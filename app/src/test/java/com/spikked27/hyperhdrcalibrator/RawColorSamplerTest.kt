package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertTrue

class RawColorSamplerTest {
    @Test fun rggbRecoversLinearSensorChannels() {
        val width = 40
        val height = 40
        val black = doubleArrayOf(64.0, 64.0, 64.0, 64.0)
        val white = 1023
        fun channelValue(x: Int, y: Int): Int {
            val parity = (y and 1) * 2 + (x and 1)
            val v = when (parity) {
                0 -> 0.80 // R
                3 -> 0.20 // B
                else -> 0.50 // G
            }
            return (64 + v * (white - 64)).toInt()
        }
        val s = RawColorSampler.sample(width, height, BayerPattern.RGGB, white, black, ::channelValue)
        assertTrue(kotlin.math.abs(s.rgb.r - 0.80) < 0.01)
        assertTrue(kotlin.math.abs(s.rgb.g - 0.50) < 0.01)
        assertTrue(kotlin.math.abs(s.rgb.b - 0.20) < 0.01)
        assertTrue(s.clippedFraction < 0.001)
    }

    @Test fun detectsClipping() {
        val width = 40
        val height = 40
        val white = 4095
        val black = doubleArrayOf(64.0, 64.0, 64.0, 64.0)
        val s = RawColorSampler.sample(width, height, BayerPattern.BGGR, white, black, { _, _ -> 4095 })
        assertTrue(s.clippedFraction > 0.99)
    }

    @Test fun supportsAllBayerOrdersWithoutSwappingRedBlue() {
        val width = 40
        val height = 40
        val white = 1023
        val black = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val patterns = BayerPattern.entries
        for (pattern in patterns) {
            fun value(x: Int, y: Int): Int {
                val p = (y and 1) * 2 + (x and 1)
                val redParity = when (pattern) { BayerPattern.RGGB -> 0; BayerPattern.GRBG -> 1; BayerPattern.GBRG -> 2; BayerPattern.BGGR -> 3 }
                val blueParity = when (pattern) { BayerPattern.RGGB -> 3; BayerPattern.GRBG -> 2; BayerPattern.GBRG -> 1; BayerPattern.BGGR -> 0 }
                return when (p) { redParity -> 900; blueParity -> 100; else -> 500 }
            }
            val s = RawColorSampler.sample(width, height, pattern, white, black, ::value)
            assertTrue(s.rgb.r > s.rgb.g && s.rgb.g > s.rgb.b, "Failed for $pattern: ${s.rgb}")
        }
    }
}
