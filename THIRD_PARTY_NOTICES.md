# Third-party notices

## MobileFaceNet

The app can download the MobileFaceNet TensorFlow Lite model archive from Qualcomm AI Hub at runtime.

- Model repository: https://huggingface.co/qualcomm/MobileFaceNet
- Model card: https://aihub.qualcomm.com/models/mobile_facenet
- Upstream implementation: https://github.com/foamliu/MobileFaceNet
- Upstream model license: Apache License 2.0
- Qualcomm AI Hub model recipe source identifies its source files as BSD-3-Clause.

The app does not commit the model weights into this repository. The model is downloaded directly from Qualcomm's published model asset host after the user taps the download button. The model file is stored in the app's private internal storage and used locally for face verification.

Build configuration targets stable Android API 36 to avoid requiring a preview SDK in continuous integration.

## Username site list

The username-checker's site list (assets/wmn-sites.json) is combined from two open-source projects, which also supplied the detection rules (the expected response for a "found" vs. "not found" profile page):

- WhatsMyName (https://github.com/WebBreacher/WhatsMyName) by Micah Hoffman — CC BY-SA 4.0. Full license: assets/wmn-LICENSE.md.
- Sherlock Project (https://github.com/sherlock-project/sherlock) — MIT License. Full license: assets/sherlock-LICENSE.md.

Both projects' site lists are bundled as static data; the app does not call out to either project's own code or servers.
