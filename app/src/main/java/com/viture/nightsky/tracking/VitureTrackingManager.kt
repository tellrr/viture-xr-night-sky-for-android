package com.viture.nightsky.tracking

import android.content.Context
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.viture.nightsky.math.Quaternion
import com.viture.nightsky.scene.NightSkySceneController
import com.viture.sdk.ArCallback
import com.viture.sdk.ArManager
import com.viture.sdk.Constants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Method
import kotlin.math.abs

class VitureTrackingManager(
    context: Context,
    private val sceneController: NightSkySceneController
) {
    private val appContext = context.applicationContext
    private val arManager = ArManager.getInstance(appContext)
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var sdkReady = false
    private var lastInitResult = Int.MIN_VALUE
    private var imuPacketCount = 0
    private var reflectedInitUsb: Method? = null
    private var phoneFallbackRegistered = false
    private val phoneFallbackQuaternion = FloatArray(4)
    private var nativeRunning = false
    private var nativeConnection: UsbDeviceConnection? = null
    private var nativePermissionReceiverRegistered = false
    private val nativePose = FloatArray(7)
    private val nativePoseStatus = IntArray(1)

    private val nativePollRunnable = object : Runnable {
        override fun run() {
            if (!started || !nativeRunning) {
                return
            }

            val result = NativeVitureBridge.nativePollPose(nativePose, nativePoseStatus)
            if (result == 0) {
                val orientation = Quaternion(
                    x = nativePose[4],
                    y = nativePose[5],
                    z = nativePose[6],
                    w = nativePose[3]
                )
                val stability = if (nativePoseStatus[0] == 0) "stable" else "warming up"
                sceneController.onSensorPose(
                    orientation,
                    "Receiving live pose data from VITURE SDK ${NativeVitureBridge.nativeVersion()} ($stability)."
                )
            } else if (result != NATIVE_ERROR_NO_DATA && result != NATIVE_ERROR_NO_HANDLE) {
                sceneController.setTrackingStatus("VITURE SDK 2.2.1 pose poll failed: $result.")
            }

            mainHandler.postDelayed(this, NATIVE_POLL_INTERVAL_MS)
        }
    }

    private val nativePermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_NATIVE_USB_PERMISSION) {
                return
            }
            if (!started) {
                return
            }

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                sceneController.clearSensorPose("USB permission denied for VITURE SDK 2.2.1.")
                return
            }

            if (tryStartNativeSdk()) {
                return
            }
        }
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!started) {
                return
            }

            if (!sdkReady && lastInitResult != Constants.ERROR_INIT_NO_PERMISSION) {
                if (!tryStartNativeSdk()) {
                    handleInitResult(initializeSdk())
                }
            }
            mainHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    private val callback = object : ArCallback() {
        override fun onEvent(msgId: Int, event: ByteArray, timestamp: Long) {
            Log.d(TAG, "VITURE event msgId=$msgId bytes=${event.size}")
            if (msgId == Constants.EVENT_ID_INIT) {
                handleInitResult(readLittleEndianInt(event))
            }
        }

        override fun onImu(ts: Long, imu: ByteArray) {
            if (imu.size < 12) {
                return
            }

            val buffer = ByteBuffer.wrap(imu).order(ByteOrder.BIG_ENDIAN)
            var roll = buffer.getFloat(0)
            var pitch = buffer.getFloat(4)
            var yaw = buffer.getFloat(8)
            imuPacketCount++

            val maxAbsAngle = maxOf(abs(roll), abs(pitch), abs(yaw))
            if (maxAbsAngle > 7.0f) {
                val toRadians = Math.PI.toFloat() / 180.0f
                roll *= toRadians
                pitch *= toRadians
                yaw *= toRadians
            }

            val orientation = Quaternion.fromEuler(pitch, yaw, roll)
            if (imuPacketCount <= INITIAL_LOGGED_IMU_PACKETS) {
                Log.d(TAG, "IMU#$imuPacketCount roll=$roll pitch=$pitch yaw=$yaw bytes=${imu.size}")
            }

            stopPhoneRotationFallback()
            sceneController.onSensorPose(orientation)
        }
    }

    private val phoneFallbackListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (sdkReady || event.values.isEmpty()) {
                return
            }

            SensorManager.getQuaternionFromVector(phoneFallbackQuaternion, event.values)
            val orientation = Quaternion(
                x = phoneFallbackQuaternion[1],
                y = phoneFallbackQuaternion[2],
                z = phoneFallbackQuaternion[3],
                w = phoneFallbackQuaternion[0]
            )
            sceneController.onSensorPose(
                orientation,
                "Using phone rotation fallback. Luma Ultra glasses tracking needs the newer VITURE XR Glasses SDK."
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (started) {
            return
        }

        started = true
        sdkReady = false
        imuPacketCount = 0
        arManager.setLogOn(true)
        arManager.registerCallback(callback)
        if (!tryStartNativeSdk()) {
            handleInitResult(initializeSdk())
        }
        mainHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    fun stop() {
        if (!started) {
            return
        }

        started = false
        mainHandler.removeCallbacks(retryRunnable)
        stopNativeSdk()
        unregisterNativePermissionReceiver()
        arManager.unregisterCallback(callback)
        stopPhoneRotationFallback()
        sdkReady = false
        sceneController.clearSensorPose("Tracking paused. Drag to look around.")
    }

    fun release() {
        mainHandler.removeCallbacks(retryRunnable)
        stopNativeSdk()
        unregisterNativePermissionReceiver()
        stopPhoneRotationFallback()
        if (started) {
            arManager.unregisterCallback(callback)
            started = false
        }
        sdkReady = false
        arManager.release()
        sceneController.clearSensorPose("VITURE tracking released. Drag to look around.")
    }

    private fun tryStartNativeSdk(): Boolean {
        if (nativeRunning) {
            return true
        }

        val device = findCompatibilityVitureDevice() ?: return false
        if (!looksLikeNativeSdkDevice(device)) {
            return false
        }

        ensureNativePermissionReceiver()
        if (!usbManager.hasPermission(device)) {
            requestNativeUsbPermission(device)
            sceneController.clearSensorPose("USB permission is required for VITURE SDK 2.2.1 on ${device.shortLabel()}.")
            return true
        }

        val connection = try {
            usbManager.openDevice(device)
        } catch (error: Throwable) {
            sceneController.clearSensorPose("Could not open ${device.shortLabel()}: ${error.message ?: error.javaClass.simpleName}")
            return true
        } ?: run {
            sceneController.clearSensorPose("Could not open ${device.shortLabel()}.")
            return true
        }

        val result = try {
            NativeVitureBridge.nativeStart(device.productId, connection.fileDescriptor, appContext.cacheDir.absolutePath)
        } catch (error: Throwable) {
            sceneController.clearSensorPose("VITURE SDK 2.2.1 load/start failed: ${error.message ?: error.javaClass.simpleName}")
            connection.close()
            return true
        }

        if (result != 0) {
            sceneController.clearSensorPose(
                "VITURE SDK 2.2.1 start failed: $result. ${NativeVitureBridge.nativeLastError()}"
            )
            connection.close()
            return true
        }

        nativeConnection = connection
        nativeRunning = true
        sdkReady = true
        stopPhoneRotationFallback()
        sceneController.setTrackingStatus("VITURE SDK ${NativeVitureBridge.nativeVersion()} started. Waiting for pose.")
        mainHandler.removeCallbacks(nativePollRunnable)
        mainHandler.post(nativePollRunnable)
        return true
    }

    private fun stopNativeSdk() {
        mainHandler.removeCallbacks(nativePollRunnable)
        if (nativeRunning) {
            NativeVitureBridge.nativeStop()
            nativeRunning = false
        }
        nativeConnection?.close()
        nativeConnection = null
    }

    private fun ensureNativePermissionReceiver() {
        if (nativePermissionReceiverRegistered) {
            return
        }

        ContextCompat.registerReceiver(
            appContext,
            nativePermissionReceiver,
            IntentFilter(ACTION_NATIVE_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        nativePermissionReceiverRegistered = true
    }

    private fun unregisterNativePermissionReceiver() {
        if (!nativePermissionReceiverRegistered) {
            return
        }

        try {
            appContext.unregisterReceiver(nativePermissionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already removed by the framework or process lifecycle.
        }
        nativePermissionReceiverRegistered = false
    }

    private fun requestNativeUsbPermission(device: UsbDevice) {
        val intent = Intent(ACTION_NATIVE_USB_PERMISSION).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun initializeSdk(): Int {
        val normalResult = arManager.init()
        if (normalResult != Constants.ERROR_INIT_NO_DEVICE) {
            return normalResult
        }

        val compatibilityDevice = findCompatibilityVitureDevice() ?: return normalResult
        Log.d(TAG, "Trying compatibility init for ${compatibilityDevice.shortLabel()}")
        sceneController.setTrackingStatus("Trying VITURE compatibility init for ${compatibilityDevice.shortLabel()}")

        return try {
            val method = reflectedInitUsb ?: ArManager::class.java
                .getDeclaredMethod("initUsb", UsbDevice::class.java)
                .apply {
                    isAccessible = true
                    reflectedInitUsb = this
                }
            method.invoke(arManager, compatibilityDevice) as Int
        } catch (error: Throwable) {
            Log.e(TAG, "Compatibility init failed", error)
            Constants.ERROR_INIT_UNKOWN
        }
    }

    private fun handleInitResult(result: Int) {
        lastInitResult = result
        when (result) {
            Constants.ERROR_INIT_SUCCESS -> {
                sdkReady = true
                stopPhoneRotationFallback()
                val frequencyResult = arManager.setImuFrequency(Constants.IMU_FREQUENCE_120)
                val imuResult = arManager.setImuOn(true)
                sceneController.setTrackingStatus(
                    "Connected to VITURE runtime. IMU frequency result=$frequencyResult, IMU on result=$imuResult."
                )
            }

            Constants.ERROR_INIT_NO_DEVICE -> {
                sdkReady = false
                startPhoneRotationFallback()
                sceneController.clearSensorPose(
                    "Waiting for VITURE glasses connection. init=$result. ${usbDeviceSummary()}"
                )
            }

            Constants.ERROR_INIT_NO_PERMISSION -> {
                sdkReady = false
                startPhoneRotationFallback()
                sceneController.clearSensorPose(
                    "USB permission is required for VITURE glasses. init=$result. ${usbDeviceSummary()}"
                )
            }

            else -> {
                sdkReady = false
                startPhoneRotationFallback()
                val limitation = if (findCompatibilityVitureDevice() != null) {
                    "Luma Ultra is visible, but VITURE-SDK-1.0.7 rejected it."
                } else {
                    "VITURE SDK initialization failed."
                }
                sceneController.clearSensorPose("$limitation init=$result. ${usbDeviceSummary()}")
            }
        }
    }

    private fun readLittleEndianInt(bytes: ByteArray): Int {
        val padded = ByteArray(4)
        val count = minOf(bytes.size, 4)
        System.arraycopy(bytes, 0, padded, 0, count)
        return ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun startPhoneRotationFallback() {
        if (phoneFallbackRegistered) {
            return
        }

        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return

        phoneFallbackRegistered = sensorManager.registerListener(
            phoneFallbackListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    private fun stopPhoneRotationFallback() {
        if (!phoneFallbackRegistered) {
            return
        }

        sensorManager.unregisterListener(phoneFallbackListener)
        phoneFallbackRegistered = false
    }

    private fun usbDeviceSummary(): String {
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (error: Throwable) {
            return "USB diagnostic failed: ${error.message ?: error.javaClass.simpleName}"
        }

        if (devices.isEmpty()) {
            return "Android USB host sees no USB devices."
        }

        val supported = devices.filter(::looksLikeSupportedVitureDevice)
        if (supported.isNotEmpty()) {
            return "Supported VITURE USB visible: ${supported.joinToString { it.shortLabel() }}"
        }

        return "USB visible but not VITURE SDK match: ${devices.take(4).joinToString { it.shortLabel() }}"
    }

    private fun findCompatibilityVitureDevice(): UsbDevice? {
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (_: Throwable) {
            return null
        }

        return devices.firstOrNull(::looksLikeSupportedVitureDevice)
            ?: devices.firstOrNull(::looksLikeCompatibilityVitureDevice)
    }

    private fun looksLikeSupportedVitureDevice(device: UsbDevice): Boolean {
        val productId = device.productId
        return device.vendorId == VITURE_USB_VENDOR_ID &&
            (productId == VITURE_USB_FIRST_APP_PRODUCT_ID ||
                ((productId - VITURE_USB_FIRST_APP_PRODUCT_ID) % 2 == 0 && productId < VITURE_USB_PRODUCT_ID_LIMIT))
    }

    private fun looksLikeCompatibilityVitureDevice(device: UsbDevice): Boolean {
        if (device.vendorId != VITURE_USB_VENDOR_ID) {
            return false
        }

        val label = "${device.productName.orEmpty()} ${device.deviceName}".lowercase()
        if (label.contains("microphone")) {
            return false
        }

        return device.productId in VITURE_COMPATIBILITY_PRODUCT_IDS ||
            label.contains("viture") ||
            label.contains("glasses")
    }

    private fun looksLikeNativeSdkDevice(device: UsbDevice): Boolean {
        return device.vendorId == VITURE_USB_VENDOR_ID && device.productId in VITURE_NATIVE_PRODUCT_IDS
    }

    private fun UsbDevice.shortLabel(): String {
        return "${vendorId.toHex4()}:${productId.toHex4()} class=$deviceClass name=${productName ?: deviceName}"
    }

    private fun Int.toHex4(): String = toString(16).padStart(4, '0')

    private companion object {
        private const val TAG = "VitureTracking"
        private const val ACTION_NATIVE_USB_PERMISSION = "com.viture.nightsky.USB_PERMISSION"
        private const val RETRY_INTERVAL_MS = 2_000L
        private const val NATIVE_POLL_INTERVAL_MS = 16L
        private const val INITIAL_LOGGED_IMU_PACKETS = 5
        private const val NATIVE_ERROR_NO_HANDLE = -1000
        private const val NATIVE_ERROR_NO_DATA = -5
        private const val VITURE_USB_VENDOR_ID = 0x35CA
        private const val VITURE_USB_FIRST_APP_PRODUCT_ID = 0x1011
        private const val VITURE_USB_PRODUCT_ID_LIMIT = 0x2000
        private val VITURE_COMPATIBILITY_PRODUCT_IDS = setOf(0x1104)
        private val VITURE_NATIVE_PRODUCT_IDS = setOf(0x1104)
    }
}
