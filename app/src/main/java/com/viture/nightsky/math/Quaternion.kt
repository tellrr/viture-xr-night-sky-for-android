package com.viture.nightsky.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class Vector3(val x: Float, val y: Float, val z: Float)

data class Quaternion(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
) {
    fun dot(other: Quaternion): Float = x * other.x + y * other.y + z * other.z + w * other.w

    fun magnitude(): Float = sqrt(max(dot(this), 1.0e-12f))

    fun normalized(): Quaternion {
        val length = magnitude()
        return Quaternion(x / length, y / length, z / length, w / length)
    }

    fun inverse(): Quaternion {
        val normalized = normalized()
        return Quaternion(-normalized.x, -normalized.y, -normalized.z, normalized.w)
    }

    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w,
            w = w * other.w - x * other.x - y * other.y - z * other.z
        )
    }

    operator fun unaryMinus(): Quaternion = Quaternion(-x, -y, -z, -w)

    fun isValid(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite() &&
            !(kotlin.math.abs(x) < 1.0e-6f &&
                kotlin.math.abs(y) < 1.0e-6f &&
                kotlin.math.abs(z) < 1.0e-6f &&
                kotlin.math.abs(w) < 1.0e-6f)
    }

    fun slerp(target: Quaternion, t: Float): Quaternion {
        var end = target
        var cosTheta = dot(end)
        if (cosTheta < 0.0f) {
            end = -end
            cosTheta = -cosTheta
        }

        if (cosTheta > 0.9995f) {
            return Quaternion(
                x + (end.x - x) * t,
                y + (end.y - y) * t,
                z + (end.z - z) * t,
                w + (end.w - w) * t
            ).normalized()
        }

        val clampedCos = cosTheta.coerceIn(-1.0f, 1.0f)
        val theta0 = acos(clampedCos)
        val theta = theta0 * t
        val sinTheta = sin(theta)
        val sinTheta0 = sin(theta0)

        val scale0 = cos(theta) - clampedCos * sinTheta / sinTheta0
        val scale1 = sinTheta / sinTheta0
        return Quaternion(
            x = x * scale0 + end.x * scale1,
            y = y * scale0 + end.y * scale1,
            z = z * scale0 + end.z * scale1,
            w = w * scale0 + end.w * scale1
        ).normalized()
    }

    fun rotate(vector: Vector3): Vector3 {
        val vectorQuaternion = Quaternion(vector.x, vector.y, vector.z, 0.0f)
        val rotated = this * vectorQuaternion * inverse()
        return Vector3(rotated.x, rotated.y, rotated.z)
    }

    companion object {
        val IDENTITY = Quaternion(0.0f, 0.0f, 0.0f, 1.0f)

        fun fromAxisAngle(axisX: Float, axisY: Float, axisZ: Float, angleRadians: Float): Quaternion {
            val half = angleRadians * 0.5f
            val sinHalf = sin(half)
            return Quaternion(
                x = axisX * sinHalf,
                y = axisY * sinHalf,
                z = axisZ * sinHalf,
                w = cos(half)
            ).normalized()
        }

        fun fromEuler(pitchRadians: Float, yawRadians: Float, rollRadians: Float): Quaternion {
            val yaw = fromAxisAngle(0.0f, 1.0f, 0.0f, yawRadians)
            val pitch = fromAxisAngle(1.0f, 0.0f, 0.0f, pitchRadians)
            val roll = fromAxisAngle(0.0f, 0.0f, 1.0f, rollRadians)
            return (yaw * pitch * roll).normalized()
        }
    }
}
