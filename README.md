# Safe Vision

Safe Vision is an Android app for on-device sensitive-content detection and privacy masking. It runs YOLO-based ONNX models locally, supports both image and video workflows, and provides a configurable masking pipeline for personal media protection.

## Features

- Detect sensitive content in images and videos with local ONNX inference
- Switch between a standard detection model and an anime-oriented detection model
- Apply multiple privacy effects, including mosaic, solid black, Gaussian blur, sketch, and stickers
- Configure masking behavior per label, including per-label effects and scaling
- Process images in batches with unified progress feedback
- Process videos through a foreground service with queueing and progress notifications
- Run real-time screen detection with an accessibility overlay for privacy masking
- Browse, edit, export, share, and manage processed media inside the app
- Save and switch privacy presets for different masking strategies
- Support multiple languages, including Simplified Chinese, Traditional Chinese, English, and Korean

## Technical Structure

The project is a single Android application module built with Kotlin and Gradle Kotlin DSL.

- UI layer: `MainActivity` plus multiple `Fragment`-based screens for processing, gallery, viewer, and settings
- Inference layer: `YoloOnnxRunner`, `YoloModelProvider`, and model-variant support for standard and anime detection
- Media pipeline: image processing, video decoding/encoding, batch execution, and result persistence
- Rendering layer: shared masking and overlay rendering through `DetectionRenderEngine` and related privacy-processing classes
- Service layer: foreground services for batch processing, video processing, and screen detection
- Settings and storage: dedicated managers for privacy settings, app settings, themes, logs, crash handling, and error reports

Key dependencies currently include:

- AndroidX
- Material Components
- Kotlin Coroutines
- ONNX Runtime for Android

## Development

### Requirements

- Android Studio with Android SDK support
- JDK 17
- Android `minSdk 24`
- Android `targetSdk / compileSdk 36`

### Project Layout

```text
SafeVision/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/safe/vision/
│       ├── res/
│       └── AndroidManifest.xml
├── assets/
│   ├── 320n.onnx
│   ├── 320n-anime.onnx
│   ├── 320n-anime.onnx.data
│   └── Default-stickers.png
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

### Build and Run

1. Open the project in Android Studio.
2. Sync Gradle dependencies.
3. Make sure the model files under `assets/` are present.
4. Build and run the `app` module on an Android device or emulator.

You can also build from the command line:

```bash
./gradlew assembleDebug
```

### Development Notes

- The app uses local model assets instead of network inference.
- Video and screen-detection flows rely on Android foreground services.
- Privacy behavior is heavily configuration-driven through the settings managers and label-level options.
- Processed outputs, logs, and reports are stored in the app-specific file area.

## Acknowledgements

This project uses a detection model pipeline derived from the NudeNet repository. Thanks to the NudeNet authors and contributors for making their work publicly available:

https://github.com/notAI-tech/NudeNet
