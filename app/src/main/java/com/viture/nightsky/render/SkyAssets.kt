package com.viture.nightsky.render

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

object SkyAssets {
    private const val PI = 3.14159265358979323846f
    private const val TWO_PI = 6.28318530717958647692f

    fun buildSphereMesh(latitudeSegments: Int, longitudeSegments: Int, radius: Float): MeshData {
        val vertexCount = (latitudeSegments + 1) * (longitudeSegments + 1)
        val vertices = FloatArray(vertexCount * 5)
        val indices = ShortArray(latitudeSegments * longitudeSegments * 6)

        var vertexCursor = 0
        for (lat in 0..latitudeSegments) {
            val v = lat.toFloat() / latitudeSegments.toFloat()
            val phi = v * PI
            val y = cos(phi)
            val ring = sin(phi)

            for (lon in 0..longitudeSegments) {
                val u = lon.toFloat() / longitudeSegments.toFloat()
                val theta = u * TWO_PI

                vertices[vertexCursor++] = radius * ring * cos(theta)
                vertices[vertexCursor++] = radius * y
                vertices[vertexCursor++] = radius * ring * sin(theta)
                vertices[vertexCursor++] = 1.0f - u
                vertices[vertexCursor++] = v
            }
        }

        val stride = longitudeSegments + 1
        var indexCursor = 0
        for (lat in 0 until latitudeSegments) {
            for (lon in 0 until longitudeSegments) {
                val i0 = (lat * stride + lon).toShort()
                val i1 = (lat * stride + lon + 1).toShort()
                val i2 = ((lat + 1) * stride + lon).toShort()
                val i3 = ((lat + 1) * stride + lon + 1).toShort()

                indices[indexCursor++] = i0
                indices[indexCursor++] = i2
                indices[indexCursor++] = i1
                indices[indexCursor++] = i1
                indices[indexCursor++] = i2
                indices[indexCursor++] = i3
            }
        }

        return MeshData(vertices, indices)
    }

    fun buildReferenceCubeMesh(halfExtent: Float): MeshData {
        val vertices = ArrayList<Float>(24 * 5)
        val indices = ArrayList<Short>(36)

        fun tileBounds(tileX: Int, tileY: Int): FloatArray {
            val width = 1.0f / 3.0f
            val height = 1.0f / 2.0f
            val padding = 0.01f
            return floatArrayOf(
                tileX * width + width * padding,
                tileY * height + height * padding,
                (tileX + 1) * width - width * padding,
                (tileY + 1) * height - height * padding
            )
        }

        fun appendTexturedQuad(
            centerX: Float,
            centerY: Float,
            centerZ: Float,
            rightX: Float,
            rightY: Float,
            rightZ: Float,
            upX: Float,
            upY: Float,
            upZ: Float,
            minU: Float,
            minV: Float,
            maxU: Float,
            maxV: Float
        ) {
            val rightOffsetX = rightX * halfExtent
            val rightOffsetY = rightY * halfExtent
            val rightOffsetZ = rightZ * halfExtent
            val upOffsetX = upX * halfExtent
            val upOffsetY = upY * halfExtent
            val upOffsetZ = upZ * halfExtent

            val topLeft = floatArrayOf(
                centerX - rightOffsetX + upOffsetX,
                centerY - rightOffsetY + upOffsetY,
                centerZ - rightOffsetZ + upOffsetZ
            )
            val bottomLeft = floatArrayOf(
                centerX - rightOffsetX - upOffsetX,
                centerY - rightOffsetY - upOffsetY,
                centerZ - rightOffsetZ - upOffsetZ
            )
            val topRight = floatArrayOf(
                centerX + rightOffsetX + upOffsetX,
                centerY + rightOffsetY + upOffsetY,
                centerZ + rightOffsetZ + upOffsetZ
            )
            val bottomRight = floatArrayOf(
                centerX + rightOffsetX - upOffsetX,
                centerY + rightOffsetY - upOffsetY,
                centerZ + rightOffsetZ - upOffsetZ
            )

            val baseIndex = (vertices.size / 5).toShort()
            appendVertex(vertices, topLeft[0], topLeft[1], topLeft[2], minU, minV)
            appendVertex(vertices, bottomLeft[0], bottomLeft[1], bottomLeft[2], minU, maxV)
            appendVertex(vertices, topRight[0], topRight[1], topRight[2], maxU, minV)
            appendVertex(vertices, bottomRight[0], bottomRight[1], bottomRight[2], maxU, maxV)

            indices += baseIndex
            indices += (baseIndex + 1).toShort()
            indices += (baseIndex + 2).toShort()
            indices += (baseIndex + 2).toShort()
            indices += (baseIndex + 1).toShort()
            indices += (baseIndex + 3).toShort()
        }

        fun appendFace(
            centerX: Float,
            centerY: Float,
            centerZ: Float,
            rightX: Float,
            rightY: Float,
            rightZ: Float,
            upX: Float,
            upY: Float,
            upZ: Float,
            tileX: Int,
            tileY: Int
        ) {
            val bounds = tileBounds(tileX, tileY)
            appendTexturedQuad(
                centerX,
                centerY,
                centerZ,
                rightX,
                rightY,
                rightZ,
                upX,
                upY,
                upZ,
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3]
            )
        }

        appendFace(0.0f, 0.0f, halfExtent, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0, 0)
        appendFace(halfExtent, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f, 1, 0)
        appendFace(0.0f, 0.0f, -halfExtent, -1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 2, 0)
        appendFace(-halfExtent, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0, 1)
        appendFace(0.0f, halfExtent, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 1, 1)
        appendFace(0.0f, -halfExtent, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 2, 1)

        return MeshData(vertices.toFloatArray(), indices.toShortArray())
    }

    fun generateNightSkyBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        val galaxyPlaneNormalX = -0.34090984f
        val galaxyPlaneNormalY = 0.9130268f
        val galaxyPlaneNormalZ = -0.2205886f

        for (y in 0 until height) {
            val v = (y + 0.5f) / height.toFloat()
            for (x in 0 until width) {
                val u = (x + 0.5f) / width.toFloat()
                val longitude = (u - 0.5f) * TWO_PI
                val latitude = (0.5f - v) * PI
                val cosLatitude = cos(latitude)
                val dirX = sin(longitude) * cosLatitude
                val dirY = sin(latitude)
                val dirZ = cos(longitude) * cosLatitude

                val zenith = saturate((dirY + 1.0f) * 0.5f)
                var r = lerp(0.0025f, 0.012f, zenith.pow(0.65f))
                var g = lerp(0.0032f, 0.018f, zenith.pow(0.65f))
                var b = lerp(0.01f, 0.038f, zenith.pow(0.65f))

                val distanceToPlane = abs(
                    dirX * galaxyPlaneNormalX +
                        dirY * galaxyPlaneNormalY +
                        dirZ * galaxyPlaneNormalZ
                )
                val planeMask = exp(-((distanceToPlane / 0.18f).pow(2.0f)))
                val planeCore = exp(-((distanceToPlane / 0.09f).pow(2.0f)))

                val dustLarge = fractalNoise(u * 4.0f + 11.0f, v * 7.0f - 3.0f, 4, 0.55f)
                val dustFine = fractalNoise(u * 22.0f - 17.0f, v * 18.0f + 5.0f, 3, 0.5f)
                val dust = saturate(1.0f - (dustLarge * 0.55f + dustFine * 0.45f))

                val laneMask = planeMask * (0.35f + 0.65f * dust)
                val milkyTintR = lerp(0.16f, 0.82f, planeCore)
                val milkyTintG = lerp(0.19f, 0.78f, planeCore)
                val milkyTintB = lerp(0.28f, 0.63f, planeCore)
                r += milkyTintR * laneMask * 0.9f
                g += milkyTintG * laneMask * 0.9f
                b += milkyTintB * laneMask * 0.95f

                val warmNebulaNoise = fractalNoise(u * 14.0f + 23.0f, v * 11.0f - 13.0f, 3, 0.6f)
                val warmNebula = planeMask * smoothStep(0.68f, 0.92f, warmNebulaNoise) * 0.24f
                r += warmNebula * 0.65f
                g += warmNebula * 0.25f

                val smallStarNoise = valueNoise(u * 2300.0f, v * 1200.0f)
                val mediumStarNoise = valueNoise(u * 920.0f + 41.0f, v * 760.0f - 27.0f)
                val brightStarNoise = valueNoise(u * 240.0f - 7.0f, v * 240.0f + 13.0f)
                val smallStars = saturate((smallStarNoise - 0.9965f) / 0.0035f).pow(11.0f)
                val mediumStars = saturate((mediumStarNoise - 0.992f) / 0.008f).pow(9.0f)
                val brightStars = saturate((brightStarNoise - 0.985f) / 0.015f).pow(6.0f)

                val starWarmMix = valueNoise(u * 510.0f + 9.0f, v * 510.0f - 11.0f)
                val starColorR = lerp(0.72f, 1.0f, starWarmMix)
                val starColorG = lerp(0.83f, 0.89f, starWarmMix)
                val starColorB = lerp(1.0f, 0.72f, starWarmMix)
                val starIntensity = smallStars * 0.45f + mediumStars * 0.9f + brightStars * 1.65f
                r += starColorR * starIntensity
                g += starColorG * starIntensity
                b += starColorB * starIntensity

                val galaxyGlow = planeMask * 0.08f
                r += galaxyGlow * 0.12f
                g += galaxyGlow * 0.18f
                b += galaxyGlow * 0.28f

                r = 1.0f - exp(-r)
                g = 1.0f - exp(-g)
                b = 1.0f - exp(-b)

                pixels[y * width + x] = toArgb(
                    r.pow(1.0f / 2.2f),
                    g.pow(1.0f / 2.2f),
                    b.pow(1.0f / 2.2f)
                )
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun generateReferenceCubeBitmap(tileSize: Int): Bitmap {
        val width = tileSize * 3
        val height = tileSize * 2
        val pixels = IntArray(width * height)

        val faces = listOf(
            ReferenceFace(0, 0, 0.05f, 0.11f, 0.24f, 0.33f, 0.52f, 0.9f, 'F'),
            ReferenceFace(1, 0, 0.23f, 0.07f, 0.06f, 0.92f, 0.42f, 0.32f, 'R'),
            ReferenceFace(2, 0, 0.20f, 0.05f, 0.22f, 0.78f, 0.46f, 0.9f, 'B'),
            ReferenceFace(0, 1, 0.04f, 0.18f, 0.10f, 0.40f, 0.82f, 0.48f, 'L'),
            ReferenceFace(1, 1, 0.22f, 0.18f, 0.05f, 0.95f, 0.82f, 0.26f, 'U'),
            ReferenceFace(2, 1, 0.04f, 0.16f, 0.20f, 0.32f, 0.80f, 0.92f, 'D')
        )

        for (y in 0 until height) {
            val tileY = y / tileSize
            val localV = ((y % tileSize) + 0.5f) / tileSize.toFloat()
            for (x in 0 until width) {
                val tileX = x / tileSize
                val localU = ((x % tileSize) + 0.5f) / tileSize.toFloat()

                val face = faces.firstOrNull { it.tileX == tileX && it.tileY == tileY }
                var r = 0.01f
                var g = 0.01f
                var b = 0.01f

                if (face != null) {
                    r = face.baseR
                    g = face.baseG
                    b = face.baseB

                    val minorGrid = min(distanceToGridLine(localU, 10.0f), distanceToGridLine(localV, 10.0f))
                    val majorGrid = min(distanceToGridLine(localU, 4.0f), distanceToGridLine(localV, 4.0f))
                    val border = min(min(localU, 1.0f - localU), min(localV, 1.0f - localV))
                    val centerLine = min(abs(localU - 0.5f), abs(localV - 0.5f))
                    val glyph = glyphMask(face.label, localU, localV)

                    val minorMask = if (minorGrid < 0.0022f) 1.0f else 0.0f
                    val majorMask = if (majorGrid < 0.0038f) 1.0f else 0.0f
                    val borderMask = if (border < 0.012f) 1.0f else 0.0f
                    val centerMask = if (centerLine < 0.007f) 1.0f else 0.0f

                    val accentR = face.accentR
                    val accentG = face.accentG
                    val accentB = face.accentB

                    r = blend(r, accentR, 0.08f)
                    g = blend(g, accentG, 0.08f)
                    b = blend(b, accentB, 0.08f)

                    r = blend(r, 0.92f, minorMask * 0.18f)
                    g = blend(g, 0.94f, minorMask * 0.18f)
                    b = blend(b, 0.98f, minorMask * 0.18f)

                    r = blend(r, 0.98f, majorMask * 0.42f)
                    g = blend(g, 0.98f, majorMask * 0.42f)
                    b = blend(b, 1.0f, majorMask * 0.42f)

                    r = blend(r, accentR, borderMask * 0.72f)
                    g = blend(g, accentG, borderMask * 0.72f)
                    b = blend(b, accentB, borderMask * 0.72f)

                    r = blend(r, 1.0f, centerMask * 0.8f)
                    g = blend(g, 1.0f, centerMask * 0.8f)
                    b = blend(b, 1.0f, centerMask * 0.8f)

                    r = blend(r, 1.0f, glyph * 0.88f)
                    g = blend(g, 1.0f, glyph * 0.88f)
                    b = blend(b, 1.0f, glyph * 0.88f)
                }

                pixels[y * width + x] = toArgb(r, g, b)
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun appendVertex(vertices: MutableList<Float>, x: Float, y: Float, z: Float, u: Float, v: Float) {
        vertices += x
        vertices += y
        vertices += z
        vertices += u
        vertices += v
    }

    private fun fract(value: Float): Float = value - floor(value)

    private fun hash(x: Float, y: Float): Float = fract(sin(x * 127.1f + y * 311.7f) * 43758.5453123f)

    private fun valueNoise(x: Float, y: Float): Float {
        val ix = floor(x)
        val iy = floor(y)
        val fx = fract(x)
        val fy = fract(y)

        val a = hash(ix, iy)
        val b = hash(ix + 1.0f, iy)
        val c = hash(ix, iy + 1.0f)
        val d = hash(ix + 1.0f, iy + 1.0f)

        val ux = fx * fx * (3.0f - 2.0f * fx)
        val uy = fy * fy * (3.0f - 2.0f * fy)
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uy)
    }

    private fun fractalNoise(x: Float, y: Float, octaves: Int, persistence: Float): Float {
        var amplitude = 1.0f
        var frequency = 1.0f
        var sum = 0.0f
        var weight = 0.0f
        repeat(octaves) {
            sum += valueNoise(x * frequency, y * frequency) * amplitude
            weight += amplitude
            amplitude *= persistence
            frequency *= 2.0f
        }
        return if (weight > 0.0f) sum / weight else 0.0f
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = saturate((value - edge0) / (edge1 - edge0))
        return t * t * (3.0f - 2.0f * t)
    }

    private fun distanceToGridLine(coordinate: Float, divisions: Float): Float {
        val scaled = coordinate * divisions
        return abs(scaled - round(scaled)) / divisions
    }

    private fun glyphMask(label: Char, u: Float, v: Float): Float {
        val labelLeft = 0.24f
        val labelRight = 0.76f
        val labelTop = 0.18f
        val labelBottom = 0.82f
        val inset = 0.12f

        if (u < labelLeft || u >= labelRight || v < labelTop || v >= labelBottom) {
            return 0.0f
        }

        val rows = glyphRows(label)
        val glyphU = (u - labelLeft) / (labelRight - labelLeft)
        val glyphV = (v - labelTop) / (labelBottom - labelTop)
        val cellX = glyphU * 5.0f
        val cellY = glyphV * 7.0f
        val pixelX = cellX.toInt().coerceIn(0, 4)
        val pixelY = cellY.toInt().coerceIn(0, 6)
        val localX = fract(cellX)
        val localY = fract(cellY)
        val bitMask = 1 shl (4 - pixelX)
        if ((rows[pixelY].toInt() and bitMask) == 0) {
            return 0.0f
        }

        if (localX < inset || localX > (1.0f - inset) || localY < inset || localY > (1.0f - inset)) {
            return 0.0f
        }

        return 1.0f
    }

    private fun glyphRows(label: Char): ByteArray {
        return when (label) {
            'B' -> byteArrayOf(30, 17, 17, 30, 17, 17, 30)
            'D' -> byteArrayOf(30, 17, 17, 17, 17, 17, 30)
            'F' -> byteArrayOf(31, 16, 16, 30, 16, 16, 16)
            'L' -> byteArrayOf(16, 16, 16, 16, 16, 16, 31)
            'R' -> byteArrayOf(30, 17, 17, 30, 20, 18, 17)
            'U' -> byteArrayOf(17, 17, 17, 17, 17, 17, 14)
            else -> ByteArray(7)
        }
    }

    private fun blend(base: Float, overlay: Float, alpha: Float): Float = lerp(base, overlay, saturate(alpha))

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun saturate(value: Float): Float = value.coerceIn(0.0f, 1.0f)

    private fun toArgb(r: Float, g: Float, b: Float): Int {
        val red = (saturate(r) * 255.0f + 0.5f).toInt()
        val green = (saturate(g) * 255.0f + 0.5f).toInt()
        val blue = (saturate(b) * 255.0f + 0.5f).toInt()
        return (255 shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private data class ReferenceFace(
        val tileX: Int,
        val tileY: Int,
        val baseR: Float,
        val baseG: Float,
        val baseB: Float,
        val accentR: Float,
        val accentG: Float,
        val accentB: Float,
        val label: Char
    )
}
