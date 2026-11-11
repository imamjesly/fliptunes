package com.imamjesly.fliptunes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Beat tracking for the Flipper's LED pulse 
 *
 * Why not just send every detected kick?  Each message takes a variable time
 * to cross Bluetooth, so flashes land late and uneven. Instead:
 *
 *  1. ONSETS  - ~20 times a second, measure how much the bass (~40-350 Hz)
 *               suddenly got louder ("spectral flux"). A jump well above the
 *               recent average is a kick/onset.
 *  2. TEMPO   - from the last 8 s of onsets, find the most common gap between
 *               them (60-180 BPM) = the beat period.
 *  3. PHASE   - find where in that period the onsets line up = when beats fall.
 *  4. SEND    - every second: "M:<period ms>,<ms until next beat>". The Flipper
 *               runs its own metronome from that, so Bluetooth delay doesn't
 *               matter. If the tempo isn't clear (no steady beat), raw onsets
 *               are sent instead ("B").
 *
 * The audio is read from the phone's OUTPUT via Android's Visualizer, which
 * Android files under the microphone permission.
 */
class BeatDetector(
    private val context: Context,
    private val onBeat: () -> Unit,
    private val onTempo: (periodMs: Int, nextBeatInMs: Int) -> Unit,
) {
    private var visualizer: Visualizer? = null
    private val previous = FloatArray(BINS)
    private val fluxHistory = ArrayDeque<Float>()
    private val onsets = ArrayDeque<Long>()
    private var lastOnset = 0L
    private var lastTempoCheck = 0L
    private var tempoLocked = false
    private var warned = false

    /** 1 = low, 2 = medium, 3 = high (most sensitive). Set from the Flipper. */
    private var sensitivity = 3

    fun updateSensitivity(level: Int) {
        sensitivity = level.coerceIn(1, 3)
    }

    var wanted = false
        private set

    fun setWanted(on: Boolean) {
        if (on == wanted) return
        wanted = on
        if (on) start() else stop()
    }

    fun refresh() {
        if (wanted && visualizer == null) start()
    }

    fun stop() {
        visualizer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (_: Exception) {}
        }
        visualizer = null
        fluxHistory.clear()
        onsets.clear()
        tempoLocked = false
        AppState.beatStatus.value = if (wanted) "Starting..." else "Off"
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!warned) AppState.log("Beat pulse: microphone permission off, Flipper uses steady tempo")
            warned = true
            AppState.beatStatus.value = "No permission (Flipper uses steady 120 BPM)"
            return
        }
        try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) analyse(fft)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            visualizer = v
            warned = false
            AppState.log("Beat detection on")
            AppState.beatStatus.value = "Listening..."
        } catch (e: Exception) {
            if (!warned) AppState.log("Beat detection unavailable (${e.javaClass.simpleName}); steady tempo used")
            warned = true
            visualizer = null
            AppState.beatStatus.value = "Unavailable for this player (Flipper uses steady 120 BPM)"
        }
    }

    // ------------------------------------------------------------------ 1. onsets

    private fun analyse(fft: ByteArray) {
        val now = SystemClock.elapsedRealtime()

        // Spectral flux of the bass bins: total of each bin's increase since last frame
        var flux = 0f
        for (k in 1..BINS) {
            val re = fft[2 * k].toFloat()
            val im = fft[2 * k + 1].toFloat()
            val magnitude = sqrt(re * re + im * im)
            val rise = magnitude - previous[k - 1]
            if (rise > 0) flux += rise
            previous[k - 1] = magnitude
        }

        // Adaptive threshold: recent mean + K x spread. Higher sensitivity = smaller K.
        val mean = if (fluxHistory.isEmpty()) flux else fluxHistory.average().toFloat()
        var variance = 0f
        for (f in fluxHistory) variance += (f - mean) * (f - mean)
        val std = if (fluxHistory.isEmpty()) 0f else sqrt(variance / fluxHistory.size)
        val k = when (sensitivity) { 1 -> 2.0f; 2 -> 1.5f; else -> 1.1f }
        val threshold = mean + k * std + 1.5f

        fluxHistory.addLast(flux)
        if (fluxHistory.size > 20) fluxHistory.removeFirst() // ~1 s

        val isOnset = fluxHistory.size >= 10 && flux > threshold && now - lastOnset > MIN_GAP_MS
        if (isOnset) {
            lastOnset = now
            onsets.addLast(now)
            while (onsets.isNotEmpty() && now - onsets.first() > WINDOW_MS) onsets.removeFirst()
            if (!tempoLocked) onBeat() // no steady tempo yet: forward the raw kick
        }

        if (now - lastTempoCheck >= 1000) {
            lastTempoCheck = now
            updateTempo(now)
        }
    }

    // ------------------------------------------------------------------ 2-4. tempo, phase, send

    private fun updateTempo(now: Long) {
        while (onsets.isNotEmpty() && now - onsets.first() > WINDOW_MS) onsets.removeFirst()
        val estimate = estimate()
        if (estimate == null) {
            if (tempoLocked) AppState.log("Beat: lost tempo")
            tempoLocked = false
            AppState.beatStatus.value = if (onsets.size < 4) "Listening... (quiet or no beat)" else "Following kicks (no steady tempo)"
            return
        }
        val (period, phase) = estimate
        if (!tempoLocked) AppState.log("Beat: locked at ${60000 / period} BPM")
        tempoLocked = true
        val nextIn = ((phase - now % period) % period + period) % period
        onTempo(period, nextIn.toInt())
        AppState.beatStatus.value = "Locked: ${60000 / period} BPM"
    }

    /** Returns (period ms, phase ms) or null if there's no clear steady beat. */
    private fun estimate(): Pair<Int, Long>? {
        if (onsets.size < 6) return null
        val times = onsets.toList()

        // Histogram of gaps between onsets (and halves/thirds/quarters of long gaps)
        val bins = (MAX_PERIOD - MIN_PERIOD) / 10 + 1
        val hist = IntArray(bins)
        for (i in times.indices) {
            for (j in i + 1 until times.size) {
                val gap = (times[j] - times[i]).toInt()
                if (gap > 4000) break
                for (div in 1..4) {
                    val p = gap / div
                    if (p in MIN_PERIOD..MAX_PERIOD) hist[(p - MIN_PERIOD) / 10] += if (div == 1) 2 else 1
                }
            }
        }
        // Smooth and pick the strongest period
        var best = -1
        var bestScore = 0
        for (b in hist.indices) {
            val score = 2 * hist[b] + (if (b > 0) hist[b - 1] else 0) + (if (b < bins - 1) hist[b + 1] else 0)
            if (score > bestScore) { bestScore = score; best = b }
        }
        if (best < 0 || bestScore < 8) return null
        val period = MIN_PERIOD + best * 10 + 5

        // Phase = average position of onsets within the period (circular mean)
        var sx = 0.0
        var sy = 0.0
        for (t in times) {
            val angle = 2 * PI * (t % period) / period
            sx += cos(angle)
            sy += sin(angle)
        }
        val consistency = hypot(sx, sy) / times.size // 1 = perfectly regular
        if (consistency < 0.35) return null
        var phase = (atan2(sy, sx) / (2 * PI) * period).toLong()
        if (phase < 0) phase += period
        return period to phase
    }

    companion object {
        private const val BINS = 8            // bass bins ~43-350 Hz
        private const val MIN_GAP_MS = 200    // fastest kicks we accept
        private const val WINDOW_MS = 8000L   // onsets kept for tempo
        private const val MIN_PERIOD = 333    // 180 BPM
        private const val MAX_PERIOD = 1000   // 60 BPM
    }
}
