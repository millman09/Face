package com.example.clarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AutoFixTest {

    private fun frame(w: Int = 64, h: Int = 48, noise: Int = 0, color: (x: Int, y: Int) -> Triple<Int, Int, Int>): Frame {
        val rnd = Random(42)
        val px = IntArray(w * h) { i ->
            val (r, g, b) = color(i % w, i / w)
            fun n(v: Int) = (v + if (noise > 0) rnd.nextInt(noise * 2 + 1) - noise else 0).coerceIn(0, 255)
            (0xFF shl 24) or (n(r) shl 16) or (n(g) shl 8) or n(b)
        }
        return Frame(px, w, h)
    }

    /** A clean gradient from black to white with gentle colour, like a well-exposed shot. */
    private fun goodShot(x: Int, y: Int): Triple<Int, Int, Int> {
        val v = (x * 255 / 63)
        val c = ((y * 4) % 60) - 30
        return Triple((v + c).coerceIn(0, 255), v, (v - c).coerceIn(0, 255))
    }

    @Test
    fun darkVideoIsBrightened() {
        val dark = frame { x, y -> goodShot(x, y).let { (r, g, b) -> Triple(r / 4, g / 4, b / 4) } }
        val result = AutoFix.analyze(listOf(dark))
        assertTrue("gamma should brighten", result.settings.autoGamma < 1f)
        assertTrue(result.notes.any { it.contains("Brightened") })
    }

    @Test
    fun blueCastIsNeutralised() {
        val blue = frame { x, _ -> val v = 60 + x * 2; Triple(v - 30, v, v + 40) }
        val s = AutoFix.analyze(listOf(blue)).settings
        assertTrue(s.autoGainB < 1f)
        assertTrue(s.autoGainR > 1f)
    }

    @Test
    fun washedOutBlacksAreStretched() {
        val flat = frame { x, _ -> val v = 70 + x * 2; Triple(v, v, v) }
        val s = AutoFix.analyze(listOf(flat)).settings
        assertTrue(s.autoBlack > 0.2f || s.autoBlack == 0.12f)
        assertTrue(s.autoWhite < 1f)
    }

    @Test
    fun grainyVideoGetsDenoised() {
        val clean = AutoFix.analyze(listOf(frame { x, y -> goodShot(x, y) })).settings
        val grainy = AutoFix.analyze(listOf(frame(noise = 25) { x, y -> goodShot(x, y) })).settings
        assertEquals(0f, clean.denoise, 0.01f)
        assertTrue("denoise was ${grainy.denoise}", grainy.denoise >= 40f)
    }

    @Test
    fun neutralGrayHasNoColourCorrection() {
        val gray = frame { x, y -> val v = 30 + (x + y) * 2; Triple(v, v, v) }
        val s = AutoFix.analyze(listOf(gray)).settings
        assertEquals(1f, s.autoGainR, 0.001f)
        assertEquals(1f, s.autoGainG, 0.001f)
        assertEquals(1f, s.autoGainB, 0.001f)
    }
}
