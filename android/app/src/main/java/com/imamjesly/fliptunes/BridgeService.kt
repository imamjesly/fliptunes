package com.imamjesly.fliptunes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background service that joins the two halves:
 *   MediaWatcher (what's playing)  ->  FlipperLink (send to Flipper)
 *   FlipperLink (button pressed)   ->  MediaWatcher (control the player)
 * It shows a small ongoing notification, which Android requires for this.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var watcher: MediaWatcher
    private lateinit var link: FlipperLink
    private lateinit var beats: BeatDetector
    private var lastSent = ""
    private var lastArt = ""
    private var lastArtSource: android.graphics.Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        watcher = MediaWatcher(this)
        link = FlipperLink(this) { fc ->
            when (fc.command) {
                Protocol.Command.RESEND_ALL -> sendNow()
                Protocol.Command.BEATS -> beats.setWanted(fc.arg == 1)
                Protocol.Command.BEAT_SENSITIVITY -> beats.updateSensitivity(fc.arg)
                else -> watcher.command(fc.command, fc.arg)
            }
        }
        beats = BeatDetector(
            this,
            onBeat = { link.send(Protocol.BEAT_LINE) },
            onTempo = { period, nextIn -> link.send(Protocol.encodeTempo(period, nextIn)) },
        )
        link.onReady = { sendNow() }
        Bridge.watcher = watcher
        Bridge.link = link
        AppState.bridgeRunning.value = true
        AppState.log("Bridge started")

        if (!watcher.start()) AppState.log("Notification access is missing")
        link.start()

        // Send the song to the Flipper whenever it changes
        scope.launch { AppState.nowPlaying.collect { sendIfChanged() } }
        // Every 5 s re-read the position so the Flipper's progress bar stays accurate,
        // and every 30 s resend everything (incl. the cover) in case a message was lost
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(5000)
                watcher.publish()
                beats.refresh()
                if (++ticks % 6 == 0) sendNow()
            }
        }
        // Keep the notification text in sync with the link
        scope.launch {
            AppState.linkDetail.collect { detail ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(detail))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        beats.stop()
        link.stop()
        watcher.stop()
        Bridge.watcher = null
        Bridge.link = null
        AppState.bridgeRunning.value = false
        AppState.log("Bridge stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendIfChanged() {
        val np = AppState.nowPlaying.value
        val message = Protocol.encode(np)
        if (message != lastSent) {
            lastSent = message
            link.send(message)
        }
        // Album art: only re-encode when the cover picture changes, only send if it differs
        if (np?.art !== lastArtSource || lastArt.isEmpty()) {
            lastArtSource = np?.art
            val bits = ArtEncoder.encode(np?.art)
            AppState.artBits.value = bits
            val artLine = Protocol.encodeArt(ArtEncoder.toBase64(bits))
            if (artLine != lastArt) {
                lastArt = artLine
                link.send(artLine)
            }
        }
    }

    private fun sendNow() {
        // Link (re)connected or Flipper asked: send everything, including the cover
        AppState.log("Sending full update" + if (AppState.artBits.value == null) " (no cover available)" else " with cover")
        lastSent = Protocol.encode(AppState.nowPlaying.value)
        link.send(lastSent)
        lastArt = Protocol.encodeArt(ArtEncoder.toBase64(AppState.artBits.value))
        link.send(lastArt)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Flipper connection", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("FlipTunes")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
    }
}
