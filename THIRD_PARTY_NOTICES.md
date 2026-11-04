# Third-party notices

## MobileFaceNet

The app can download the MobileFaceNet TensorFlow Lite model archive from Qualcomm AI Hub at runtime.

- Model repository: https://huggingface.co/qualcomm/MobileFaceNet
- Model card: https://aihub.qualcomm.com/models/mobile_facenet
- Upstream implementation: https://github.com/foamliu/MobileFaceNet
- Upstream model license: Apache License 2.0
- Qualcomm AI Hub model recipe source identifies its source files as BSD-3-Clause.

The app does not commit the model weights into this repository. The model is downloaded directly from Qualcomm's published model asset host after the user taps the download button. The model file is stored in the app's private internal storage and used locally for face verification.
