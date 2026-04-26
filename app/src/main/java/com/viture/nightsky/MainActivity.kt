package com.viture.nightsky

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.viture.nightsky.display.NightSkyPresentation
import com.viture.nightsky.render.NightSkyGLSurfaceView
import com.viture.nightsky.scene.NightSkySceneController
import com.viture.nightsky.scene.SceneMode
import com.viture.nightsky.tracking.ArFloorTrackingManager
import com.viture.nightsky.tracking.VitureTrackingManager
import com.viture.nightsky.util.PanoramaFolderState
import com.viture.nightsky.util.PanoramaRepository

class MainActivity : AppCompatActivity(), DisplayManager.DisplayListener {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var sceneController: NightSkySceneController
    private lateinit var trackingManager: VitureTrackingManager
    private lateinit var floorTrackingManager: ArFloorTrackingManager
    private lateinit var displayManager: DisplayManager
    private lateinit var panoramaRepository: PanoramaRepository

    private lateinit var rootView: View
    private lateinit var infoPanel: View
    private lateinit var floorMultiplierPanel: View
    private lateinit var floorMultiplierLabel: TextView
    private lateinit var floorMultiplierSeek: SeekBar
    private lateinit var surfaceView: NightSkyGLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var sceneButton: Button
    private lateinit var recenterButton: Button
    private lateinit var settingsButton: ImageButton

    private var presentation: NightSkyPresentation? = null
    private var activePresentationDisplayId: Int = Display.INVALID_DISPLAY
    private var selectedFolderState: PanoramaFolderState? = null
    private var updatingFloorMultiplierUi = false

    private val hudUpdater = object : Runnable {
        override fun run() {
            refreshHud()
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val folderPicker = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) {
            handleFolderPicked(uri, result.data?.flags ?: 0)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            setFloorTrackingEnabled(true)
        } else {
            sceneController.setFloorTrackingEnabled(false, "Camera permission denied; floor tracking off.")
            refreshHud()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sceneController = NightSkySceneController()
        trackingManager = VitureTrackingManager(applicationContext, sceneController)
        floorTrackingManager = ArFloorTrackingManager(this, sceneController)
        displayManager = getSystemService(DisplayManager::class.java)
        panoramaRepository = PanoramaRepository(applicationContext)
        selectedFolderState = panoramaRepository.selectedFolderState()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.main_root)
        infoPanel = findViewById(R.id.info_panel)
        floorMultiplierPanel = findViewById(R.id.floor_multiplier_panel)
        floorMultiplierLabel = findViewById(R.id.floor_multiplier_label)
        floorMultiplierSeek = findViewById(R.id.floor_multiplier_seek)
        surfaceView = findViewById(R.id.night_sky_surface)
        statusText = findViewById(R.id.status_text)
        sceneButton = findViewById(R.id.scene_button)
        recenterButton = findViewById(R.id.recenter_button)
        settingsButton = findViewById(R.id.settings_button)

        surfaceView.bindController(sceneController)
        surfaceView.setFloorTrackingFrameSource(floorTrackingManager)
        surfaceView.setPanoramaSwipeListener { delta ->
            moveSelectedPanorama(delta)
        }
        surfaceView.setZoomSwipeListener { direction ->
            adjustZoom(direction)
        }

        sceneButton.setOnClickListener {
            sceneController.toggleScene()
            refreshHud()
            hideSystemUi(window)
        }

        recenterButton.setOnClickListener {
            sceneController.recenter()
            floorTrackingManager.resetOrigin()
            refreshHud()
            hideSystemUi(window)
        }

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        floorMultiplierSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingFloorMultiplierUi) {
                    return
                }

                val multiplier = progress + NightSkySceneController.MIN_FLOOR_MOVEMENT_MULTIPLIER
                sceneController.setFloorMovementMultiplier(multiplier)
                updateFloorMultiplierUi(multiplier, floorMultiplierPanel.visibility == View.VISIBLE)
                refreshHud()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                hideSystemUi(window)
            }
        })

        rootView.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            updateInfoPanelWidth(right - left, view.resources.displayMetrics.density)
        }
        hideSystemUi(window)
        refreshHud()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi(window)
        surfaceView.onResume()
        floorTrackingManager.onResume()
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
        floorTrackingManager.onPause()
        surfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        floorTrackingManager.release()
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
                floorTrackingManager.resetOrigin()
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
        updateFloorMultiplierUi(snapshot.floorMovementMultiplier, snapshot.floorTrackingEnabled)
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
        val floorLine = "Floor: ${snapshot.floorTrackingStatus}"
        val positionLine = if (snapshot.hasFloorPose) {
            String.format(
                java.util.Locale.US,
                "Position: x=%+.2fm z=%+.2fm",
                snapshot.position.x,
                snapshot.position.z
            )
        } else {
            "Position: not tracking floor movement."
        }
        val rendererLine = "Renderer: ${snapshot.rendererStatus}"
        val folderLine = if (folderState != null) {
            "Folder: ${folderState.folderName} - ${folderState.displayLabel}"
        } else {
            "Folder: tap the gear to choose image folder."
        }
        val zoomLine = "Zoom: ${formatZoom(snapshot.zoomFactor)}"
        val movementLine = "Move scale: ${snapshot.floorMovementMultiplier}x"
        val controlsLine = if (folderState != null && folderState.imageCount > 1) {
            "Controls: swipe left/right for previous/next image, up/down for zoom."
        } else if (snapshot.hasLivePose) {
            "Controls: swipe up/down for zoom; use buttons, R/G, or volume keys."
        } else {
            "Controls: drag to look around; swipe up/down for zoom."
        }

        statusText.text = listOf(
            outputLine,
            sceneLine,
            folderLine,
            zoomLine,
            rendererLine,
            trackingLine,
            floorLine,
            positionLine,
            movementLine,
            controlsLine
        ).joinToString("\n\n")
    }

    private fun showSettingsDialog() {
        val folderState = panoramaRepository.selectedFolderState().also {
            selectedFolderState = it
        }
        val snapshot = sceneController.snapshot()
        val message = if (folderState != null) {
            "Image folder: ${folderState.folderName}\nCurrent image: ${folderState.displayLabel}\n\nFloor: ${snapshot.floorTrackingStatus}"
        } else {
            "Image folder: not selected\nChoose a folder of JPG, PNG, or WebP panoramas.\n\nFloor: ${snapshot.floorTrackingStatus}"
        }
        val floorAction = if (floorTrackingManager.isEnabled) {
            getString(R.string.disable_floor_tracking)
        } else {
            getString(R.string.enable_floor_tracking)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        content.addView(
            TextView(this).apply {
                text = message
                textSize = 18.0f
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            }
        )

        lateinit var dialog: AlertDialog
        addSettingsButton(content, getString(R.string.select_folder)) {
            dialog.dismiss()
            folderPicker.launch(createFolderPickerIntent())
        }
        addSettingsButton(content, floorAction) {
            dialog.dismiss()
            setFloorTrackingEnabled(!floorTrackingManager.isEnabled)
        }
        addSettingsButton(content, getString(R.string.reset_floor_origin)) {
            dialog.dismiss()
            floorTrackingManager.resetOrigin()
            refreshHud()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            hideSystemUi(window)
        }
        dialog.show()
        hideSystemUi(window)
    }

    private fun addSettingsButton(
        container: LinearLayout,
        label: String,
        onClick: () -> Unit
    ) {
        val topMargin = dp(12)
        container.addView(
            Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, 0)
            }
        )
    }

    private fun createFolderPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }

    private fun handleFolderPicked(uri: Uri, grantFlags: Int) {
        val persistableFlags = grantFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if ((persistableFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Keep the transient grant for this app session if this provider is not persistable.
            }
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

    private fun adjustZoom(direction: Int): Boolean {
        sceneController.adjustZoom(direction)
        refreshHud()
        hideSystemUi(window)
        return true
    }

    private fun setFloorTrackingEnabled(enabled: Boolean) {
        if (!enabled) {
            floorTrackingManager.disable()
            refreshHud()
            hideSystemUi(window)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            sceneController.setFloorTrackingStatus("Camera permission required for floor tracking.")
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            refreshHud()
            return
        }

        floorTrackingManager.enable()
        refreshHud()
        hideSystemUi(window)
    }

    private fun formatZoom(zoomFactor: Float): String {
        return String.format(java.util.Locale.US, "%.2fx", zoomFactor)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun updateFloorMultiplierUi(multiplier: Int, visible: Boolean) {
        floorMultiplierPanel.visibility = if (visible) View.VISIBLE else View.GONE
        floorMultiplierLabel.text = "Move ${multiplier}x"

        val expectedProgress = multiplier - NightSkySceneController.MIN_FLOOR_MOVEMENT_MULTIPLIER
        if (floorMultiplierSeek.progress != expectedProgress) {
            updatingFloorMultiplierUi = true
            floorMultiplierSeek.progress = expectedProgress
            updatingFloorMultiplierUi = false
        }

        updateInfoPanelTopMargin(visible)
    }

    private fun updateInfoPanelTopMargin(floorSliderVisible: Boolean) {
        val topMargin = if (floorSliderVisible) dp(78) else dp(16)
        val layoutParams = infoPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        if (layoutParams.topMargin == topMargin) {
            return
        }

        infoPanel.layoutParams = layoutParams.apply {
            this.topMargin = topMargin
        }
    }

    private fun updateInfoPanelWidth(rootWidth: Int, density: Float) {
        if (rootWidth <= 0) {
            return
        }

        val horizontalMarginsPx = (32.0f * density).toInt()
        val minWidthPx = (320.0f * density).toInt()
        val targetWidth = ((rootWidth * INFO_PANEL_WIDTH_FRACTION).toInt() - horizontalMarginsPx)
            .coerceAtLeast(minWidthPx)
        val layoutParams = infoPanel.layoutParams
        if (layoutParams.width == targetWidth) {
            return
        }

        infoPanel.layoutParams = layoutParams.apply {
            width = targetWidth
        }
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

private const val INFO_PANEL_WIDTH_FRACTION = 2.0f / 3.0f

@Suppress("DEPRECATION")
internal fun hideSystemUi(window: Window) {
    window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)

    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LOW_PROFILE

    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    insetsController.hide(WindowInsetsCompat.Type.systemBars())

    window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
        val barsVisible = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0 ||
            visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
        if (barsVisible) {
            window.decorView.postDelayed({ hideSystemUi(window) }, 350L)
        }
    }
}
