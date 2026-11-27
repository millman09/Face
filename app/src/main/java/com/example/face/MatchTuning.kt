package com.example.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/** Learns a match threshold from the user's "That's me" / "Not a match" answers, and small face helpers. */
object MatchTuning {
    private const val PREFS = "match_tuning"
    private const val DEFAULT_THRESHOLD = 0.4f
    private const val KEEP = 30

    private fun read(context: Context, key: String): List<Float> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()
            .split(',').mapNotNull { it.toFloatOrNull() }

    /** Forgets past "That's me" / "Not a match" scores; used when the face model changes, since its scores differ. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Remembers the score of a result the user confirmed or rejected. */
    fun record(context: Context, confirmed: Boolean, similarity: Float) {
        val key = if (confirmed) "confirmed" else "rejected"
        val updated = (read(context, key) + similarity).takeLast(KEEP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, updated.joinToString(",")).apply()
    }

    /**
     * Confirmed matches pull the threshold down so near misses are kept; with no confirmed matches,
     * many rejections push it up a little. Always between 0.35 and 0.5.
     */
    fun threshold(context: Context): Float {
        val confirmed = read(context, "confirmed")
        val rejected = read(context, "rejected").sorted()
        if (confirmed.size >= 3) return (confirmed.min() - 0.05f).coerceIn(0.35f, 0.5f)
        if (rejected.size >= 5) return rejected[rejected.size / 2].coerceIn(DEFAULT_THRESHOLD, 0.5f)
        return DEFAULT_THRESHOLD
    }

    /** Face box as fractions of the image, so it survives resizing: "left,top,right,bottom". */
    fun formatBox(face: Face, width: Int, height: Int): String {
        val b = face.boundingBox
        fun f(v: Int, size: Int) = (v.toFloat() / size).coerceIn(0f, 1f)
        return listOf(f(b.left, width), f(b.top, height), f(b.right, width), f(b.bottom, height))
            .joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
    }

    private fun parseBox(box: String?): FloatArray? {
        val parts = box?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if (parts.size == 4 && parts[2] > parts[0] && parts[3] > parts[1]) parts.toFloatArray() else null
    }

    /** Copy of [preview] with a rectangle around the matched face. */
    fun highlight(preview: Bitmap, box: String?): Bitmap {
        val b = parseBox(box) ?: return preview
        val copy = preview.copy(Bitmap.Config.ARGB_8888, true) ?: return preview
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (copy.width / 60f).coerceAtLeast(2f)
            color = Color.rgb(155, 139, 255)
        }
        Canvas(copy).drawRoundRect(
            RectF(b[0] * copy.width, b[1] * copy.height, b[2] * copy.width, b[3] * copy.height), 6f, 6f, paint
        )
        return copy
    }

    /** Square-ish crop of the matched face with a little margin, or null when there is no box. */
    fun cropFace(source: Bitmap, box: String?): Bitmap? {
        val b = parseBox(box) ?: return null
        val w = source.width
        val h = source.height
        val marginX = (b[2] - b[0]) * 0.2f
        val marginY = (b[3] - b[1]) * 0.2f
        val left = ((b[0] - marginX) * w).toInt().coerceIn(0, w - 1)
        val top = ((b[1] - marginY) * h).toInt().coerceIn(0, h - 1)
        val right = ((b[2] + marginX) * w).toInt().coerceIn(left + 1, w)
        val bottom = ((b[3] + marginY) * h).toInt().coerceIn(top + 1, h)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val scale = 128f / maxOf(crop.width, crop.height)
        return if (scale >= 1f) crop else Bitmap.createScaledBitmap(
            crop, (crop.width * scale).toInt().coerceAtLeast(1), (crop.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    /** One short sentence about what is wrong with a reference photo, or null when it looks fine. */
    fun photoHint(bitmap: Bitmap, face: Face, number: Int): String? {
        val box = face.boundingBox
        if (box.width() < 100 || box.height() < 100) return "Photo $number: your face is small in the frame. Use a closer shot."
        if (abs(face.headEulerAngleY) > 25f) return "Photo $number: your head is turned. A mostly front-facing photo works best."
        if (sharpness(bitmap, face) < 25.0) return "Photo $number: looks blurry. Use a sharper, well-lit photo."
        return null
    }

    /** Variance of a Laplacian over the face area: low values mean a blurry image. */
    private fun sharpness(bitmap: Bitmap, face: Face): Double {
        val b = face.boundingBox
        val left = b.left.coerceIn(0, bitmap.width - 1)
        val top = b.top.coerceIn(0, bitmap.height - 1)
        val width = b.width().coerceIn(1, bitmap.width - left)
        val height = b.height().coerceIn(1, bitmap.height - top)
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        val small = Bitmap.createScaledBitmap(crop, 64, 64, true)
        val px = IntArray(64 * 64)
        small.getPixels(px, 0, 64, 0, 0, 64, 64)
        if (crop !== bitmap && crop !== small) crop.recycle()
        small.recycle()
        val gray = DoubleArray(px.size) { i ->
            0.299 * Color.red(px[i]) + 0.587 * Color.green(px[i]) + 0.114 * Color.blue(px[i])
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until 63) for (x in 1 until 63) {
            val i = y * 64 + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - 64] + gray[i + 64] - 4 * gray[i]
            sum += lap; sumSq += lap * lap; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
