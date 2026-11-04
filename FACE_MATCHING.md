# Local face matching

The app includes on-device face verification using Qualcomm AI Hub's MobileFaceNet TFLite release.

## First-time setup

1. Open **Match my face in selected photos**.
2. Tap **Download official MobileFaceNet model**. The app downloads the public Qualcomm TFLite archive over HTTPS, extracts the model into private app storage, and validates its tensor contract before enabling matching.
3. Choose one clear reference photo.
4. Choose candidate images on the phone, or share image files to the app from another Android app.
5. Run comparison. All face detection, alignment, and model inference happen locally.

The model archive is hosted by Qualcomm AI Hub:
- Model card: https://huggingface.co/qualcomm/MobileFaceNet
- TFLite float archive: https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.65.0/mobile_facenet-tflite-float.zip
- Model weights are identified as Apache-2.0 in the model repository. The app downloads the archive at runtime rather than committing model weights to this source repository.

## Model contract and preprocessing

This Qualcomm export is a pairwise verifier, not a single-image embedding model. It expects two float32 RGB images at 112 x 112, each in the 0..1 range. The exported model applies torchvision mean/std normalization internally and returns two 128-value embeddings (256 float values total). The app checks the input count, shapes, types, and output size before using the model. It supports either NHWC or NCHW tensor layouts.

Qualcomm's reference application aligns five facial landmarks to a canonical 112 x 112 crop. The Android app uses ML Kit's left eye, right eye, nose base, left mouth, and right mouth landmarks and a 2D similarity transform to create the aligned crop. Photos missing any required landmark are skipped with an explanation.

## Scores and limitations

- Qualcomm's published evaluator uses a 74.18-degree cosine-angle threshold on its LFW benchmark. The app uses this as an initial reference threshold, not a guarantee for your photos or other image sources.
- Lighting, pose, image quality, age differences, occlusion, facial expression, and landmark accuracy can affect results.
- A possible match is a lead for manual review, never proof of identity.
- The matcher compares images selected or shared into the phone. It does not crawl websites, download browser results, or automatically scan search engines.
- The three free web-search buttons open browser search pages; the app does not automatically collect those results.
- The reference image and candidate images are processed locally. The app does not upload them to Qualcomm or another matching service.
- The reference face is not saved as a biometric template after the scan session.

