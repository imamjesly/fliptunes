package com.imamjesly.fliptunes

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What is playing on the phone right now. */
data class NowPlaying(
    val appName: String = "",
    val packageName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val art: Bitmap? = null,
)

/** State of the Bluetooth link to the Flipper. */
enum class LinkState { OFF, CONNECTING, CONNECTED, ERROR }

/** Shared, observable app state. The screen watches these; the service updates them. */
object AppState {
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** The 48x48 black-and-white cover as sent to the Flipper (for the on-phone preview). */
    val artBits = MutableStateFlow<ByteArray?>(null)
    val link = MutableStateFlow(LinkState.OFF)
    val linkDetail = MutableStateFlow("Not started")
    val bridgeRunning = MutableStateFlow(false)
    val beatStatus = MutableStateFlow("Off")
    val log = MutableStateFlow<List<String>>(emptyList())

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.value = (listOf("$time  $message") + log.value).take(40)
    }
}

/** Lets the screen reach the running bridge (null when it isn't running). */
object Bridge {
    @Volatile var watcher: MediaWatcher? = null
    @Volatile var link: FlipperLink? = null

    fun command(command: Protocol.Command) {
        watcher?.command(command)
    }
}

/** Remembers which Flipper the user picked. */
object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fliptunes", Context.MODE_PRIVATE)
    fun address(ctx: Context): String? = prefs(ctx).getString("address", null)
    fun name(ctx: Context): String? = prefs(ctx).getString("name", null)
    fun save(ctx: Context, address: String, name: String) {
        prefs(ctx).edit().putString("address", address).putString("name", name).apply()
    }
}
