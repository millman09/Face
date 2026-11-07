package com.example.clarity

/**
 * Every adjustment the editor can apply. The slider values use the same units
 * the sliders show (0..100 or -100..100) so they can be copied straight to and
 * from the screen. The `auto*` values are set only by Auto Fix: they hold the
 * levels and white-balance corrections it measured from the video itself.
 */
data class EnhanceSettings(
    // Clean up
    val denoise: Float = 0f,       // 0..100
    val sharpen: Float = 0f,       // 0..100
    // Light
    val exposure: Float = 0f,      // -100..100
    val contrast: Float = 0f,      // -100..100
    val shadows: Float = 0f,       // -100..100
    val highlights: Float = 0f,    // -100..100
    // Color
    val saturation: Float = 0f,    // -100..100
    val vibrance: Float = 0f,      // -100..100
    val warmth: Float = 0f,        // -100..100
    val tint: Float = 0f,          // -100..100
    // Measured by Auto Fix
    val autoBlack: Float = 0f,     // 0..1, input level mapped to black
    val autoWhite: Float = 1f,     // 0..1, input level mapped to white
    val autoGamma: Float = 1f,     // < 1 brightens mid-tones, > 1 darkens them
    val autoGainR: Float = 1f,
    val autoGainG: Float = 1f,
    val autoGainB: Float = 1f,
) {
    /** Per-channel multipliers combining Auto Fix's white balance with Warmth and Tint. */
    fun channelGains(): FloatArray {
        val w = warmth / 100f
        val t = tint / 100f
        return floatArrayOf(
            autoGainR * (1f + 0.15f * w),
            autoGainG * (1f - 0.10f * t),
            autoGainB * (1f - 0.15f * w),
        )
    }

    val isOriginal: Boolean get() = this == EnhanceSettings()

    companion object {
        /** One-tap looks offered next to Auto Fix. */
        val LOW_LIGHT = EnhanceSettings(
            denoise = 60f, sharpen = 20f, exposure = 35f, shadows = 45f,
            highlights = -10f, vibrance = 15f
        )
        val VIVID = EnhanceSettings(
            sharpen = 30f, contrast = 20f, saturation = 15f, vibrance = 35f, highlights = -15f
        )
        val CLEAN = EnhanceSettings(denoise = 70f, sharpen = 35f)
        val CINEMATIC = EnhanceSettings(
            contrast = 15f, shadows = -10f, highlights = -25f, saturation = -15f, warmth = 15f,
            sharpen = 15f
        )
    }
}
