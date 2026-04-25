#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>

#include "viture_device_carina.h"
#include "viture_glasses_provider.h"
#include "viture_result.h"
#include "viture_version.h"

namespace {

constexpr const char* kLogTag = "VitureNativeTracking";
constexpr int kErrorNoHandle = -1000;
constexpr int kErrorCreateFailed = -1001;
constexpr int kErrorNotCarina = -1002;

std::mutex g_mutex;
XRDeviceProviderHandle g_handle = nullptr;
std::string g_last_error = "Native tracking not started.";
int g_device_type = -1;

void SetLastError(const std::string& message) {
    g_last_error = message;
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, message.c_str());
}

void StopLocked() {
    if (!g_handle) {
        return;
    }

    xr_device_provider_stop(g_handle);
    xr_device_provider_shutdown(g_handle);
    xr_device_provider_destroy(g_handle);
    g_handle = nullptr;
    g_device_type = -1;
}

std::string JStringToString(JNIEnv* env, jstring value) {
    if (!value) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return {};
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_viture_nightsky_tracking_NativeVitureBridge_nativeStart(
    JNIEnv* env,
    jobject,
    jint product_id,
    jint file_descriptor,
    jstring cache_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    StopLocked();

    xr_device_provider_set_log_level(3);
    const std::string cache_dir_string = JStringToString(env, cache_dir);

    g_handle = xr_device_provider_create(product_id, file_descriptor);
    if (!g_handle) {
        SetLastError("xr_device_provider_create failed.");
        return kErrorCreateFailed;
    }

    g_device_type = xr_device_provider_get_device_type(g_handle);
    if (g_device_type == XR_DEVICE_TYPE_VITURE_CARINA) {
        const int dof_result = xr_device_provider_set_dof_type_carina(g_handle, 0);
        if (dof_result != VITURE_GLASSES_SUCCESS) {
            SetLastError("xr_device_provider_set_dof_type_carina failed: " + std::to_string(dof_result));
            StopLocked();
            return dof_result;
        }
    } else {
        SetLastError("Unsupported native SDK device type: " + std::to_string(g_device_type));
        StopLocked();
        return kErrorNotCarina;
    }

    int result = xr_device_provider_initialize(
        g_handle,
        nullptr,
        cache_dir_string.empty() ? nullptr : cache_dir_string.c_str());
    if (result != VITURE_GLASSES_SUCCESS) {
        SetLastError("xr_device_provider_initialize failed: " + std::to_string(result));
        StopLocked();
        return result;
    }

    result = xr_device_provider_start(g_handle);
    if (result != VITURE_GLASSES_SUCCESS) {
        SetLastError("xr_device_provider_start failed: " + std::to_string(result));
        StopLocked();
        return result;
    }

    g_last_error = "Native VITURE SDK started.";
    __android_log_write(ANDROID_LOG_INFO, kLogTag, g_last_error.c_str());
    return VITURE_GLASSES_SUCCESS;
}

extern "C" JNIEXPORT void JNICALL
Java_com_viture_nightsky_tracking_NativeVitureBridge_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    StopLocked();
    g_last_error = "Native tracking stopped.";
}

extern "C" JNIEXPORT jint JNICALL
Java_com_viture_nightsky_tracking_NativeVitureBridge_nativePollPose(
    JNIEnv* env,
    jobject,
    jfloatArray pose_out,
    jintArray status_out) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_handle) {
        return kErrorNoHandle;
    }

    float pose[7] = {};
    int pose_status = 0;
    const int result = xr_device_provider_get_gl_pose_carina(g_handle, pose, 0.0, &pose_status);
    if (result != VITURE_GLASSES_SUCCESS) {
        return result;
    }

    env->SetFloatArrayRegion(pose_out, 0, 7, pose);
    env->SetIntArrayRegion(status_out, 0, 1, &pose_status);
    return VITURE_GLASSES_SUCCESS;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_viture_nightsky_tracking_NativeVitureBridge_nativeResetOrigin(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_handle) {
        return kErrorNoHandle;
    }

    float identity_pose[7] = {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f};
    return xr_device_provider_reset_origin_carina(g_handle, identity_pose);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_viture_nightsky_tracking_NativeVitureBridge_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_viture_nightsky_tracking_NativeVitureBridge_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(GetVersionString());
}
