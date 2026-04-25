package com.viture.nightsky.scene

import android.os.SystemClock
import com.viture.nightsky.math.Quaternion
import kotlin.math.exp

enum class SceneMode {
    NIGHT_SKY,
    REFERENCE_CUBE
}

data class SceneSnapshot(
    val orientation: Quaternion,
    val sceneMode: SceneMode,
    val trackingStatus: String,
    val rendererStatus: String,
    val hasLivePose: Boolean,
    val environmentLabel: String
)

class NightSkySceneController(
    private val smoothTimeSeconds: Float = 0.035f,
    private val disconnectTimeoutNanos: Long = 2_000_000_000L
) {
    private val orientationFilter = OrientationFilter(smoothTimeSeconds)

    private var latestSensorPose: Quaternion? = null
    private var latestSensorTimestampNanos: Long = 0L
    private var recenterReference: Quaternion = Quaternion.IDENTITY
    private var currentOrientation: Quaternion = Quaternion.IDENTITY
    private var manualYawRadians = 0.0f
    private var manualPitchRadians = 0.0f
    private var sceneMode = SceneMode.NIGHT_SKY
    private var trackingStatus = "Waiting for VITURE glasses connection."
    private var rendererStatus = "Renderer starting."
    private var environmentLabel = "Current Panorama"
    private var lastUpdateNanos = SystemClock.elapsedRealtimeNanos()

    init {
        orientationFilter.reset(currentOrientation)
    }

    @Synchronized
    fun snapshot(): SceneSnapshot {
        val now = SystemClock.elapsedRealtimeNanos()
        expireStaleTracking(now)
        val deltaSeconds =
            ((now - lastUpdateNanos).coerceAtLeast(0L).toFloat() / 1_000_000_000.0f).coerceAtMost(0.1f)
        lastUpdateNanos = now

        val targetOrientation = latestSensorPose?.let { recenterReference * it }
            ?: Quaternion.fromEuler(manualPitchRadians, manualYawRadians, 0.0f)

        currentOrientation = orientationFilter.update(targetOrientation, deltaSeconds)
        return SceneSnapshot(
            orientation = currentOrientation,
            sceneMode = sceneMode,
            trackingStatus = trackingStatus,
            rendererStatus = rendererStatus,
            hasLivePose = latestSensorPose != null,
            environmentLabel = environmentLabel
        )
    }

    @Synchronized
    fun toggleScene() {
        sceneMode = if (sceneMode == SceneMode.NIGHT_SKY) {
            SceneMode.REFERENCE_CUBE
        } else {
            SceneMode.NIGHT_SKY
        }
    }

    @Synchronized
    fun recenter() {
        expireStaleTracking(SystemClock.elapsedRealtimeNanos())
        if (latestSensorPose != null) {
            recenterReference = latestSensorPose!!.inverse()
        } else {
            manualYawRadians = 0.0f
            manualPitchRadians = 0.0f
            recenterReference = Quaternion.IDENTITY
        }

        currentOrientation = Quaternion.IDENTITY
        orientationFilter.reset(Quaternion.IDENTITY)
    }

    @Synchronized
    fun updateManualOrientation(deltaX: Float, deltaY: Float) {
        if (latestSensorPose != null) {
            return
        }

        manualYawRadians += deltaX * 0.004f
        manualPitchRadians = (manualPitchRadians + deltaY * 0.004f).coerceIn(-1.45f, 1.45f)
    }

    @Synchronized
    fun onSensorPose(
        rawOrientation: Quaternion,
        status: String = "Receiving live pose data from VITURE glasses."
    ) {
        val normalized = rawOrientation.normalized()
        if (!normalized.isValid()) {
            return
        }

        latestSensorPose = normalized
        latestSensorTimestampNanos = SystemClock.elapsedRealtimeNanos()
        trackingStatus = status
    }

    @Synchronized
    fun clearSensorPose(status: String) {
        latestSensorPose = null
        latestSensorTimestampNanos = 0L
        trackingStatus = status
    }

    @Synchronized
    fun setTrackingStatus(status: String) {
        trackingStatus = status
    }

    @Synchronized
    fun setRendererStatus(status: String) {
        rendererStatus = status
    }

    @Synchronized
    fun setEnvironmentLabel(label: String) {
        environmentLabel = label
    }

    @Synchronized
    fun hasLivePose(): Boolean {
        expireStaleTracking(SystemClock.elapsedRealtimeNanos())
        return latestSensorPose != null
    }

    private fun expireStaleTracking(now: Long) {
        if (latestSensorPose != null && now - latestSensorTimestampNanos > disconnectTimeoutNanos) {
            latestSensorPose = null
            latestSensorTimestampNanos = 0L
            trackingStatus = "Pose stream stalled. Drag to look around."
        }
    }
}

private class OrientationFilter(private var smoothTimeSeconds: Float) {
    private var initialized = false
    private var currentOrientation: Quaternion = Quaternion.IDENTITY

    fun reset(orientation: Quaternion) {
        currentOrientation = orientation.normalized()
        initialized = true
    }

    fun update(targetOrientation: Quaternion, deltaSeconds: Float): Quaternion {
        val target = targetOrientation.normalized()
        if (!initialized) {
            reset(target)
            return currentOrientation
        }

        if (smoothTimeSeconds <= 0.0f) {
            currentOrientation = target
            return currentOrientation
        }

        val alpha = 1.0f - exp(-deltaSeconds / smoothTimeSeconds.coerceAtLeast(0.001f))
        currentOrientation = currentOrientation.slerp(target, alpha)
        return currentOrientation
    }
}
