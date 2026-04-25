package com.viture.nightsky.tracking

object NativeVitureBridge {
    init {
        System.loadLibrary("cloud_protocol")
        System.loadLibrary("carina_vio")
        System.loadLibrary("glasses")
        System.loadLibrary("viture_native_tracking")
    }

    external fun nativeStart(productId: Int, fileDescriptor: Int, cacheDir: String): Int
    external fun nativeStop()
    external fun nativePollPose(poseOut: FloatArray, statusOut: IntArray): Int
    external fun nativeResetOrigin(): Int
    external fun nativeLastError(): String
    external fun nativeVersion(): String
}
