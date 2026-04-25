package com.viture.nightsky.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class MeshData(
    vertices: FloatArray,
    indices: ShortArray
) {
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertices)
            position(0)
        }

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(indices.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
            put(indices)
            position(0)
        }

    private val indexCount = indices.size

    fun draw(positionHandle: Int, texCoordHandle: Int) {
        val strideBytes = 5 * 4

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
    }
}
