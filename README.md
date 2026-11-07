Android app project

This repository holds two Android apps:

- `app/` – **Face**, the privacy and takedown helper.
- `videoeditor/` – **Clarity**, a video editor that cleans up and enhances footage.

## Clarity video editor

Open a video (or share one to Clarity from your gallery) and:

- **Auto fix** – studies frames from the video and corrects brightness, washed-out
  blacks, color casts, flat contrast, faded color, grain and softness in one tap.
  A card lists what it changed.
- **Quick looks** – Clean up, Low light, Vivid and Cinematic presets.
- **Fine-tune** – Denoise, Sharpen, Exposure, Contrast, Shadows, Highlights,
  Saturation, Vibrance, Warmth and Tint.
- **Hold to compare** – shows the original while you hold the button.
- **Trim**, **remove sound**, and **enhance resolution** (upscales sub-HD video to
  1080p with a crispening pass).
- **Export** – renders on the GPU with Media3 Transformer at a high bitrate and
  saves to *Movies › Clarity*, ready to share.

The live preview uses the same GPU effect as the export, so what you see is what
you get. Build it with `./gradlew :videoeditor:assembleDebug`.
