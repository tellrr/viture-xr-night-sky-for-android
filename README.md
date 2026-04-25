# VITURE XR Night Sky for Android

This project ports the Windows VITURE XR night-sky viewer into a native Android phone app.

The Android app:

- renders a fullscreen immersive panorama sphere with the same reference-cube validation mode
- prefers live pose orientation from the VITURE XR Glasses SDK 2.2.1 native libraries for Luma Ultra
- supports low-latency recentering
- falls back to touch-drag look controls when live pose data is unavailable
- opens a dedicated immersive presentation on a connected external display when Android exposes one
- loads an override panorama from app storage, with the bundled `current_panorama.jpg` and a procedural sky as fallback

## Project Layout

```text
.
|-- app
|   |-- build.gradle
|   |-- libs
|   |   `-- VITURE-SDK-1.0.7.aar
|   `-- src/main
|       |-- AndroidManifest.xml
|       |-- assets/panoramas/current_panorama.jpg
|       |-- cpp
|       |   `-- viture_native_tracking.cpp
|       |-- java/com/viture/nightsky
|       |-- jniLibs
|       |   |-- arm64-v8a
|       |   `-- armeabi-v7a
|       `-- res
|-- gradle
|   `-- wrapper
|-- third_party
|   `-- viture/android/sdk-2.2.1
|-- VITURE_XR_Glasses_SDK_for_Android
|   `-- release
`-- README.md
```

## Requirements

- Android Studio with the Android SDK installed
- Android SDK path available at `C:\Users\rr\AppData\Local\Android\Sdk`, or a matching `local.properties`
- Android NDK/CMake installed through Android Studio for the JNI bridge
- A phone that supports the VITURE Android SDK path if you want live glasses tracking

## Build

If `local.properties` does not already exist, create it with:

```properties
sdk.dir=C:\\Users\\rr\\AppData\\Local\\Android\\Sdk
```

Then build the debug APK:

```powershell
.\gradlew.bat assembleDebug
```

The APK will be written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Install the debug APK on the Android phone.
2. Connect the VITURE glasses if you want live pose tracking and external-display output.
3. Launch **VITURE Night Sky**.

Runtime behavior:

- if Android exposes a presentation display for the glasses, the app opens a clean immersive scene on it
- the phone keeps the same scene plus the control HUD
- `Image Folder` lets you select a phone folder containing panoramas; swipe left/right on the phone to move between images
- `Recenter` resets the current forward direction
- `Panorama / Reference Cube` toggles between the sky sphere and the validation cube
- when tracking is unavailable, dragging on the phone screen rotates the camera manually

## Troubleshooting

The HUD now reports renderer and tracking state separately:

- `Renderer: Ready.` means OpenGL finished loading the scene.
- `Renderer: OpenGL error: ...` means the GL setup failed before drawing.
- `Tracking: Waiting for VITURE glasses connection. init=-1` means the VITURE SDK did not see the USB device yet.
- `Tracking: USB permission is required for VITURE glasses. init=-2` means Android still needs the USB permission dialog accepted.
- `Tracking: VITURE SDK 2.2.1 started. Waiting for pose.` means the native Luma Ultra SDK opened the USB device and pose polling has started.
- `Tracking: Receiving live pose data from VITURE SDK ...` means the renderer is using live glasses pose.
- `Tracking: Connected to VITURE runtime...` means the SDK initialized and the app is waiting for IMU packets.
- `Tracking: Receiving live pose data from VITURE glasses.` means head tracking packets are reaching the renderer.

## Panorama Override

The bundled panorama is:

```text
app/src/main/assets/panoramas/current_panorama.jpg
```

To replace the panorama inside the APK, overwrite that file with the new `current_panorama.jpg` and rebuild.

To use a folder on the phone without rebuilding, tap `Image Folder`, choose the folder, then swipe left/right on the phone screen to select the previous/next supported image.

At runtime, the app searches these override locations first:

- `Android/data/com.viture.nightsky/files/panoramas/current_panorama.jpg`
- the same path with `.jpeg`, `.png`, or `.webp`
- the app's internal `files/panoramas/` directory

If no override exists, it loads the bundled `app/src/main/assets/panoramas/current_panorama.jpg`. If that cannot be decoded, it falls back to a procedural night sky.

## Notes

- The app stays monoscopic like the Windows build. It does not force VITURE 3D SBS mode.
- The VITURE XR Glasses SDK 2.2.1 native libraries are vendored locally for Luma Ultra (`35ca:1104`).
- `VITURE-SDK-1.0.7.aar` remains in the app only as a compatibility fallback for older VITURE devices.
- Touch fallback is intentionally preserved so the scene remains usable even without a connected headset.
