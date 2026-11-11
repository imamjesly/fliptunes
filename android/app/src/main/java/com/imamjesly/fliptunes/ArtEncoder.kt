package com.imamjesly.fliptunes

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64

/**
 * Turns album art into the Flipper's format: 48 x 48 pixels, 1 bit per pixel
 * (black or white), packed like an XBM image (8 pixels per byte, lowest bit
 * = leftmost pixel). Grey shades are faked with Floyd-Steinberg dithering,
 * the same trick old newspapers used.
 */
object ArtEncoder {
    const val SIZE = 48
    private const val ROW_BYTES = SIZE / 8

    /** Returns the 288 packed bytes, or null if there's no picture. */
    fun encode(source: Bitmap?): ByteArray? {
        if (source == null || source.width == 0 || source.height == 0) return null
        // Hardware bitmaps can't be read pixel by pixel; copy to a normal one
        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else source

        // Centre-crop to a square, then shrink to 48 x 48
        val side = minOf(readable.width, readable.height)
        val square = Bitmap.createBitmap(readable, (readable.width - side) / 2, (readable.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)

        val pixels = IntArray(SIZE * SIZE)
        small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        // Brightness 0..255, stretched so dark covers still show detail
        val lum = FloatArray(pixels.size) {
            val c = pixels[it]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        val lo = lum.min()
        val range = (lum.max() - lo).coerceAtLeast(1f)
        for (i in lum.indices) lum[i] = (lum[i] - lo) * 255f / range

        // Dither: round each pixel to black/white and spread the error to neighbours
        val out = ByteArray(ROW_BYTES * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val i = y * SIZE + x
                val old = lum[i]
                val black = old < 128f
                val error = old - (if (black) 0f else 255f)
                if (black) {
                    val b = y * ROW_BYTES + x / 8
                    out[b] = (out[b].toInt() or (1 shl (x % 8))).toByte()
                }
                if (x + 1 < SIZE) lum[i + 1] += error * 7 / 16
                if (y + 1 < SIZE) {
                    if (x > 0) lum[i + SIZE - 1] += error * 3 / 16
                    lum[i + SIZE] += error * 5 / 16
                    if (x + 1 < SIZE) lum[i + SIZE + 1] += error * 1 / 16
                }
            }
        }
        return out
    }

    fun toBase64(bits: ByteArray?): String? = bits?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /** Rebuild a picture from the packed bits, to preview on the phone exactly what the Flipper shows. */
    fun preview(bits: ByteArray): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val on = (bits[y * ROW_BYTES + x / 8].toInt() shr (x % 8)) and 1 == 1
            bmp.setPixel(x, y, if (on) Color.BLACK else Color.rgb(255, 138, 31)) // Flipper orange
        }
        return bmp
    }
}
