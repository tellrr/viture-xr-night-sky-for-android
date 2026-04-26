# VITURE PanoView Android Project Notes

This project is a native Android port of a Windows VITURE XR panorama/night-sky viewer. It targets Android phones connected to VITURE XR glasses, especially VITURE Luma Ultra (`USB 35ca:1104`). The app renders an immersive monoscopic panorama sphere, supports VITURE 3DoF head tracking, and includes experimental ARCore phone-floor tracking to approximate body translation.

## Core Behavior

- App name: `VITURE PanoView`.
- Phone activity is portrait.
- If Android exposes the glasses as a presentation display, `NightSkyPresentation` renders the immersive scene on that external display.
- The phone shows the same GL scene with HUD, controls, settings gear, and optional floor-tracking movement slider.
- `Panorama / Reference Cube` toggles between panorama sphere and a large grid cube/test room.
- `Recenter` resets VITURE orientation and ARCore floor origin/trail.
- Swipe left/right selects previous/next panorama from the chosen image folder.
- Swipe up/down controls zoom from `0.5x` to `5x`.

## Important Files

- `app/src/main/java/com/viture/nightsky/MainActivity.kt`: phone UI, display selection, HUD, settings dialog, folder picker, floor-tracking enable/disable, movement multiplier slider.
- `app/src/main/java/com/viture/nightsky/display/NightSkyPresentation.kt`: external display rendering for glasses.
- `app/src/main/java/com/viture/nightsky/render/NightSkyGLSurfaceView.kt`: touch gestures and GL renderer setup.
- `app/src/main/java/com/viture/nightsky/render/NightSkyRenderer.kt`: OpenGL rendering, panorama/reference cube drawing, ARCore frame updates, floor trail rendering.
- `app/src/main/java/com/viture/nightsky/render/SkyAssets.kt`: procedural sky, reference cube texture/mesh, floor trail mesh.
- `app/src/main/java/com/viture/nightsky/scene/NightSkySceneController.kt`: shared scene state: orientation, zoom, floor position/trail, movement multiplier, statuses.
- `app/src/main/java/com/viture/nightsky/tracking/VitureTrackingManager.kt`: VITURE USB/native SDK tracking orchestration.
- `app/src/main/java/com/viture/nightsky/tracking/NativeVitureBridge.kt`: Kotlin JNI bridge.
- `app/src/main/cpp/viture_native_tracking.cpp`: native bridge into VITURE XR Glasses SDK 2.2.1.
- `app/src/main/java/com/viture/nightsky/tracking/ArFloorTrackingManager.kt`: experimental ARCore floor translation tracker.
- `app/src/main/java/com/viture/nightsky/util/PanoramaRepository.kt`: bundled panorama, app-storage overrides, selected folder persistence and image decoding.

## Native / Third-Party Dependencies

- VITURE XR Glasses SDK 2.2.1 native libraries are vendored under `third_party/viture/android/sdk-2.2.1` and copied to `app/src/main/jniLibs`.
- `app/libs/VITURE-SDK-1.0.7.aar` remains as compatibility fallback for older VITURE devices.
- ARCore is optional and used only for the experimental floor-tracking mode.
- DocumentFile is used for Android folder access and persisted URI permissions.

## Build

Use Android Studio JBR on this machine:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug --stacktrace
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Current Feature Notes

- Rendering is monoscopic. Stereo 360 images are not yet supported.
- VITURE glasses provide 3DoF orientation only.
- ARCore floor tracking uses phone camera/IMU pose and applies only horizontal X/Z translation to the reference cube scene. It tracks the phone, not the glasses, so it approximates body movement only when the phone moves with the user.
- Floor tracking is opt-in from settings and requires camera permission plus Google Play Services for AR.
- The top `Move` slider appears only when floor tracking is enabled and scales floor movement/trail from `1x` to `10x`; `1x` is raw ARCore movement.
- The panorama sphere is effectively infinite-distance, so floor translation is most useful in the reference cube/grid test scene.
- Android immersive mode is centralized in `hideSystemUi(window)` in `MainActivity.kt`; both activity and presentation call it.

## Development Cautions

- Do not break the native VITURE SDK path while changing UI/rendering code; head tracking currently works.
- The phone renderer is the only renderer that should call ARCore `Session.update()`. The glasses presentation consumes shared scene state but must not drive ARCore.
- Keep selected folder access via `ACTION_OPEN_DOCUMENT_TREE` and persisted URI grants; Samsung file providers can be sensitive to grant flags.
- Avoid destructive git commands. The working tree may include user changes.
