/*
 * FlipTunes for Flipper Zero  -  v1.0.0
 * https://github.com/imamjesly/fliptunes   (GPL-3.0)
 *
 * Shows what's playing on your Android phone - title, artist, player app,
 * progress bar and a 48x48 album cover - and controls it from the Flipper.
 * Needs the FlipTunes Android app (in the same repo).
 *
 * Controls:
 *   OK               Play / Pause          Hold OK    Settings
 *   Left / Right     Previous / Next       Hold       Seek back / forward
 *   Up / Down        Volume (hold to repeat)
 *   Back             Exit                  Hold Back  Lock / unlock buttons
 *
 * Settings (saved to /ext/apps_data/fliptunes):
 *   Key vibration, Track change buzz, LED beat pulse, Beat sensitivity,
 *   Beat sync offset, Keep screen on, Seek step, Lock buttons now.
 *
 * Link: the Flipper's Bluetooth serial service, one text line per message.
 *   phone -> Flipper:  T:title  A:artist  P:player  S:playing,pos,dur  I:art  N
 *                      M:period,next (tempo lock)  B (single kick)
 *   Flipper -> phone:  C:PP C:NX C:PV C:VU C:VD  C:RA (resend all)
 *                      C:SK:<+/-seconds> (seek)  C:BT:<1/0> (beats on/off)
 *                      C:BS:<1-3> (beat sensitivity)
 *   Full details: docs/PROTOCOL.md
 */

#include <furi.h>
#include <furi_hal_bt.h>
#include <bt/bt_service/bt.h>
#include <profiles/serial_profile.h>
#include <gui/gui.h>
#include <gui/elements.h>
#include <notification/notification_messages.h>
#include <storage/storage.h>
#include <toolbox/saved_struct.h>

#define TAG "FlipTunes"

#define RX_BUFFER_SIZE 2048
#define LINE_MAX_LEN 520
#define ART_SIZE 48
#define ART_BYTES (ART_SIZE / 8 * ART_SIZE)

#define SETTINGS_DIR EXT_PATH("apps_data/fliptunes")
#define SETTINGS_PATH SETTINGS_DIR "/settings.bin"
#define SETTINGS_MAGIC 0x4E
#define SETTINGS_VERSION 1

#define BEAT_TIMEOUT_MS 3000 /* no beats from the phone for this long -> use steady tempo */
#define FALLBACK_BEAT_MS 500 /* steady tempo fallback = 120 BPM */
#define SKIP_GRACE_MS 3000 /* track changes this soon after Next/Prev don't buzz */

/* ------------------------------------------------------------------------ */
/* Vibration and LED patterns                                               */
/* ------------------------------------------------------------------------ */


/* Two gentle taps when the phone moves to a new song by itself */
static const NotificationSequence seq_track_buzz = {
    &message_vibro_on,
    &message_delay_50,
    &message_vibro_off,
    &message_delay_100,
    &message_vibro_on,
    &message_delay_50,
    &message_vibro_off,
    NULL,
};

/* Orange LED flash for a beat (red full + a little green = orange) */
static const NotificationMessage message_green_60 = {
    .type = NotificationMessageTypeLedGreen,
    .data.led.value = 60,
};
static const NotificationSequence seq_beat = {
    &message_red_255,
    &message_green_60,
    &message_delay_50,
    &message_red_0,
    &message_green_0,
    NULL,
};

/* ------------------------------------------------------------------------ */
/* Settings                                                                 */
/* ------------------------------------------------------------------------ */

typedef struct {
    uint8_t key_vibro;
    uint8_t track_vibro;
    uint8_t led_pulse;
    uint8_t keep_screen_on;
    uint8_t seek_step; /* seconds */
    uint8_t beat_sensitivity; /* 1 low, 2 medium, 3 high */
    int16_t beat_offset_ms; /* move flashes earlier (-) or later (+) */
} Settings;

typedef enum {
    SettingKeyVibro,
    SettingTrackVibro,
    SettingLedPulse,
    SettingBeatSensitivity,
    SettingBeatOffset,
    SettingKeepScreenOn,
    SettingSeekStep,
    SettingLockNow,
    SettingCount,
} SettingItem;

static const uint8_t seek_steps[] = {5, 10, 15, 30};
#define MENU_ROWS 6 /* settings rows that fit on screen */
#define OFFSET_STEP 20
#define OFFSET_LIMIT 400

/* ------------------------------------------------------------------------ */
/* App state                                                                */
/* ------------------------------------------------------------------------ */

typedef enum {
    ScreenPlayer,
    ScreenSettings,
} Screen;

typedef struct {
    Gui* gui;
    Bt* bt;
    NotificationApp* notifications;
    ViewPort* view_port;
    FuriMessageQueue* input_queue;
    FuriHalBleProfileBase* serial;
    FuriStreamBuffer* rx_stream;
    char line[LINE_MAX_LEN];
    size_t line_len;

    /* --- shared with drawing: guarded by mutex --- */
    FuriMutex* mutex;
    Screen screen;
    size_t menu_index;
    Settings settings;
    bool locked;

    bool connected;
    bool linked;
    bool has_track;
    FuriString* title;
    FuriString* artist;
    FuriString* player;
    bool playing;
    uint32_t position_s;
    uint32_t duration_s;
    uint32_t position_tick;
    bool has_art;
    uint8_t art[ART_BYTES];
    size_t scroll;

    const char* feedback;
    uint32_t feedback_tick;

    /* --- events for the main loop (set under mutex, handled outside) --- */
    bool want_sync;
    bool want_track_buzz;
    bool want_beat;
    uint32_t last_skip_tick; /* when the user last pressed Next/Prev */
    uint32_t last_beat_rx_tick; /* when the phone last sent a beat */
    uint32_t repeat_count; /* repeats since the button went down (main loop only) */

    /* Tempo lock from the phone: the Flipper's own metronome */
    uint32_t tempo_period_ms;
    uint32_t next_beat_tick;
    uint32_t tempo_rx_tick;
} App;

/* ------------------------------------------------------------------------ */
/* Helpers                                                                  */
/* ------------------------------------------------------------------------ */

static uint32_t current_position(App* app) {
    uint32_t pos = app->position_s;
    if(app->playing) pos += (furi_get_tick() - app->position_tick) / 1000; /* ticks are ms */
    if(app->duration_s && pos > app->duration_s) pos = app->duration_s;
    return pos;
}

static void format_time(char* out, size_t size, uint32_t seconds) {
    snprintf(out, size, "%d:%02d", (int)(seconds / 60), (int)(seconds % 60));
}

static int base64_value(char c) {
    if(c >= 'A' && c <= 'Z') return c - 'A';
    if(c >= 'a' && c <= 'z') return c - 'a' + 26;
    if(c >= '0' && c <= '9') return c - '0' + 52;
    if(c == '+') return 62;
    if(c == '/') return 63;
    return -1;
}

static size_t base64_decode(const char* in, uint8_t* out, size_t max) {
    uint32_t acc = 0;
    int bits = 0;
    size_t n = 0;
    for(; *in && *in != '='; in++) {
        int v = base64_value(*in);
        if(v < 0) continue;
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if(bits >= 8) {
            bits -= 8;
            if(n < max) out[n++] = (acc >> bits) & 0xFF;
            acc &= (1u << bits) - 1;
        }
    }
    return n;
}

static void settings_load(App* app) {
    Settings defaults = {
        .key_vibro = 1,
        .track_vibro = 1,
        .led_pulse = 1,
        .keep_screen_on = 0,
        .seek_step = 10,
        .beat_sensitivity = 3,
        .beat_offset_ms = 0};
    if(!saved_struct_load(
           SETTINGS_PATH, &app->settings, sizeof(Settings), SETTINGS_MAGIC, SETTINGS_VERSION)) {
        app->settings = defaults;
    }
}

static void settings_save(App* app) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    storage_simply_mkdir(storage, SETTINGS_DIR);
    furi_record_close(RECORD_STORAGE);
    saved_struct_save(
        SETTINGS_PATH, &app->settings, sizeof(Settings), SETTINGS_MAGIC, SETTINGS_VERSION);
}

/* Screen stays on while "Keep screen on" is set or the buttons are locked */
static void apply_backlight(App* app) {
    bool on = app->settings.keep_screen_on || app->locked;
    notification_message(
        app->notifications,
        on ? &sequence_display_backlight_enforce_on : &sequence_display_backlight_enforce_auto);
}

static void send_line(App* app, const char* text) {
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    bool connected = app->connected;
    furi_mutex_release(app->mutex);
    if(!connected) return;
    ble_profile_serial_tx(app->serial, (uint8_t*)text, (uint16_t)strlen(text));
}

static void send_command(App* app, const char* code) {
    char msg[32];
    snprintf(msg, sizeof(msg), "C:%s\n", code);
    send_line(app, msg);
    FURI_LOG_I(TAG, "sent %s", code);
}

/* Tell the phone whether to listen for beats, and how sensitively.
 * Sent as ONE Bluetooth packet: back-to-back packets can be dropped while the
 * previous one is still being delivered. (With with_sync, "C:RA" is added first.) */
static void send_beat_setting_ex(App* app, bool with_sync) {
    char msg[48];
    snprintf(
        msg,
        sizeof(msg),
        "%sC:BT:%d\nC:BS:%d\n",
        with_sync ? "C:RA\n" : "",
        app->settings.led_pulse ? 1 : 0,
        app->settings.beat_sensitivity);
    send_line(app, msg);
    FURI_LOG_I(TAG, "sent beat settings%s", with_sync ? " + resend request" : "");
}

static void send_beat_setting(App* app) {
    send_beat_setting_ex(app, false);
}

static void set_feedback(App* app, const char* text) {
    app->feedback = text;
    app->feedback_tick = furi_get_tick();
}

/* ------------------------------------------------------------------------ */
/* Messages from the phone                                                  */
/* ------------------------------------------------------------------------ */

static void handle_line(App* app, char* line) {
    size_t len = strlen(line);
    if(len && line[len - 1] == '\r') line[--len] = '\0';
    if(len == 0) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    if(len == 1 && line[0] == 'B') {
        /* Beat detected on the phone */
        app->want_beat = true;
        app->last_beat_rx_tick = furi_get_tick();
    } else if(len == 1 && line[0] == 'N') {
        app->linked = true;
        app->has_track = false;
        app->has_art = false;
    } else if(len >= 2 && line[1] == ':') {
        const char* value = line + 2;
        if(!app->linked) app->want_sync = true;
        app->linked = true;
        switch(line[0]) {
        case 'T':
            if(furi_string_cmp_str(app->title, value) != 0) {
                /* New song. Buzz only if the phone moved on by itself,
                 * not right after the user pressed Next/Previous. */
                if(app->has_track && furi_get_tick() - app->last_skip_tick > SKIP_GRACE_MS) {
                    app->want_track_buzz = true;
                }
                furi_string_set_str(app->title, value);
                app->scroll = 0;
            }
            app->has_track = true;
            break;
        case 'A':
            furi_string_set_str(app->artist, value);
            break;
        case 'P':
            furi_string_set_str(app->player, value);
            break;
        case 'S': {
            char* end = NULL;
            unsigned long playing = strtoul(value, &end, 10);
            if(end && *end == ',') {
                unsigned long pos = strtoul(end + 1, &end, 10);
                if(end && *end == ',') {
                    unsigned long dur = strtoul(end + 1, &end, 10);
                    app->playing = playing != 0;
                    app->position_s = pos;
                    app->duration_s = dur;
                    app->position_tick = furi_get_tick();
                }
            }
            break;
        }
        case 'M': {
            /* M:<period ms>,<ms until next beat>  -> (re)sync our metronome */
            char* end = NULL;
            unsigned long period = strtoul(value, &end, 10);
            if(end && *end == ',' && period >= 250 && period <= 1500) {
                long next_in = (long)strtoul(end + 1, &end, 10);
                uint32_t now = furi_get_tick();
                int32_t target = (int32_t)now + (int32_t)next_in + app->settings.beat_offset_ms;
                while(target < (int32_t)now) target += (int32_t)period;
                app->tempo_period_ms = (uint32_t)period;
                app->next_beat_tick = (uint32_t)target;
                app->tempo_rx_tick = now;
            }
            break;
        }
        case 'I':
            app->has_art = (value[0] != '-') &&
                           (base64_decode(value, app->art, ART_BYTES) == ART_BYTES);
            break;
        default:
            break;
        }
    }
    furi_mutex_release(app->mutex);
}

static bool drain_rx(App* app) {
    uint8_t chunk[64];
    bool got_any = false;
    size_t n;
    while((n = furi_stream_buffer_receive(app->rx_stream, chunk, sizeof(chunk), 0)) > 0) {
        got_any = true;
        for(size_t i = 0; i < n; i++) {
            char c = (char)chunk[i];
            if(c == '\n') {
                app->line[app->line_len] = '\0';
                handle_line(app, app->line);
                app->line_len = 0;
            } else if(app->line_len < LINE_MAX_LEN - 1) {
                app->line[app->line_len++] = c;
            } else {
                app->line_len = 0;
            }
        }
    }
    return got_any;
}

/* ------------------------------------------------------------------------ */
/* Firmware callbacks                                                       */
/* ------------------------------------------------------------------------ */

static uint16_t serial_callback(SerialServiceEvent event, void* ctx) {
    App* app = ctx;
    if(event.event == SerialServiceEventTypeDataReceived) {
        furi_stream_buffer_send(app->rx_stream, event.data.buffer, event.data.size, 0);
    }
    return (uint16_t)furi_stream_buffer_spaces_available(app->rx_stream);
}

static void claim_serial(App* app) {
    ble_profile_serial_set_event_callback(app->serial, RX_BUFFER_SIZE, serial_callback, app);
    ble_profile_serial_set_rpc_active(app->serial, false);
}

static void bt_status_callback(BtStatus status, void* ctx) {
    App* app = ctx;
    bool connected = (status == BtStatusConnected);
    if(connected) claim_serial(app);
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->connected = connected;
    if(!connected) app->linked = false;
    furi_mutex_release(app->mutex);
    view_port_update(app->view_port);
}

static void input_callback(InputEvent* event, void* ctx) {
    App* app = ctx;
    furi_message_queue_put(app->input_queue, event, 0);
}

/* ------------------------------------------------------------------------ */
/* Drawing                                                                  */
/* ------------------------------------------------------------------------ */

static void draw_play_glyph(Canvas* canvas, int x, int y, bool playing) {
    if(playing) {
        canvas_draw_box(canvas, x, y, 3, 9);
        canvas_draw_box(canvas, x + 5, y, 3, 9);
    } else {
        for(int i = 0; i < 5; i++) canvas_draw_line(canvas, x + i, y + i, x + i, y + 8 - i);
    }
}

/* Small padlock: 7 x 9 pixels */
static void draw_lock_glyph(Canvas* canvas, int x, int y) {
    canvas_draw_frame(canvas, x + 1, y, 5, 5);
    canvas_draw_box(canvas, x, y + 4, 7, 5);
}

static const char* on_off(uint8_t v) {
    return v ? "On" : "Off";
}

static void draw_settings(Canvas* canvas, App* app) {
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "Settings");
    canvas_set_font(canvas, FontSecondary);

    char step[16];
    snprintf(step, sizeof(step), "%d s", app->settings.seek_step);
    char offset[24];
    snprintf(offset, sizeof(offset), "%+d ms", app->settings.beat_offset_ms);
    static const char* sens_names[] = {"", "Low", "Medium", "High"};
    const char* labels[SettingCount] = {
        "Key vibration",
        "Track change buzz",
        "LED beat pulse",
        "Beat sensitivity",
        "Beat sync offset",
        "Keep screen on",
        "Seek step",
        "Lock buttons now",
    };
    const char* values[SettingCount] = {
        on_off(app->settings.key_vibro),
        on_off(app->settings.track_vibro),
        on_off(app->settings.led_pulse),
        sens_names[app->settings.beat_sensitivity <= 3 ? app->settings.beat_sensitivity : 3],
        offset,
        on_off(app->settings.keep_screen_on),
        step,
        ">",
    };

    /* Scroll the list so the selected row is always visible */
    size_t first = (app->menu_index >= MENU_ROWS) ? app->menu_index - MENU_ROWS + 1 : 0;
    char pos[24];
    snprintf(pos, sizeof(pos), "%d/%d", (int)app->menu_index + 1, (int)SettingCount);
    canvas_draw_str_aligned(canvas, 125, 9, AlignRight, AlignBottom, pos);

    for(size_t i = first; i < SettingCount && i < first + MENU_ROWS; i++) {
        int y = 11 + (int)(i - first) * 9; /* row top */
        if(i == app->menu_index) {
            canvas_draw_box(canvas, 0, y, 128, 9);
            canvas_set_color(canvas, ColorWhite);
        }
        canvas_draw_str(canvas, 3, y + 8, labels[i]);
        canvas_draw_str_aligned(canvas, 125, y + 8, AlignRight, AlignBottom, values[i]);
        canvas_set_color(canvas, ColorBlack);
    }
}

static void draw_player(Canvas* canvas, App* app) {
    if(!app->linked) {
        canvas_set_font(canvas, FontPrimary);
        canvas_draw_str(canvas, 2, 11, "FlipTunes");
        canvas_set_font(canvas, FontSecondary);
        if(!app->connected) {
            canvas_draw_str(canvas, 2, 25, "Waiting for phone...");
            canvas_draw_str(canvas, 2, 36, "On phone: open FlipTunes");
            canvas_draw_str(canvas, 2, 46, "and tap Start bridge");
        } else {
            canvas_draw_str(canvas, 2, 25, "Phone connected.");
            canvas_draw_str(canvas, 2, 36, "Waiting for song info...");
        }
        canvas_draw_str(canvas, 2, 62, "Hold OK: settings");
        return;
    }
    if(!app->has_track) {
        canvas_set_font(canvas, FontPrimary);
        canvas_draw_str_aligned(canvas, 64, 26, AlignCenter, AlignBottom, "Nothing playing");
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str_aligned(canvas, 64, 40, AlignCenter, AlignBottom, "Press OK to resume");
        return;
    }

    if(app->has_art) {
        canvas_draw_xbm(canvas, 0, 0, ART_SIZE, ART_SIZE, app->art);
    } else {
        canvas_draw_frame(canvas, 0, 0, ART_SIZE, ART_SIZE);
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str_aligned(canvas, 24, 24, AlignCenter, AlignCenter, "No art");
    }

    const int x = 52;
    const size_t w = 76;
    canvas_set_font(canvas, FontPrimary);
    elements_scrollable_text_line(canvas, x, 10, w, app->title, app->scroll, false);
    canvas_set_font(canvas, FontSecondary);
    elements_scrollable_text_line(canvas, x, 21, w, app->artist, app->scroll, false);
    elements_scrollable_text_line(canvas, x, 31, w, app->player, 0, true);

    uint32_t pos = current_position(app);
    canvas_draw_frame(canvas, x, 35, w, 5);
    if(app->duration_s > 0) {
        uint32_t fill = (uint32_t)(w - 2) * pos / app->duration_s;
        if(fill > w - 2) fill = w - 2;
        canvas_draw_box(canvas, x + 1, 36, fill, 3);
    }
    char t[32];
    format_time(t, sizeof(t), pos);
    canvas_draw_str(canvas, x, 49, t);
    format_time(t, sizeof(t), app->duration_s);
    canvas_draw_str_aligned(canvas, 127, 49, AlignRight, AlignBottom, t);

    draw_play_glyph(canvas, 2, 54, app->playing);
    canvas_draw_str(canvas, 14, 63, app->playing ? "Playing" : "Paused");

    /* Bottom-right: padlock while locked, otherwise brief press feedback */
    if(app->locked) {
        draw_lock_glyph(canvas, 120, 54);
    } else if(app->feedback && furi_get_tick() - app->feedback_tick < 1200) {
        canvas_draw_str_aligned(canvas, 127, 64, AlignRight, AlignBottom, app->feedback);
    }
}

static void draw_callback(Canvas* canvas, void* ctx) {
    App* app = ctx;
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    canvas_clear(canvas);
    if(app->screen == ScreenSettings) {
        draw_settings(canvas, app);
    } else {
        draw_player(canvas, app);
    }
    furi_mutex_release(app->mutex);
}

/* ------------------------------------------------------------------------ */
/* Buttons                                                                  */
/* ------------------------------------------------------------------------ */

static void key_tick(App* app) {
    /* Standard Flipper vibration (can be switched off in Settings) */
    if(app->settings.key_vibro) notification_message(app->notifications, &sequence_single_vibro);
}

/* Settings screen: Up/Down move, OK/Left/Right change, Back closes */
static void handle_settings_input(App* app, InputEvent* event) {
    if(event->type != InputTypeShort && event->type != InputTypeRepeat) return;

    bool changed = false;
    bool close = false;
    bool beat_changed = false;
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    Settings* s = &app->settings;
    switch(event->key) {
    case InputKeyUp:
        app->menu_index = (app->menu_index + SettingCount - 1) % SettingCount;
        break;
    case InputKeyDown:
        app->menu_index = (app->menu_index + 1) % SettingCount;
        break;
    case InputKeyBack:
        if(event->type == InputTypeShort) close = true;
        break;
    case InputKeyOk:
    case InputKeyLeft:
    case InputKeyRight:
        changed = true;
        switch(app->menu_index) {
        case SettingKeyVibro:
            s->key_vibro = !s->key_vibro;
            break;
        case SettingTrackVibro:
            s->track_vibro = !s->track_vibro;
            break;
        case SettingLedPulse:
            s->led_pulse = !s->led_pulse;
            beat_changed = true;
            break;
        case SettingBeatSensitivity:
            /* Right/OK = more sensitive, Left = less; wraps around */
            if(event->key == InputKeyLeft) {
                s->beat_sensitivity = (s->beat_sensitivity <= 1) ? 3 : s->beat_sensitivity - 1;
            } else {
                s->beat_sensitivity = (s->beat_sensitivity >= 3) ? 1 : s->beat_sensitivity + 1;
            }
            beat_changed = true;
            break;
        case SettingBeatOffset: {
            /* Left = flash earlier, Right/OK = later, 20 ms steps */
            int v = s->beat_offset_ms + ((event->key == InputKeyLeft) ? -OFFSET_STEP : OFFSET_STEP);
            if(v < -OFFSET_LIMIT) v = -OFFSET_LIMIT;
            if(v > OFFSET_LIMIT) v = OFFSET_LIMIT;
            s->beat_offset_ms = (int16_t)v;
            break;
        }
        case SettingKeepScreenOn:
            s->keep_screen_on = !s->keep_screen_on;
            break;
        case SettingSeekStep: {
            /* cycle 5 -> 10 -> 15 -> 30 (Left goes backwards) */
            size_t n = sizeof(seek_steps) / sizeof(seek_steps[0]);
            size_t i = 0;
            while(i < n && seek_steps[i] != s->seek_step) i++;
            if(i >= n) i = 1;
            i = (event->key == InputKeyLeft) ? (i + n - 1) % n : (i + 1) % n;
            s->seek_step = seek_steps[i];
            break;
        }
        case SettingLockNow:
            if(event->key == InputKeyOk) {
                app->locked = true;
                close = true;
            }
            changed = false;
            break;
        default:
            break;
        }
        break;
    default:
        break;
    }
    if(close) app->screen = ScreenPlayer;
    furi_mutex_release(app->mutex);

    if(changed) {
        settings_save(app);
        key_tick(app);
    }
    if(beat_changed) send_beat_setting(app);
    apply_backlight(app);
}

/* Returns false when the app should exit */
static bool handle_input(App* app, InputEvent* event) {
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    Screen screen = app->screen;
    bool locked = app->locked;
    furi_mutex_release(app->mutex);

    /* Hold Back: lock / unlock buttons (works everywhere on the player screen) */
    if(screen == ScreenPlayer && event->key == InputKeyBack && event->type == InputTypeLong) {
        furi_mutex_acquire(app->mutex, FuriWaitForever);
        app->locked = !app->locked;
        set_feedback(app, app->locked ? "Locked" : "Unlocked");
        furi_mutex_release(app->mutex);
        notification_message(app->notifications, &seq_track_buzz);
        apply_backlight(app);
        return true;
    }
    if(locked) return true; /* everything else is ignored while locked */

    if(screen == ScreenSettings) {
        handle_settings_input(app, event);
        return true;
    }

    if(event->key == InputKeyBack) return event->type != InputTypeShort;

    /* Hold OK: open settings */
    if(event->key == InputKeyOk && event->type == InputTypeLong) {
        furi_mutex_acquire(app->mutex, FuriWaitForever);
        app->screen = ScreenSettings;
        app->menu_index = 0;
        furi_mutex_release(app->mutex);
        key_tick(app);
        return true;
    }

    const char* code = NULL;
    const char* label = NULL;
    int seek = 0;
    char seek_code[24];

    if(event->type == InputTypeShort) {
        if(event->key == InputKeyOk) code = "PP", label = "Play/Pause";
        if(event->key == InputKeyLeft) code = "PV", label = "Previous";
        if(event->key == InputKeyRight) code = "NX", label = "Next";
    }
    if(event->type == InputTypePress || event->type == InputTypeRepeat) {
        if(event->key == InputKeyUp) code = "VU", label = "Vol +";
        if(event->key == InputKeyDown) code = "VD", label = "Vol -";
    }
    /* Hold Left/Right: seek. Long = first jump, then every 3rd repeat (~0.5 s) */
    if(event->type == InputTypePress) app->repeat_count = 0;
    if(event->type == InputTypeRepeat) app->repeat_count++;
    if((event->key == InputKeyLeft || event->key == InputKeyRight) &&
       (event->type == InputTypeLong ||
        (event->type == InputTypeRepeat && app->repeat_count % 3 == 0))) {
        seek = (event->key == InputKeyLeft ? -1 : 1) * (int)app->settings.seek_step;
        snprintf(seek_code, sizeof(seek_code), "SK:%d", seek);
        code = seek_code;
        label = (seek < 0) ? "<< Seek" : "Seek >>";
    }
    if(!code) return true;

    send_command(app, code);
    key_tick(app);

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    set_feedback(app, label);
    if(event->key == InputKeyOk) {
        app->position_s = current_position(app);
        app->position_tick = furi_get_tick();
        app->playing = !app->playing;
    }
    if(seek) {
        /* Move the bar straight away; the phone confirms shortly */
        int32_t pos = (int32_t)current_position(app) + seek;
        if(pos < 0) pos = 0;
        if(app->duration_s && (uint32_t)pos > app->duration_s) pos = (int32_t)app->duration_s;
        app->position_s = (uint32_t)pos;
        app->position_tick = furi_get_tick();
    }
    if(code[0] == 'P' && code[1] == 'V') app->last_skip_tick = furi_get_tick();
    if(code[0] == 'N' && code[1] == 'X') app->last_skip_tick = furi_get_tick();
    furi_mutex_release(app->mutex);

    view_port_update(app->view_port);
    return true;
}

/* ------------------------------------------------------------------------ */
/* Entry point                                                              */
/* ------------------------------------------------------------------------ */

int32_t fliptunes_app(void* p) {
    UNUSED(p);
    App* app = malloc(sizeof(App));
    memset(app, 0, sizeof(App));

    app->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app->input_queue = furi_message_queue_alloc(8, sizeof(InputEvent));
    app->rx_stream = furi_stream_buffer_alloc(RX_BUFFER_SIZE, 1);
    app->title = furi_string_alloc();
    app->artist = furi_string_alloc();
    app->player = furi_string_alloc();
    app->notifications = furi_record_open(RECORD_NOTIFICATION);
    app->gui = furi_record_open(RECORD_GUI);
    app->bt = furi_record_open(RECORD_BT);
    settings_load(app);
    apply_backlight(app);

    app->view_port = view_port_alloc();
    view_port_draw_callback_set(app->view_port, draw_callback, app);
    view_port_input_callback_set(app->view_port, input_callback, app);
    gui_add_view_port(app->gui, app->view_port, GuiLayerFullscreen);

    bt_disconnect(app->bt);
    furi_delay_ms(200);
    app->serial = bt_profile_start(app->bt, ble_profile_serial, NULL);
    furi_check(app->serial);
    furi_hal_bt_start_advertising();
    bt_set_status_changed_callback(app->bt, bt_status_callback, app);

    InputEvent event;
    bool running = true;
    uint32_t last_redraw = 0, last_scroll = 0, last_claim = 0, last_fallback_beat = 0;
    while(running) {
        /* 10 ms loop so metronome flashes land within ~10 ms of the beat */
        if(furi_message_queue_get(app->input_queue, &event, 10) == FuriStatusOk) {
            running = handle_input(app, &event);
        }
        bool got_data = drain_rx(app);
        uint32_t now = furi_get_tick();

        /* Collect events under the lock, act on them after */
        furi_mutex_acquire(app->mutex, FuriWaitForever);
        bool connected = app->connected;
        bool linked = app->linked;
        bool want_sync = app->want_sync;
        bool want_buzz = app->want_track_buzz && app->settings.track_vibro;
        bool led_on = app->settings.led_pulse && app->playing && app->has_track;
        bool beat = app->want_beat;
        bool phone_beats = (now - app->last_beat_rx_tick) < BEAT_TIMEOUT_MS;
        bool tempo_ok = app->tempo_period_ms && (now - app->tempo_rx_tick) < BEAT_TIMEOUT_MS;
        bool tempo_flash = false;
        if(tempo_ok && (int32_t)(now - app->next_beat_tick) >= 0) {
            tempo_flash = true;
            while((int32_t)(now - app->next_beat_tick) >= 0) app->next_beat_tick += app->tempo_period_ms;
        }
        app->want_sync = false;
        app->want_track_buzz = false;
        app->want_beat = false;
        if(now - last_scroll > 400) {
            app->scroll++;
            last_scroll = now;
        }
        furi_mutex_release(app->mutex);

        if(want_sync) send_beat_setting_ex(app, true);
        if(want_buzz) notification_message(app->notifications, &seq_track_buzz);

        /* LED priority: 1) metronome locked to the song's tempo
         *               2) single kicks from the phone (no steady tempo found)
         *               3) steady 120 BPM if the phone sends nothing */
        if(led_on) {
            if(tempo_ok) {
                if(tempo_flash) notification_message(app->notifications, &seq_beat);
            } else if(beat) {
                notification_message(app->notifications, &seq_beat);
            } else if(!phone_beats && now - last_fallback_beat > FALLBACK_BEAT_MS) {
                notification_message(app->notifications, &seq_beat);
                last_fallback_beat = now;
            }
        }

        if(connected && !linked && now - last_claim > 2000) {
            claim_serial(app);
            last_claim = now;
        }
        if(got_data || now - last_redraw > 250) {
            view_port_update(app->view_port);
            last_redraw = now;
        }
    }

    /* Clean up and give Bluetooth back to the Flipper */
    bt_set_status_changed_callback(app->bt, NULL, NULL);
    ble_profile_serial_set_event_callback(app->serial, 0, NULL, NULL);
    bt_disconnect(app->bt);
    furi_delay_ms(200);
    bt_profile_restore_default(app->bt);
    notification_message(app->notifications, &sequence_reset_rgb);
    notification_message(app->notifications, &sequence_display_backlight_enforce_auto);

    gui_remove_view_port(app->gui, app->view_port);
    view_port_free(app->view_port);
    furi_record_close(RECORD_BT);
    furi_record_close(RECORD_GUI);
    furi_record_close(RECORD_NOTIFICATION);
    furi_string_free(app->title);
    furi_string_free(app->artist);
    furi_string_free(app->player);
    furi_stream_buffer_free(app->rx_stream);
    furi_message_queue_free(app->input_queue);
    furi_mutex_free(app->mutex);
    free(app);
    return 0;
}
