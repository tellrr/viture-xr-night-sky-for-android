package com.viture.nightsky.scene

import android.os.SystemClock
import com.viture.nightsky.math.Quaternion
import com.viture.nightsky.math.Vector3
import kotlin.math.exp

enum class SceneMode {
    NIGHT_SKY,
    REFERENCE_CUBE
}

data class SceneSnapshot(
    val orientation: Quaternion,
    val position: Vector3,
    val floorTrail: List<Vector3>,
    val floorMovementMultiplier: Int,
    val sceneMode: SceneMode,
    val trackingStatus: String,
    val rendererStatus: String,
    val floorTrackingStatus: String,
    val floorTrackingEnabled: Boolean,
    val hasFloorPose: Boolean,
    val hasLivePose: Boolean,
    val environmentLabel: String,
    val zoomFactor: Float
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
    private var floorTrackingStatus = "Floor tracking off."
    private var floorTrackingEnabled = false
    private var hasFloorPose = false
    private var rawFloorPosition = Vector3(0.0f, 0.0f, 0.0f)
    private var floorMovementMultiplier = DEFAULT_FLOOR_MOVEMENT_MULTIPLIER
    private val rawFloorTrail = ArrayList<Vector3>()
    private var environmentLabel = "Current Panorama"
    private var zoomFactor = DEFAULT_ZOOM_FACTOR
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
        val scaledPosition = scaleFloorPoint(rawFloorPosition)
        return SceneSnapshot(
            orientation = currentOrientation,
            position = scaledPosition,
            floorTrail = rawFloorTrail.map(::scaleFloorPoint),
            floorMovementMultiplier = floorMovementMultiplier,
            sceneMode = sceneMode,
            trackingStatus = trackingStatus,
            rendererStatus = rendererStatus,
            floorTrackingStatus = floorTrackingStatus,
            floorTrackingEnabled = floorTrackingEnabled,
            hasFloorPose = hasFloorPose,
            hasLivePose = latestSensorPose != null,
            environmentLabel = environmentLabel,
            zoomFactor = zoomFactor
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
    fun resetFloorPosition(status: String = "Floor tracking origin reset.") {
        rawFloorPosition = Vector3(0.0f, 0.0f, 0.0f)
        hasFloorPose = false
        rawFloorTrail.clear()
        if (floorTrackingEnabled) {
            floorTrackingStatus = status
        }
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
    fun adjustZoom(direction: Int): Float {
        if (direction == 0) {
            return zoomFactor
        }

        zoomFactor = if (direction > 0) {
            zoomFactor * ZOOM_STEP_FACTOR
        } else {
            zoomFactor / ZOOM_STEP_FACTOR
        }.coerceIn(MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR)

        return zoomFactor
    }

    @Synchronized
    fun setFloorMovementMultiplier(multiplier: Int): Int {
        floorMovementMultiplier = multiplier.coerceIn(
            MIN_FLOOR_MOVEMENT_MULTIPLIER,
            MAX_FLOOR_MOVEMENT_MULTIPLIER
        )
        return floorMovementMultiplier
    }

    @Synchronized
    fun setFloorTrackingEnabled(enabled: Boolean, status: String) {
        floorTrackingEnabled = enabled
        floorTrackingStatus = status
        if (!enabled) {
            hasFloorPose = false
            rawFloorPosition = Vector3(0.0f, 0.0f, 0.0f)
            rawFloorTrail.clear()
        }
    }

    @Synchronized
    fun setFloorTrackingStatus(status: String) {
        floorTrackingStatus = status
    }

    @Synchronized
    fun onFloorTrackingPosition(xMeters: Float, zMeters: Float, status: String) {
        if (!xMeters.isFinite() || !zMeters.isFinite()) {
            return
        }

        floorTrackingEnabled = true
        hasFloorPose = true
        rawFloorPosition = Vector3(xMeters, 0.0f, zMeters)
        appendFloorTrailPoint(rawFloorPosition)
        floorTrackingStatus = status
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

    companion object {
        const val MIN_ZOOM_FACTOR = 0.5f
        const val MAX_ZOOM_FACTOR = 5.0f
        const val DEFAULT_ZOOM_FACTOR = 1.0f
        const val MIN_FLOOR_MOVEMENT_MULTIPLIER = 1
        const val MAX_FLOOR_MOVEMENT_MULTIPLIER = 10
        const val DEFAULT_FLOOR_MOVEMENT_MULTIPLIER = 1
        private const val ZOOM_STEP_FACTOR = 1.25f
        private const val MIN_TRAIL_POINT_DISTANCE_METERS = 0.03f
        private const val MAX_TRAIL_POINTS = 512
    }

    private fun expireStaleTracking(now: Long) {
        if (latestSensorPose != null && now - latestSensorTimestampNanos > disconnectTimeoutNanos) {
            latestSensorPose = null
            latestSensorTimestampNanos = 0L
            trackingStatus = "Pose stream stalled. Drag to look around."
        }
    }

    private fun scaleFloorPoint(point: Vector3): Vector3 {
        return Vector3(
            point.x * floorMovementMultiplier.toFloat(),
            point.y,
            point.z * floorMovementMultiplier.toFloat()
        )
    }

    private fun appendFloorTrailPoint(point: Vector3) {
        val previous = rawFloorTrail.lastOrNull()
        if (previous != null) {
            val dx = point.x - previous.x
            val dz = point.z - previous.z
            if (dx * dx + dz * dz < MIN_TRAIL_POINT_DISTANCE_METERS * MIN_TRAIL_POINT_DISTANCE_METERS) {
                return
            }
        }

        rawFloorTrail += point
        if (rawFloorTrail.size > MAX_TRAIL_POINTS) {
            rawFloorTrail.removeAt(0)
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
