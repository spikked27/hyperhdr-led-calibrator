package com.spikked27.hyperhdrcalibrator

import kotlin.math.pow
import kotlin.math.sqrt

object ColorScience {
    private fun linearize(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    fun toXyz(rgb: Rgb): Rgb {
        val n = rgb.normalized()
        val r = linearize(n.r); val g = linearize(n.g); val b = linearize(n.b)
        return Rgb(
            0.4124564*r + 0.3575761*g + 0.1804375*b,
            0.2126729*r + 0.7151522*g + 0.0721750*b,
            0.0193339*r + 0.1191920*g + 0.9503041*b,
        )
    }

    fun xyzToLab(xyz: Rgb): Rgb {
        val xr = xyz.r / 0.95047; val yr = xyz.g; val zr = xyz.b / 1.08883
        fun f(t: Double): Double = if (t > 216.0/24389.0) t.pow(1.0/3.0) else (24389.0/27.0*t + 16.0)/116.0
        val fx=f(xr); val fy=f(yr); val fz=f(zr)
        return Rgb(116*fy-16, 500*(fx-fy), 200*(fy-fz))
    }

    fun deltaE76(a: Rgb, b: Rgb): Double {
        val la = xyzToLab(toXyz(a)); val lb = xyzToLab(toXyz(b))
        return sqrt((la.r-lb.r).pow(2)+(la.g-lb.g).pow(2)+(la.b-lb.b).pow(2))
    }
}
