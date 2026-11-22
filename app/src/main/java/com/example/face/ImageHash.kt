package com.example.face

import android.graphics.Bitmap
import android.graphics.Color

/**
 * A 64-bit "difference hash" of a picture: it stays nearly the same when a photo is resized, recompressed
 * or lightly re-edited, so reposts of one photo on different sites hash within a few bits of each other.
 */
object ImageHash {
    /** Hashes this close or closer are treated as the same photo. */
    const val SAME_IMAGE_DISTANCE = 6

    /** Null if the picture can't be read (for example a recycled bitmap); such a card just isn't grouped. */
    fun dHash(source: Bitmap): Long? = try {
        val readable = if (android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else source
        try { hashOf(readable) } finally { if (readable !== source) readable.recycle() }
    } catch (_: Exception) {
        null
    }

    private fun hashOf(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
                    bit++
                }
            }
            return hash
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luma(color: Int): Int = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
}
