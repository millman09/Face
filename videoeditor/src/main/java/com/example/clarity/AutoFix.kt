package com.example.clarity

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** One still taken from the video, as packed ARGB pixels (the format of Bitmap.getPixels). */
class Frame(val pixels: IntArray, val width: Int, val height: Int)

/** What Auto Fix chose and a short, plain list of what it changed. */
data class AutoFixResult(val settings: EnhanceSettings, val notes: List<String>)

/**
 * Looks at sample frames from a video and picks settings that fix the usual
 * problems with phone footage: too dark or too bright, washed-out blacks,
 * a color cast, flat contrast, dull color, grain and softness.
 *
 * This file has no Android dependencies so it can be unit tested on the JVM.
 */
object AutoFix {

    private const val TARGET_MID = 0.46f

    fun analyze(frames: List<Frame>): AutoFixResult {
        require(frames.isNotEmpty()) { "Need at least one frame" }

        val histogram = IntArray(256)
        var count = 0L
        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var satSum = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var midCount = 0L
        var darkCount = 0L
        var brightCount = 0L

        for (frame in frames) {
            for (p in frame.pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = luma(r, g, b)
                histogram[l]++
                count++
                lumaSum += l
                lumaSqSum += l.toDouble() * l
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                satSum += (max - min) / 255.0
                if (l < 38) darkCount++
                if (l > 242) brightCount++
                // Gray-world white balance ignores clipped and near-black pixels,
                // which carry no reliable color.
                if (l in 20..235) {
                    sumR += r; sumG += g; sumB += b; midCount++
                }
            }
        }

        val notes = mutableListOf<String>()

        // Levels: stretch the tones so the darkest and brightest parts use the full range.
        val lowPct = percentile(histogram, count, 0.005) / 255f
        val highPct = percentile(histogram, count, 0.995) / 255f
        val black = if (lowPct > 0.02f) lowPct.coerceAtMost(0.12f) else 0f
        val white = if (highPct < 0.96f) highPct.coerceAtLeast(0.75f) else 1f
        if (black > 0f) notes += "Deepened washed-out blacks"
        if (white < 1f) notes += "Restored dull whites"

        // Brightness: bend the mid-tones so the average lands near a natural mid-gray.
        val meanLuma = (lumaSum / count / 255.0).toFloat()
        val leveledMean = ((meanLuma - black) / (white - black)).coerceIn(0.02f, 0.98f)
        var gamma = 1f
        if (abs(leveledMean - TARGET_MID) > 0.04f) {
            gamma = (ln(TARGET_MID) / ln(leveledMean)).coerceIn(0.55f, 1.4f)
            notes += if (gamma < 1f) "Brightened a dark video" else "Toned down an over-bright video"
        }

        // White balance: neutral scenes average to gray, so scale channels toward that.
        var gainR = 1f
        var gainG = 1f
        var gainB = 1f
        if (midCount > 0) {
            val avgR = sumR / midCount
            val avgG = sumG / midCount
            val avgB = sumB / midCount
            val gray = (avgR + avgG + avgB) / 3.0
            val strength = 0.7f
            val rawR = (gray / avgR.coerceAtLeast(1.0)).toFloat().coerceIn(0.8f, 1.25f)
            val rawG = (gray / avgG.coerceAtLeast(1.0)).toFloat().coerceIn(0.8f, 1.25f)
            val rawB = (gray / avgB.coerceAtLeast(1.0)).toFloat().coerceIn(0.8f, 1.25f)
            val cast = maxOf(abs(rawR - 1f), abs(rawG - 1f), abs(rawB - 1f))
            if (cast > 0.04f) {
                gainR = 1f + strength * (rawR - 1f)
                gainG = 1f + strength * (rawG - 1f)
                gainB = 1f + strength * (rawB - 1f)
                notes += "Removed a ${castName(avgR, avgG, avgB)} color cast"
            }
        }

        // Shadows and highlights: open up crushed shadows, pull back blown skies.
        val darkShare = darkCount.toFloat() / count
        val brightShare = brightCount.toFloat() / count
        var shadows = 0f
        var highlights = 0f
        if (darkShare > 0.25f) {
            shadows = (darkShare * 80f).coerceIn(15f, 45f)
            notes += "Lifted detail out of the shadows"
        }
        if (brightShare > 0.04f) {
            highlights = -(brightShare * 300f).coerceIn(15f, 45f)
            notes += "Recovered bright highlights"
        }

        // Contrast: flat footage gets a gentle boost; harsh footage is softened a little.
        val variance = lumaSqSum / count - (lumaSum / count) * (lumaSum / count)
        val spread = (sqrt(variance.coerceAtLeast(0.0)) / 255.0).toFloat()
        val contrast = when {
            spread < 0.12f -> 25f
            spread < 0.17f -> 12f
            spread > 0.32f -> -10f
            else -> 0f
        }
        if (contrast > 0f) notes += "Added contrast to flat footage"

        // Color: dull footage gets vibrance, which boosts muted colors more than strong ones.
        val meanSat = (satSum / count).toFloat()
        val vibrance = when {
            meanSat < 0.10f -> 40f
            meanSat < 0.20f -> 25f
            meanSat < 0.30f -> 12f
            else -> 0f
        }
        if (vibrance >= 25f) notes += "Brought back faded colors"

        // Noise and sharpness, measured on the luma channel.
        var noiseSum = 0.0
        var edgeSum = 0.0
        for (frame in frames) {
            noiseSum += noiseSigma(frame)
            edgeSum += edgeStrength(frame)
        }
        val noise = (noiseSum / frames.size).toFloat()
        val edges = (edgeSum / frames.size).toFloat()

        var denoise = ((noise - 1.5f) / 5f).coerceIn(0f, 1f) * 80f
        // Brightening a dark video also brightens its grain.
        if (gamma < 0.85f) denoise += 15f
        denoise = denoise.coerceIn(0f, 85f)
        if (denoise >= 20f) {
            notes += if (denoise >= 55f) "Removed heavy grain and noise" else "Cleaned up grain and noise"
        }

        var sharpen = 25f
        if (edges < 4f) sharpen += 25f
        if (denoise > 50f) sharpen -= 10f
        sharpen = sharpen.coerceIn(0f, 100f)
        notes += if (sharpen >= 45f) "Sharpened a soft image" else "Added light sharpening"

        val settings = EnhanceSettings(
            denoise = denoise.round(),
            sharpen = sharpen.round(),
            contrast = contrast,
            shadows = shadows.round(),
            highlights = highlights.round(),
            vibrance = vibrance,
            autoBlack = black,
            autoWhite = white,
            autoGamma = gamma,
            autoGainR = gainR,
            autoGainG = gainG,
            autoGainB = gainB,
        )
        return AutoFixResult(settings, notes)
    }

    private fun luma(r: Int, g: Int, b: Int): Int = (r * 54 + g * 183 + b * 19) shr 8

    private fun percentile(histogram: IntArray, total: Long, fraction: Double): Int {
        val target = (total * fraction).toLong()
        var seen = 0L
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen > target) return i
        }
        return 255
    }

    private fun castName(r: Double, g: Double, b: Double): String = when {
        b > r && b > g -> "blue"
        r > g && r > b -> if (g > b) "orange" else "red"
        g > r && g > b -> "green"
        else -> "color"
    }

    private fun lumaPlane(frame: Frame): IntArray = IntArray(frame.pixels.size) { i ->
        val p = frame.pixels[i]
        luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    /**
     * Immerkær's fast noise estimate: a kernel that cancels smooth gradients and
     * edges leaves mostly noise; its mean absolute response scales to sigma
     * (in 0..255 units).
     */
    private fun noiseSigma(frame: Frame): Double {
        val w = frame.width
        val h = frame.height
        if (w < 3 || h < 3) return 0.0
        val y = lumaPlane(frame)
        var sum = 0.0
        for (row in 1 until h - 1) {
            for (col in 1 until w - 1) {
                val i = row * w + col
                val v = y[i - w - 1] - 2 * y[i - w] + y[i - w + 1] -
                    2 * y[i - 1] + 4 * y[i] - 2 * y[i + 1] +
                    y[i + w - 1] - 2 * y[i + w] + y[i + w + 1]
                sum += abs(v)
            }
        }
        return sqrt(Math.PI / 2) * sum / (6.0 * (w - 2) * (h - 2))
    }

    /** Mean gradient magnitude: low values mean a soft, blurry picture. */
    private fun edgeStrength(frame: Frame): Double {
        val w = frame.width
        val h = frame.height
        if (w < 2 || h < 2) return 0.0
        val y = lumaPlane(frame)
        var sum = 0.0
        for (row in 0 until h - 1) {
            for (col in 0 until w - 1) {
                val i = row * w + col
                sum += abs(y[i + 1] - y[i]) + abs(y[i + w] - y[i])
            }
        }
        return sum / ((w - 1).toDouble() * (h - 1))
    }

    private fun Float.round(): Float = Math.round(this).toFloat()
}
