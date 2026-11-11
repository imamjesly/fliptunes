package com.imamjesly.fliptunes

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/**
 * Watches the phone's media sessions (Spotify, YouTube Music, etc.),
 * publishes what's playing to AppState, and carries out commands.
 */
class MediaWatcher(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    /** Returns false if Notification access hasn't been granted yet. */
    fun start(): Boolean {
        if (!hasAccess(context)) return false
        return try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            refresh()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        try { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(controllerCallback)
        controller = null
        AppState.nowPlaying.value = null
    }

    /** Re-read the session list and pick the best one. */
    fun refresh() {
        try { pick(sessionManager.getActiveSessions(listenerComponent)) } catch (_: SecurityException) {}
    }

    /** Prefer whichever app is actually playing; otherwise the most recent one. */
    private fun pick(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            best?.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    /** Build a NowPlaying snapshot from the current session. */
    fun publish() {
        val c = controller
        if (c == null) {
            AppState.nowPlaying.value = null
            return
        }
        val meta = c.metadata
        val state = c.playbackState
        AppState.nowPlaying.value = NowPlaying(
            appName = appLabel(c.packageName),
            packageName = c.packageName,
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.description?.title?.toString() ?: "",
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = currentPosition(state),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        )
    }

    /** Apps only report position occasionally, so estimate it from the last update. */
    private fun currentPosition(state: PlaybackState?): Long {
        if (state == null) return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position.coerceAtLeast(0)
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return (state.position + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0)
    }

    /** Carry out a command from the Flipper (or the test buttons). */
    fun command(command: Protocol.Command, arg: Int = 0) {
        handler.post {
            val c = controller
            val controls = c?.transportControls
            when (command) {
                Protocol.Command.PLAY_PAUSE ->
                    if (c == null) {
                        // Nothing active: send a media key so the last player resumes
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    } else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controls?.pause()
                    } else {
                        controls?.play()
                    }
                Protocol.Command.NEXT -> controls?.skipToNext()
                Protocol.Command.PREVIOUS -> controls?.skipToPrevious()
                Protocol.Command.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
                Protocol.Command.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
                Protocol.Command.SEEK -> {
                    val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                    var target = currentPosition(c?.playbackState) + arg * 1000L
                    if (target < 0) target = 0
                    if (duration > 0 && target > duration) target = duration
                    controls?.seekTo(target)
                    handler.postDelayed({ publish() }, 300) // report the new position
                }
                Protocol.Command.RESEND_ALL, Protocol.Command.BEATS, Protocol.Command.BEAT_SENSITIVITY -> Unit // handled by BridgeService
            }
            AppState.log("Did: ${command.name}" + if (command == Protocol.Command.SEEK) " ${arg}s" else "")
        }
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun sendMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
