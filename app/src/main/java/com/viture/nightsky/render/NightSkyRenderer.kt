package com.viture.nightsky.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.viture.nightsky.math.Vector3
import com.viture.nightsky.scene.NightSkySceneController
import com.viture.nightsky.scene.SceneMode
import com.viture.nightsky.tracking.FloorTrackingFrameSource
import com.viture.nightsky.util.PanoramaRepository
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class NightSkyRenderer(
    context: Context,
    private val sceneController: NightSkySceneController
) : GLSurfaceView.Renderer {
    private val panoramaRepository = PanoramaRepository(context)

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    private var programId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var textureSamplerHandle = 0

    private lateinit var skyMesh: MeshData
    private lateinit var referenceCubeMesh: MeshData

    private var skyTextureId = 0
    private var referenceCubeTextureId = 0
    private var floorTrailTextureId = 0
    private var floorTrailMesh: MeshData? = null
    private var floorTrailSignature = 0
    private var rendererReady = false
    private var rendererError: String? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var floorTrackingFrameSource: FloorTrackingFrameSource? = null
    @Volatile
    private var panoramaReloadRequested = false

    fun setFloorTrackingFrameSource(frameSource: FloorTrackingFrameSource?) {
        floorTrackingFrameSource = frameSource
    }

    fun requestPanoramaReload() {
        panoramaReloadRequested = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            rendererReady = false
            rendererError = null
            releaseGlResources()

            sceneController.setRendererStatus("Preparing OpenGL scene.")
            programId = createProgram(VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_SOURCE)
            positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
            mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
            textureSamplerHandle = GLES20.glGetUniformLocation(programId, "uTexture")

            skyMesh = SkyAssets.buildSphereMesh(latitudeSegments = 64, longitudeSegments = 128, radius = 20.0f)
            referenceCubeMesh = SkyAssets.buildReferenceCubeMesh(halfExtent = 30.0f)
            floorTrackingFrameSource?.onGlSurfaceCreated()

            reloadSkyTexture()

            sceneController.setRendererStatus("Uploading reference cube.")
            referenceCubeTextureId = createTexture(
                SkyAssets.generateReferenceCubeBitmap(
                    tileSize = 384,
                    minorGridDivisions = 24.0f,
                    majorGridDivisions = 6.0f
                )
            )
            floorTrailTextureId = createSolidTexture(0xffff5533.toInt())

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glClearColor(0.0015f, 0.002f, 0.005f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            rendererReady = true
            sceneController.setRendererStatus("Ready.")
        } catch (error: Throwable) {
            rendererError = error.message ?: error.javaClass.simpleName
            rendererReady = false
            Log.e(TAG, "Renderer initialization failed", error)
            sceneController.setRendererStatus("OpenGL error: ${rendererError.orEmpty()}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        updateProjectionMatrix(sceneController.snapshot().zoomFactor)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (panoramaReloadRequested) {
            panoramaReloadRequested = false
            try {
                reloadSkyTexture()
            } catch (error: Throwable) {
                Log.e(TAG, "Panorama reload failed", error)
                sceneController.setRendererStatus("Panorama reload failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        if (!rendererReady) {
            GLES20.glClearColor(0.0015f, 0.002f, 0.005f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        floorTrackingFrameSource?.updateFromGlFrame()
        val snapshot = sceneController.snapshot()
        val clearColor = if (snapshot.sceneMode == SceneMode.REFERENCE_CUBE) {
            floatArrayOf(0.012f, 0.013f, 0.018f, 1.0f)
        } else {
            floatArrayOf(0.0015f, 0.002f, 0.005f, 1.0f)
        }

        GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        updateProjectionMatrix(snapshot.zoomFactor)

        val forward = snapshot.orientation.rotate(Vector3(0.0f, 0.0f, -1.0f))
        val up = snapshot.orientation.rotate(Vector3(0.0f, 1.0f, 0.0f))

        val eye = if (snapshot.sceneMode == SceneMode.REFERENCE_CUBE && snapshot.hasFloorPose) {
            snapshot.position
        } else {
            Vector3(0.0f, 0.0f, 0.0f)
        }

        Matrix.setLookAtM(
            viewMatrix,
            0,
            eye.x,
            eye.y,
            eye.z,
            eye.x + forward.x,
            eye.y + forward.y,
            eye.z + forward.z,
            up.x,
            up.y,
            up.z
        )
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        val activeMesh = if (snapshot.sceneMode == SceneMode.REFERENCE_CUBE) {
            referenceCubeMesh
        } else {
            skyMesh
        }
        val activeTextureId = if (snapshot.sceneMode == SceneMode.REFERENCE_CUBE) {
            referenceCubeTextureId
        } else {
            skyTextureId
        }

        GLES20.glUseProgram(programId)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, viewProjectionMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, activeTextureId)
        GLES20.glUniform1i(textureSamplerHandle, 0)
        activeMesh.draw(positionHandle, texCoordHandle)

        if (snapshot.sceneMode == SceneMode.REFERENCE_CUBE) {
            drawFloorTrail(snapshot.floorTrail)
        }
    }

    private fun drawFloorTrail(trail: List<Vector3>) {
        if (trail.size < 2 || floorTrailTextureId == 0) {
            floorTrailMesh = null
            floorTrailSignature = 0
            return
        }

        val signature = computeTrailSignature(trail)
        if (signature != floorTrailSignature) {
            floorTrailMesh = SkyAssets.buildFloorTrailMesh(
                points = trail,
                floorY = FLOOR_TRAIL_Y,
                lineWidth = FLOOR_TRAIL_WIDTH,
                coordinateLimit = FLOOR_TRAIL_COORDINATE_LIMIT
            )
            floorTrailSignature = signature
        }

        val mesh = floorTrailMesh ?: return
        GLES20.glUseProgram(programId)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, viewProjectionMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floorTrailTextureId)
        GLES20.glUniform1i(textureSamplerHandle, 0)
        mesh.draw(positionHandle, texCoordHandle)
    }

    private fun computeTrailSignature(trail: List<Vector3>): Int {
        var signature = trail.size
        for (point in trail) {
            signature = signature * 31 + (point.x * 100.0f).toInt()
            signature = signature * 31 + (point.z * 100.0f).toInt()
        }
        return signature
    }

    private fun reloadSkyTexture() {
        val maxTextureSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        val textureLimit = minOf(maxTextureSize[0].coerceAtLeast(1024), MAX_PANORAMA_TEXTURE_SIZE)
        val loadResult = panoramaRepository.loadPanorama(textureLimit)
        val panorama = loadResult.image

        val newTextureId = if (panorama != null) {
            sceneController.setRendererStatus(
                "Uploading ${panorama.bitmap.width}x${panorama.bitmap.height} panorama."
            )
            sceneController.setEnvironmentLabel(panorama.label)
            createTexture(panorama.bitmap)
        } else if (loadResult.failureMessage != null) {
            sceneController.setRendererStatus(loadResult.failureMessage)
            sceneController.setEnvironmentLabel("Selected Panorama Failed")
            createTexture(SkyAssets.generateReferenceCubeBitmap(tileSize = 256))
        } else {
            sceneController.setRendererStatus("Using fast procedural fallback.")
            sceneController.setEnvironmentLabel("Procedural Night Sky")
            createTexture(SkyAssets.generateNightSkyBitmap(1024, 512))
        }

        if (skyTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(skyTextureId), 0)
        }
        skyTextureId = newTextureId
    }

    private fun releaseGlResources() {
        if (skyTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(skyTextureId), 0)
            skyTextureId = 0
        }
        if (referenceCubeTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(referenceCubeTextureId), 0)
            referenceCubeTextureId = 0
        }
        if (floorTrailTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(floorTrailTextureId), 0)
            floorTrailTextureId = 0
        }
        floorTrailMesh = null
        floorTrailSignature = 0
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun updateProjectionMatrix(zoomFactor: Float) {
        val safeZoom = zoomFactor.coerceIn(0.5f, 5.0f)
        val verticalFov = (BASE_VERTICAL_FOV_DEGREES / safeZoom)
            .coerceIn(MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES)
        Matrix.perspectiveM(
            projectionMatrix,
            0,
            verticalFov,
            viewportWidth.toFloat() / viewportHeight.toFloat(),
            0.01f,
            50.0f
        )
    }

    private fun createTexture(bitmap: Bitmap): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val textureId = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureId
    }

    private fun createSolidTexture(color: Int): Int {
        return createTexture(Bitmap.createBitmap(intArrayOf(color), 1, 1, Bitmap.Config.ARGB_8888))
    }

    private fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("OpenGL program link failed: $infoLog")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("OpenGL shader compile failed: $infoLog")
        }

        return shader
    }

    companion object {
        private const val TAG = "NightSkyRenderer"
        private const val MAX_PANORAMA_TEXTURE_SIZE = 4096
        private const val BASE_VERTICAL_FOV_DEGREES = 95.0f
        private const val MIN_VERTICAL_FOV_DEGREES = 18.0f
        private const val MAX_VERTICAL_FOV_DEGREES = 150.0f
        private const val FLOOR_TRAIL_Y = -29.45f
        private const val FLOOR_TRAIL_WIDTH = 0.22f
        private const val FLOOR_TRAIL_COORDINATE_LIMIT = 29.0f

        private const val VERTEX_SHADER_SOURCE = """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_SOURCE = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
