package com.viture.nightsky.tracking

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.viture.nightsky.scene.NightSkySceneController
import java.util.Locale

class ArFloorTrackingManager(
    private val activity: Activity,
    private val sceneController: NightSkySceneController
) : FloorTrackingFrameSource {
    private var session: Session? = null
    private var cameraTextureId = 0
    private var requestedEnabled = false
    private var activityResumed = false
    private var sessionResumed = false
    private var originTranslation: FloatArray? = null
    private var lastErrorStatus: String? = null

    val isEnabled: Boolean
        get() = requestedEnabled

    fun enable(): Boolean {
        requestedEnabled = true
        originTranslation = null
        sceneController.resetFloorPosition("Floor tracking starting.")

        if (!ensureSession()) {
            requestedEnabled = false
            return false
        }

        sceneController.setFloorTrackingEnabled(true, "Floor tracking starting ARCore.")
        resumeSessionIfReady()
        return true
    }

    fun disable() {
        requestedEnabled = false
        originTranslation = null
        pauseSession()
        sceneController.setFloorTrackingEnabled(false, "Floor tracking off.")
    }

    fun resetOrigin() {
        originTranslation = null
        sceneController.resetFloorPosition("Floor tracking origin will reset on next ARCore pose.")
    }

    fun onResume() {
        activityResumed = true
        if (requestedEnabled) {
            resumeSessionIfReady()
        }
    }

    fun onPause() {
        activityResumed = false
        pauseSession()
    }

    fun release() {
        pauseSession()
        session?.close()
        session = null
    }

    override fun onGlSurfaceCreated() {
        cameraTextureId = 0
    }

    override fun updateFromGlFrame() {
        if (!requestedEnabled || !sessionResumed) {
            return
        }

        val activeSession = session ?: return
        try {
            ensureCameraTexture(activeSession)
            val frame = activeSession.update()
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                sceneController.setFloorTrackingStatus(
                    "Floor tracking ${camera.trackingState.name.lowercase(Locale.US)}. Move slowly over textured floor."
                )
                return
            }

            val translation = camera.pose.translation
            val origin = originTranslation
            if (origin == null) {
                originTranslation = translation.copyOf()
                sceneController.onFloorTrackingPosition(0.0f, 0.0f, "Floor tracking origin set.")
                return
            }

            val xMeters = translation[0] - origin[0]
            val zMeters = translation[2] - origin[2]
            sceneController.onFloorTrackingPosition(
                xMeters,
                zMeters,
                String.format(Locale.US, "ARCore floor tracking: x=%+.2fm z=%+.2fm.", xMeters, zMeters)
            )
            lastErrorStatus = null
        } catch (error: Throwable) {
            val status = "ARCore floor tracking update failed: ${error.message ?: error.javaClass.simpleName}"
            if (status != lastErrorStatus) {
                Log.w(TAG, status, error)
                lastErrorStatus = status
            }
            sceneController.setFloorTrackingStatus(status)
        }
    }

    private fun ensureSession(): Boolean {
        if (session != null) {
            return true
        }

        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            if (!availability.isSupported) {
                sceneController.setFloorTrackingEnabled(false, "ARCore is not supported on this device.")
                return false
            }

            val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                sceneController.setFloorTrackingEnabled(false, "Install or update Google Play Services for AR, then enable again.")
                return false
            }

            val newSession = Session(activity)
            val config = Config(newSession).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                focusMode = Config.FocusMode.AUTO
                depthMode = Config.DepthMode.DISABLED
            }
            newSession.configure(config)
            session = newSession
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start ARCore floor tracking", error)
            sceneController.setFloorTrackingEnabled(
                false,
                "Could not start ARCore: ${error.message ?: error.javaClass.simpleName}"
            )
            false
        }
    }

    private fun resumeSessionIfReady() {
        if (!activityResumed || sessionResumed) {
            return
        }

        val activeSession = session ?: return
        try {
            activeSession.resume()
            sessionResumed = true
            sceneController.setFloorTrackingEnabled(true, "Floor tracking waiting for ARCore pose.")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not resume ARCore floor tracking", error)
            sessionResumed = false
            sceneController.setFloorTrackingStatus(
                "Could not resume ARCore: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun pauseSession() {
        if (!sessionResumed) {
            return
        }

        try {
            session?.pause()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not pause ARCore floor tracking", error)
        } finally {
            sessionResumed = false
        }
    }

    private fun ensureCameraTexture(activeSession: Session) {
        if (cameraTextureId == 0) {
            val textureIds = IntArray(1)
            GLES20.glGenTextures(1, textureIds, 0)
            cameraTextureId = textureIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        activeSession.setCameraTextureName(cameraTextureId)
    }

    private companion object {
        private const val TAG = "ArFloorTrackingManager"
    }
}
