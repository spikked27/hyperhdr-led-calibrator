package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SpatialCalibrationTest {
    private val columns = 36
    private val rows = 24
    private val tv = NormalizedRect(0.22, 0.25, 0.78, 0.75)

    private fun frame(make: (x: Double, y: Double) -> Rgb): SpatialFrame {
        val tiles = buildList {
            for (y in 0 until rows) for (x in 0 until columns) {
                add(make((x + 0.5) / columns, (y + 0.5) / rows))
            }
        }
        return SpatialFrame(columns, rows, tiles)
    }

    @Test fun detectsBrightTvRectangle() {
        val white = frame { x, y ->
            if (tv.contains(x, y)) Rgb(0.82, 0.78, 0.74) else Rgb(0.025, 0.024, 0.023)
        }
        val detected = SpatialCalibration.detectTvRect(white)
        assertTrue(abs(detected.left - tv.left) < 0.06, "$detected")
        assertTrue(abs(detected.right - tv.right) < 0.06, "$detected")
        assertTrue(abs(detected.top - tv.top) < 0.08, "$detected")
        assertTrue(abs(detected.bottom - tv.bottom) < 0.08, "$detected")
    }

    @Test fun tracksTvAfterSmallHandheldShift() {
        val original = frame { x, y ->
            if (tv.contains(x, y)) Rgb(0.82, 0.08, 0.05) else Rgb(0.025, 0.024, 0.023)
        }
        val movedTv = NormalizedRect(tv.left + 0.055, tv.top - 0.04, tv.right + 0.055, tv.bottom - 0.04)
        val moved = frame { x, y ->
            if (movedTv.contains(x, y)) Rgb(0.82, 0.08, 0.05) else Rgb(0.025, 0.024, 0.023)
        }
        val tracked = SpatialCalibration.trackTvRect(moved, tv)
        assertTrue(tracked != null)
        assertTrue(abs(requireNotNull(tracked).left - movedTv.left) < 0.08, "$tracked")
        // Keep original referenced so this test also verifies a plausible starting geometry.
        assertTrue(SpatialCalibration.screenColor(original, tv).r > 0.7)
    }

    @Test fun whiteReferenceCancelsStrongWallBrightnessGradient() {
        val black = frame { x, y -> Rgb(0.006 + x * 0.002, 0.006 + y * 0.001, 0.006) }

        fun gain(x: Double, y: Double): Double {
            val dx = kotlin.math.abs(x - 0.5)
            val dy = kotlin.math.abs(y - 0.5)
            return (0.75 - 0.70 * (dx + dy)).coerceAtLeast(0.08)
        }

        val white = frame { x, y ->
            val b = Rgb(0.006 + x * 0.002, 0.006 + y * 0.001, 0.006)
            val g = gain(x, y)
            Rgb(b.r + 0.44 * g, b.g + 0.40 * g, b.b + 0.36 * g)
        }
        val red = frame { x, y ->
            val b = Rgb(0.006 + x * 0.002, 0.006 + y * 0.001, 0.006)
            val g = gain(x, y)
            Rgb(b.r + 0.88 * 0.44 * g, b.g + 0.08 * 0.40 * g, b.b + 0.03 * 0.36 * g)
        }

        val model = SpatialCalibration.buildWallReference(white, black, tv)
        val result = SpatialCalibration.wallColor(red, black, model)

        assertTrue(model.brightnessGradient > 1.5, "test must contain a meaningful gradient")
        assertTrue(abs(result.rgb.r - 0.88) < 0.03, "${result.rgb}")
        assertTrue(abs(result.rgb.g - 0.08) < 0.02, "${result.rgb}")
        assertTrue(abs(result.rgb.b - 0.03) < 0.02, "${result.rgb}")
    }

    @Test fun wallAlignmentCorrectsTwoTileHandheldDrift() {
        val black = frame { _, _ -> Rgb(0.004, 0.004, 0.004) }
        fun gain(x: Double, y: Double): Double =
            (0.82 - 0.9 * abs(x - 0.47) - 0.55 * abs(y - 0.52)).coerceAtLeast(0.06)

        val white = frame { x, y ->
            val g = gain(x, y)
            Rgb(0.004 + 0.46 * g, 0.004 + 0.41 * g, 0.004 + 0.36 * g)
        }

        val dxTiles = 2
        val dyTiles = -1
        val redTiles = buildList {
            for (y in 0 until rows) for (x in 0 until columns) {
                val sourceX = (x - dxTiles).coerceIn(0, columns - 1)
                val sourceY = (y - dyTiles).coerceIn(0, rows - 1)
                val sx = (sourceX + 0.5) / columns
                val sy = (sourceY + 0.5) / rows
                val g = gain(sx, sy)
                add(Rgb(0.004 + 0.90 * 0.46 * g, 0.004 + 0.07 * 0.41 * g, 0.004 + 0.03 * 0.36 * g))
            }
        }
        val redShifted = SpatialFrame(columns, rows, redTiles)

        val model = SpatialCalibration.buildWallReference(white, black, tv)
        val result = SpatialCalibration.wallColor(redShifted, black, model)
        assertTrue(abs(result.rgb.r - 0.90) < 0.06, "${result.rgb} shift=${result.alignmentDx},${result.alignmentDy}")
        assertTrue(result.rgb.g < 0.13, "${result.rgb}")
        assertTrue(result.rgb.b < 0.09, "${result.rgb}")
        assertTrue(abs(result.alignmentDx) <= 3 && abs(result.alignmentDy) <= 3)
    }

    @Test fun wallReferenceRejectsColoredOutlierArea() {
        val black = frame { _, _ -> Rgb(0.005, 0.005, 0.005) }
        val white = frame { x, y ->
            if (x < 0.18 && y < 0.35) Rgb(0.35, 0.10, 0.08)
            else Rgb(0.30, 0.29, 0.28)
        }
        val green = frame { x, y ->
            if (x < 0.18 && y < 0.35) Rgb(0.12, 0.20, 0.05)
            else Rgb(0.03, 0.55, 0.04)
        }
        val model = SpatialCalibration.buildWallReference(white, black, tv)
        val result = SpatialCalibration.wallColor(green, black, model)
        assertTrue(result.rgb.g > result.rgb.r * 4.0)
        assertTrue(result.rgb.g > result.rgb.b * 4.0)
    }

    @Test fun medianCombiningSuppressesSingleFrameOutlier() {
        val normal = frame { _, _ -> Rgb(0.2, 0.4, 0.6) }
        val bad = frame { _, _ -> Rgb(1.0, 0.0, 0.0) }
        val combined = SpatialCalibration.medianCombine(listOf(normal, normal, bad, normal, normal))
        val center = combined[columns / 2, rows / 2]
        assertTrue(abs(center.r - 0.2) < 1e-9)
        assertTrue(abs(center.g - 0.4) < 1e-9)
        assertTrue(abs(center.b - 0.6) < 1e-9)
    }
}
