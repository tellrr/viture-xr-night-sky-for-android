package com.viture.nightsky.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.viture.nightsky.scene.NightSkySceneController
import com.viture.nightsky.tracking.FloorTrackingFrameSource
import kotlin.math.abs

class NightSkyGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    private var sceneController: NightSkySceneController? = null
    private var rendererInstance: NightSkyRenderer? = null
    private var floorTrackingFrameSource: FloorTrackingFrameSource? = null
    private var panoramaSwipeListener: ((Int) -> Boolean)? = null
    private var zoomSwipeListener: ((Int) -> Boolean)? = null
    private var downTouchX = 0.0f
    private var downTouchY = 0.0f
    private var lastTouchX = 0.0f
    private var lastTouchY = 0.0f
    private var dragging = false

    fun setPanoramaSwipeListener(listener: ((Int) -> Boolean)?) {
        panoramaSwipeListener = listener
    }

    fun setZoomSwipeListener(listener: ((Int) -> Boolean)?) {
        zoomSwipeListener = listener
    }

    fun setFloorTrackingFrameSource(frameSource: FloorTrackingFrameSource?) {
        floorTrackingFrameSource = frameSource
        rendererInstance?.setFloorTrackingFrameSource(frameSource)
    }

    fun requestPanoramaReload() {
        rendererInstance?.requestPanoramaReload()
        requestRender()
    }

    fun bindController(controller: NightSkySceneController) {
        sceneController = controller
        if (rendererInstance != null) {
            return
        }

        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        rendererInstance = NightSkyRenderer(context.applicationContext, controller)
        rendererInstance?.setFloorTrackingFrameSource(floorTrackingFrameSource)
        setRenderer(rendererInstance)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val controller = sceneController ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downTouchX = event.x
                downTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1 && !controller.hasLivePose()) {
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    controller.updateManualOrientation(deltaX, deltaY)
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP && handleSwipe(event.x, event.y)) {
                    return true
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handleSwipe(upX: Float, upY: Float): Boolean {
        val deltaX = upX - downTouchX
        val deltaY = upY - downTouchY

        if (abs(deltaX) >= SWIPE_MIN_DISTANCE_PX && abs(deltaX) >= abs(deltaY) * SWIPE_DIRECTION_BIAS) {
            val direction = if (deltaX < 0.0f) 1 else -1
            return panoramaSwipeListener?.invoke(direction) == true
        }

        if (abs(deltaY) >= SWIPE_MIN_DISTANCE_PX && abs(deltaY) >= abs(deltaX) * SWIPE_DIRECTION_BIAS) {
            val direction = if (deltaY < 0.0f) 1 else -1
            return zoomSwipeListener?.invoke(direction) == true
        }

        return false
    }

    private companion object {
        private const val SWIPE_MIN_DISTANCE_PX = 120.0f
        private const val SWIPE_DIRECTION_BIAS = 1.35f
    }
}
