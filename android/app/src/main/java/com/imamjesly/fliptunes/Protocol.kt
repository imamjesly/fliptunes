package com.imamjesly.fliptunes

import java.text.Normalizer
import java.util.UUID

/**
 * The message format between the phone and the Flipper. See PROTOCOL.md.
 *
 * Transport: the Flipper's standard Bluetooth serial service.
 *   Phone -> Flipper: write to the RX characteristic.
 *   Flipper -> Phone: indications on the TX characteristic.
 *
 * Every message is one line of plain ASCII text ending in '\n'.
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    val RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // phone writes here
    val TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000") // Flipper sends here
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Commands the Flipper can send. Wire format: "C:PP\n" etc. */
    enum class Command(val wire: String) {
        PLAY_PAUSE("PP"), NEXT("NX"), PREVIOUS("PV"), VOLUME_UP("VU"), VOLUME_DOWN("VD"),
        /** Flipper asks for everything again (title, state, album art). */
        RESEND_ALL("RA"),
        /** "C:SK:-10" = seek 10 s back, "C:SK:15" = 15 s forward. */
        SEEK("SK"),
        /** "C:BT:1" = please send beats for the LED pulse, "C:BT:0" = stop. */
        BEATS("BT"),
        /** "C:BS:3" = beat sensitivity 1 (low) .. 3 (high). */
        BEAT_SENSITIVITY("BS");

        companion object {
            fun fromWire(code: String): Command? = entries.firstOrNull { it.wire == code }
        }
    }

    /** A command plus its optional number, e.g. SEEK with -10. */
    data class FlipperCommand(val command: Command, val arg: Int = 0)

    /** "C:PP" -> PLAY_PAUSE, "C:SK:-10" -> SEEK(-10), anything else -> null */
    fun parseCommand(line: String): FlipperCommand? {
        if (!line.startsWith("C:")) return null
        val parts = line.removePrefix("C:").trim().split(":", limit = 2)
        val command = Command.fromWire(parts[0]) ?: return null
        return FlipperCommand(command, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** One raw kick for the Flipper's LED pulse (used when there's no steady tempo). */
    const val BEAT_LINE = "B\n"

    /** Tempo lock: beat every [periodMs], next one in [nextInMs]. The Flipper runs the metronome. */
    fun encodeTempo(periodMs: Int, nextInMs: Int): String = "M:$periodMs,$nextInMs\n"

    /**
     * Turns the current song into the text block sent to the Flipper:
     *   T:<title>
     *   A:<artist>
     *   P:<player app name>
     *   S:<1 playing / 0 paused>,<position seconds>,<duration seconds>
     * or "N" when nothing is playing.
     */
    fun encode(np: NowPlaying?): String {
        if (np == null) return "N\n"
        return buildString {
            append("T:").append(clean(np.title, 60)).append('\n')
            append("A:").append(clean(np.artist, 40)).append('\n')
            append("P:").append(clean(np.appName, 20)).append('\n')
            append("S:").append(if (np.isPlaying) 1 else 0).append(',')
                .append(np.positionMs / 1000).append(',')
                .append(np.durationMs / 1000).append('\n')
        }
    }

    /** Album art line: "I:<base64 of 288 bytes>" or "I:-" for no cover. */
    fun encodeArt(base64: String?): String = "I:" + (base64 ?: "-") + "\n"

    /**
     * The Flipper's fonts only have basic English letters, so accents are
     * removed ("Beyoncé" -> "Beyonce") and other scripts become '?'.
     */
    private fun clean(text: String, maxLength: Int): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val ascii = noAccents.map { if (it in ' '..'~') it else '?' }.joinToString("")
        return ascii.replace(Regex("\\s+"), " ").trim().take(maxLength)
    }
}
