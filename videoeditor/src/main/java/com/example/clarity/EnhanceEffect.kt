package com.example.clarity

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * A GPU effect that cleans up and enhances every frame: edge-preserving noise
 * reduction, sharpening, levels, white balance, exposure, shadows/highlights,
 * contrast, saturation and vibrance in a single pass.
 *
 * The same effect drives the live preview and the export. [settings] is read
 * on every frame, so the preview follows the sliders without rebuilding the
 * player.
 */
@OptIn(UnstableApi::class)
class EnhanceEffect(private val settings: () -> EnhanceSettings) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            EnhanceShaderProgram(settings, useHdr)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
}

@OptIn(UnstableApi::class)
private class EnhanceShaderProgram(
    private val settings: () -> EnhanceSettings,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }
    private var texelSize = floatArrayOf(0f, 0f)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelSize = floatArrayOf(1f / inputWidth, 1f / inputHeight)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = settings()
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatsUniform("uTexelSize", texelSize)
            program.setFloatUniform("uDenoise", s.denoise / 100f)
            program.setFloatUniform("uSharpen", s.sharpen / 100f * 1.6f)
            program.setFloatsUniform("uGain", s.channelGains())
            program.setFloatUniform("uBlack", s.autoBlack)
            program.setFloatUniform("uWhite", s.autoWhite)
            program.setFloatUniform("uGamma", s.autoGamma)
            program.setFloatUniform("uExposure", s.exposure / 100f)
            program.setFloatUniform("uShadows", s.shadows / 100f)
            program.setFloatUniform("uHighlights", s.highlights / 100f)
            program.setFloatUniform("uContrast", 1f + s.contrast / 200f)
            program.setFloatUniform("uSaturation", 1f + s.saturation / 100f)
            program.setFloatUniform("uVibrance", s.vibrance / 100f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        const val FRAGMENT_SHADER = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uDenoise;
uniform float uSharpen;
uniform vec3 uGain;
uniform float uBlack;
uniform float uWhite;
uniform float uGamma;
uniform float uExposure;
uniform float uShadows;
uniform float uHighlights;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
  vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = center.rgb;

  // 5x5 edge-preserving (bilateral) smoothing for noise, plus a 3x3 Gaussian
  // blur of the source used as the base for unsharp-mask sharpening.
  float rangeSigma = mix(0.03, 0.14, uDenoise);
  float rangeInv = 1.0 / (2.0 * rangeSigma * rangeSigma);
  vec3 smoothSum = c;
  float smoothWeight = 1.0;
  vec3 blurSum = c * 4.0;
  float blurWeight = 4.0;
  for (int i = -2; i <= 2; i++) {
    for (int j = -2; j <= 2; j++) {
      vec2 offset = vec2(float(i), float(j));
      float dist2 = dot(offset, offset);
      if (dist2 > 0.0) {
        vec3 s = texture2D(uTexSampler, vTexSamplingCoord + offset * uTexelSize).rgb;
        vec3 diff = s - c;
        float w = exp(-dist2 / 4.5 - dot(diff, diff) * rangeInv);
        smoothSum += s * w;
        smoothWeight += w;
        if (dist2 <= 2.0) {
          float g = dist2 < 1.5 ? 2.0 : 1.0;
          blurSum += s * g;
          blurWeight += g;
        }
      }
    }
  }
  c = mix(c, smoothSum / smoothWeight, uDenoise);

  // Sharpen luminance only, and cap the boost, so edges get crisper without
  // color fringes or bright halos.
  float detail = clamp(luma(c - blurSum / blurWeight), -0.08, 0.08);
  c += uSharpen * detail;

  // White balance, then levels, then mid-tone gamma (all measured by Auto Fix).
  c = max(c * uGain, 0.0);
  c = clamp((c - uBlack) / max(uWhite - uBlack, 0.05), 0.0, 1.0);
  c = pow(c, vec3(uGamma));

  // Exposure in stops.
  c *= exp2(uExposure);

  // Shadows and highlights work on luminance so colors don't shift.
  float l = luma(c);
  float shadowMask = 1.0 - smoothstep(0.0, 0.5, l);
  float highlightMask = smoothstep(0.5, 1.0, l);
  float target = l + uShadows * 0.35 * shadowMask * (1.0 - l)
                   + uHighlights * 0.35 * highlightMask * l;
  c += vec3(target - l);

  // Contrast around mid-gray.
  c = (c - 0.5) * uContrast + 0.5;

  // Saturation, and vibrance that favours muted colors over already-strong ones.
  c = max(c, 0.0);
  l = luma(c);
  float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
  float amount = uSaturation + uVibrance * (1.0 - clamp(sat, 0.0, 1.0));
  c = mix(vec3(l), c, max(amount, 0.0));

  gl_FragColor = vec4(clamp(c, 0.0, 1.0), center.a);
}
"""
    }
}
