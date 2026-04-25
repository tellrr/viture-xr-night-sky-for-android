This project vendors two VITURE Android SDK paths:

- `third_party/viture/android/sdk-2.2.1` plus `app/src/main/jniLibs`: native VITURE XR Glasses SDK 2.2.1 for Luma Ultra / Carina.
- `app/libs/VITURE-SDK-1.0.7.aar`: compatibility fallback for older VITURE devices.

Source package used for this port:

- `viture_android_sdk_v1.0.7.tar.xz`
- official article: `https://www.viture.com/blog/developer/viture-xr-glasses-sdk-for-android`
- local native SDK source: `VITURE_XR_Glasses_SDK_for_Android/release`

If you update the SDK:

1. Replace the native SDK headers in `third_party/viture/android/sdk-2.2.1/include`.
2. Replace the native `.so` files in both `third_party/viture/android/sdk-2.2.1/<abi>/` and `app/src/main/jniLibs/<abi>/`.
3. Replace the `.aar` in `app/libs/` only if the legacy fallback changes.
4. Rebuild the app.
5. Re-test live pose tracking, USB permission flow, and presentation-display output.
