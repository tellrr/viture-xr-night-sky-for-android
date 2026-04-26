# VITURE PanoView for Android

This project ports the Windows VITURE XR night-sky viewer into a native Android phone app.

The Android app:

- renders a fullscreen immersive panorama sphere with the same reference-cube validation mode
- prefers live pose orientation from the VITURE XR Glasses SDK 2.2.1 native libraries for Luma Ultra
- supports low-latency recentering
- falls back to touch-drag look controls when live pose data is unavailable
- opens a dedicated immersive presentation on a connected external display when Android exposes one
- loads an override panorama from app storage, with the bundled `current_panorama.jpg` and a procedural sky as fallback
- includes an experimental ARCore floor-tracking mode that uses phone movement as approximate 5DoF translation in the reference grid scene

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
- Google Play Services for AR / ARCore if you want the experimental floor-tracking mode

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
3. Launch **VITURE PanoView**.

Runtime behavior:

- if Android exposes a presentation display for the glasses, the app opens a clean immersive scene on it
- the phone keeps the same scene plus the control HUD
- the gear button opens settings, including the remembered image-folder picker
- swipe left/right on the phone to move between images in the selected folder
- swipe up/down on the phone to zoom in/out; zoom is clamped from `0.5x` to `5x`
- `Recenter` resets the current forward direction
- `Panorama / Reference Cube` toggles between the sky sphere and the validation cube
- when tracking is unavailable, dragging on the phone screen rotates the camera manually
- in settings, `Enable Floor Tracking` starts the experimental ARCore translation tracker; use `Reference Cube` to see movement in the larger grid test room
- when floor tracking is enabled, the top `Move` slider scales virtual movement from `1x` to `10x`; `1x` is the raw/current ARCore movement

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
- `Floor: ARCore floor tracking...` means the phone camera/IMU pose is feeding approximate horizontal translation.
- `Floor: ... paused` usually means ARCore does not yet see enough trackable visual features; keep the rear camera pointed at a textured floor and move slowly.

## Experimental Floor Tracking

This mode tries to add body translation on top of the glasses' 3DoF orientation:

1. Hold the phone roughly horizontal with the screen up and rear camera facing the floor.
2. Tap the gear, choose `Enable Floor Tracking`, and grant camera permission.
3. Toggle to `Reference Cube`; it is now a larger grid test room.
4. Move slowly over a textured floor or carpet.
5. Use `Recenter` or settings `Reset Floor Origin` to zero the translation again.
6. Use the top `Move` slider if the grid movement is too subtle; it scales both the virtual position and the orange floor trail.

This is not true headset 6DoF. ARCore tracks the phone, not the glasses, so it approximates body movement only when the phone moves with the user. The panorama scene remains effectively monoscopic/infinite-distance; the reference grid scene is the intended test surface for translation.

## Panorama Override

The bundled panorama is:

```text
app/src/main/assets/panoramas/current_panorama.jpg
```

To replace the panorama inside the APK, overwrite that file with the new `current_panorama.jpg` and rebuild.

To use a folder on the phone without rebuilding, tap the gear button, choose `Image Folder`, then swipe left/right on the phone screen to select the previous/next supported image. The selected folder and image index are remembered across app launches when Android grants persistable folder access.

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
