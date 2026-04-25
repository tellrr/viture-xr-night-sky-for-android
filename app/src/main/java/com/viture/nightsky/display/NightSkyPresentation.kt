package com.viture.nightsky.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Window
import android.view.WindowManager
import com.viture.nightsky.hideSystemUi
import com.viture.nightsky.render.NightSkyGLSurfaceView
import com.viture.nightsky.scene.NightSkySceneController

class NightSkyPresentation(
    context: Context,
    display: Display,
    private val sceneController: NightSkySceneController
) : Presentation(context, display) {
    private lateinit var surfaceView: NightSkyGLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.let(::hideSystemUi)
        surfaceView = NightSkyGLSurfaceView(context).apply {
            bindController(sceneController)
        }
        setContentView(surfaceView)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        surfaceView.post {
            window?.let(::hideSystemUi)
        }
    }

    override fun onStart() {
        super.onStart()
        surfaceView.onResume()
        window?.let(::hideSystemUi)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window?.let(::hideSystemUi)
        }
    }

    fun requestPanoramaReload() {
        if (::surfaceView.isInitialized) {
            surfaceView.requestPanoramaReload()
        }
    }

    override fun onStop() {
        surfaceView.onPause()
        super.onStop()
    }
}
