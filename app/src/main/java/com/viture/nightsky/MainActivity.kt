package com.viture.nightsky

import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.viture.nightsky.display.NightSkyPresentation
import com.viture.nightsky.render.NightSkyGLSurfaceView
import com.viture.nightsky.scene.NightSkySceneController
import com.viture.nightsky.scene.SceneMode
import com.viture.nightsky.tracking.VitureTrackingManager
import com.viture.nightsky.util.PanoramaFolderState
import com.viture.nightsky.util.PanoramaRepository

class MainActivity : AppCompatActivity(), DisplayManager.DisplayListener {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var sceneController: NightSkySceneController
    private lateinit var trackingManager: VitureTrackingManager
    private lateinit var displayManager: DisplayManager
    private lateinit var panoramaRepository: PanoramaRepository

    private lateinit var surfaceView: NightSkyGLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var sceneButton: Button
    private lateinit var recenterButton: Button
    private lateinit var folderButton: Button

    private var presentation: NightSkyPresentation? = null
    private var activePresentationDisplayId: Int = Display.INVALID_DISPLAY
    private var selectedFolderState: PanoramaFolderState? = null

    private val hudUpdater = object : Runnable {
        override fun run() {
            refreshHud()
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            handleFolderPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sceneController = NightSkySceneController()
        trackingManager = VitureTrackingManager(applicationContext, sceneController)
        displayManager = getSystemService(DisplayManager::class.java)
        panoramaRepository = PanoramaRepository(applicationContext)
        selectedFolderState = panoramaRepository.selectedFolderState()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.night_sky_surface)
        statusText = findViewById(R.id.status_text)
        sceneButton = findViewById(R.id.scene_button)
        recenterButton = findViewById(R.id.recenter_button)
        folderButton = findViewById(R.id.folder_button)

        surfaceView.bindController(sceneController)
        surfaceView.setPanoramaSwipeListener { delta ->
            moveSelectedPanorama(delta)
        }

        sceneButton.setOnClickListener {
            sceneController.toggleScene()
            refreshHud()
        }

        recenterButton.setOnClickListener {
            sceneController.recenter()
            refreshHud()
        }

        folderButton.setOnClickListener {
            folderPicker.launch(null)
        }

        refreshHud()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi(window)
        surfaceView.onResume()
        trackingManager.start()
        displayManager.registerDisplayListener(this, mainHandler)
        ensurePresentation()
        mainHandler.post(hudUpdater)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(hudUpdater)
        displayManager.unregisterDisplayListener(this)
        dismissPresentation()
        trackingManager.stop()
        surfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        trackingManager.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi(window)
        }
    }

    override fun onDisplayAdded(displayId: Int) {
        ensurePresentation()
        refreshHud()
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (displayId == activePresentationDisplayId) {
            dismissPresentation()
        }
        ensurePresentation()
        refreshHud()
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == activePresentationDisplayId) {
            ensurePresentation()
            refreshHud()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_VOLUME_UP -> {
                sceneController.recenter()
                refreshHud()
                true
            }

            KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                sceneController.toggleScene()
                refreshHud()
                true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun refreshHud() {
        val snapshot = sceneController.snapshot()
        val folderState = selectedFolderState
        sceneButton.text = if (snapshot.sceneMode == SceneMode.NIGHT_SKY) {
            getString(R.string.show_reference_cube)
        } else {
            getString(R.string.show_panorama)
        }

        val outputLine = if (presentation != null) {
            "Output: immersive view on ${presentation?.display?.name ?: "external display"}"
        } else {
            "Output: immersive view on phone screen"
        }

        val sceneLine = if (snapshot.sceneMode == SceneMode.NIGHT_SKY) {
            "Scene: ${snapshot.environmentLabel}"
        } else {
            "Scene: Reference Cube"
        }

        val trackingLine = "Tracking: ${snapshot.trackingStatus}"
        val rendererLine = "Renderer: ${snapshot.rendererStatus}"
        val controlsLine = if (folderState != null && folderState.imageCount > 1) {
            "Controls: swipe left/right for previous/next panorama; Recenter resets forward."
        } else if (snapshot.hasLivePose) {
            "Controls: use the phone buttons, a keyboard with R/G, or volume up/down."
        } else {
            "Controls: drag the phone screen to look around, then recenter if needed."
        }

        statusText.text = listOf(outputLine, sceneLine, rendererLine, trackingLine, controlsLine).joinToString("\n\n")
    }

    private fun handleFolderPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers grant session access only; use it for this run if possible.
        }

        val folderState = panoramaRepository.selectFolder(uri)
        if (folderState == null) {
            selectedFolderState = null
            sceneController.setRendererStatus("Selected folder has no supported panorama images.")
            refreshHud()
            return
        }

        selectedFolderState = folderState
        sceneController.setRendererStatus("Selected ${folderState.folderName}: ${folderState.displayLabel}.")
        reloadPanoramaViews()
    }

    private fun moveSelectedPanorama(delta: Int): Boolean {
        val folderState = panoramaRepository.moveFolderSelection(delta) ?: return false
        selectedFolderState = folderState
        sceneController.setRendererStatus("Loading ${folderState.displayLabel}.")
        reloadPanoramaViews()
        return true
    }

    private fun reloadPanoramaViews() {
        surfaceView.requestPanoramaReload()
        presentation?.requestPanoramaReload()
        refreshHud()
    }

    private fun ensurePresentation() {
        val targetDisplay = selectPresentationDisplay()
        if (targetDisplay == null) {
            dismissPresentation()
            return
        }

        if (presentation != null && activePresentationDisplayId == targetDisplay.displayId) {
            return
        }

        dismissPresentation()

        val newPresentation = NightSkyPresentation(this, targetDisplay, sceneController)
        try {
            newPresentation.show()
            presentation = newPresentation
            activePresentationDisplayId = targetDisplay.displayId
        } catch (_: WindowManager.InvalidDisplayException) {
            dismissPresentation()
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
        activePresentationDisplayId = Display.INVALID_DISPLAY
    }

    private fun selectPresentationDisplay(): Display? {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isEmpty()) {
            return null
        }

        return displays.firstOrNull { display ->
            display.name.contains("viture", ignoreCase = true) ||
                display.name.contains("xr", ignoreCase = true)
        } ?: displays.first()
    }
}

@Suppress("DEPRECATION")
internal fun hideSystemUi(window: Window) {
    window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    WindowCompat.setDecorFitsSystemWindows(window, false)

    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    insetsController.hide(WindowInsetsCompat.Type.systemBars())
}
