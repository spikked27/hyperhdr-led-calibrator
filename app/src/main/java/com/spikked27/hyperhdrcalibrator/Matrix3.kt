package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs

internal data class Matrix3(val a: Array<DoubleArray>) {
    init { require(a.size == 3 && a.all { it.size == 3 }) }

    operator fun times(v: Rgb): Rgb = Rgb(
        a[0][0] * v.r + a[0][1] * v.g + a[0][2] * v.b,
        a[1][0] * v.r + a[1][1] * v.g + a[1][2] * v.b,
        a[2][0] * v.r + a[2][1] * v.g + a[2][2] * v.b,
    )

    fun inverse(): Matrix3 {
        val m = a
        val det =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        require(abs(det) > 1e-8) { "Calibration matrix is singular. Re-measure RGB patches." }
        val inv = Array(3) { DoubleArray(3) }
        inv[0][0] =  (m[1][1]*m[2][2]-m[1][2]*m[2][1])/det
        inv[0][1] = -(m[0][1]*m[2][2]-m[0][2]*m[2][1])/det
        inv[0][2] =  (m[0][1]*m[1][2]-m[0][2]*m[1][1])/det
        inv[1][0] = -(m[1][0]*m[2][2]-m[1][2]*m[2][0])/det
        inv[1][1] =  (m[0][0]*m[2][2]-m[0][2]*m[2][0])/det
        inv[1][2] = -(m[0][0]*m[1][2]-m[0][2]*m[1][0])/det
        inv[2][0] =  (m[1][0]*m[2][1]-m[1][1]*m[2][0])/det
        inv[2][1] = -(m[0][0]*m[2][1]-m[0][1]*m[2][0])/det
        inv[2][2] =  (m[0][0]*m[1][1]-m[0][1]*m[1][0])/det
        return Matrix3(inv)
    }

    companion object {
        fun columns(c0: Rgb, c1: Rgb, c2: Rgb): Matrix3 = Matrix3(arrayOf(
            doubleArrayOf(c0.r, c1.r, c2.r),
            doubleArrayOf(c0.g, c1.g, c2.g),
            doubleArrayOf(c0.b, c1.b, c2.b),
        ))
    }
}
