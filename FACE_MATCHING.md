# Local face matching

The app now includes a first-pass, on-device face embedding comparison screen.

## Current workflow

1. Open **Match my face in selected photos**.
2. Import a compatible TensorFlow Lite face-embedding model.
3. Choose one clear reference photo.
4. Choose candidate images on the phone.
5. Run comparison. Images are decoded and compared locally; the app does not upload them.

## Model requirement

The app intentionally does not bundle third-party model weights. A model's weights have separate licensing and redistribution terms, and a model should not be copied into this project until those terms and provenance are verified.

The current importer accepts a float32 TFLite model with:
- Input tensor: `[1, 112, 112, 3]`, RGB, float values normalized to `[-1, 1]`.
- Output tensor: `[1, N]`, float embedding.

The current cropper uses a square crop around the detected face bounding box. It does not perform five-point landmark alignment. Some MobileFaceNet exports require aligned crops or different preprocessing; those models may produce poor scores even if they load successfully. Use a model whose documented preprocessing exactly matches this implementation, or update the preprocessor to match the model.

## Results and limitations

- The initial 0.60 cosine-similarity cutoff is a placeholder, not a validated identity threshold. Calibrate it against genuine and non-matching photos for the exact model.
- Lighting, pose, image quality, age differences, occlusion, and crop alignment can affect results.
- A possible match is only a lead for manual review, never proof of identity.
- The current face-matching screen compares images selected from the phone. It does not crawl websites, download web results, or automatically feed search-engine results into the matcher.
- The three free web search buttons still open browser search pages. Their results are not automatically collected by the app.
- The reference embedding exists only for the current scan session; the app does not save a biometric template.

## Model licensing

Before importing a model, check the model-weight license, training-data provenance, allowed use, and the model's own preprocessing documentation. The MobileFaceNet architecture paper does not grant rights to arbitrary pretrained weight files.
