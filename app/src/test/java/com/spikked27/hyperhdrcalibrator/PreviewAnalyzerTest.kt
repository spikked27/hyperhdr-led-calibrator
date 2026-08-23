package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewAnalyzerTest {
    private val w = 72
    private val h = 40

    private fun frame(rect: NormalizedRect, inside: Rgb, outside: Rgb = Rgb(0.03, 0.03, 0.03)): PreviewFrame {
        val pixels = buildList {
            for (y in 0 until h) for (x in 0 until w) {
                val nx = (x + 0.5) / w
                val ny = (y + 0.5) / h
                add(if (rect.contains(nx, ny)) inside else outside)
            }
        }
        return PreviewFrame(w, h, pixels)
    }

    @Test fun detectsWhiteTvAndFitsActualRectangle() {
        val tv = NormalizedRect(0.18, 0.22, 0.82, 0.76)
        val detected = assertNotNull(PreviewAnalyzer.detectWhiteTv(frame(tv, Rgb(0.92, 0.89, 0.86))))
        assertTrue(abs(detected.left - tv.left) < 0.05, "$detected")
        assertTrue(abs(detected.right - tv.right) < 0.05, "$detected")
        assertTrue(abs(detected.top - tv.top) < 0.06, "$detected")
        assertTrue(abs(detected.bottom - tv.bottom) < 0.06, "$detected")
    }

    @Test fun tracksSmallHandheldShiftOnColoredPatch() {
        val old = NormalizedRect(0.20, 0.24, 0.80, 0.76)
        val moved = NormalizedRect(0.24, 0.21, 0.84, 0.73)
        val white = Rgb(0.90, 0.88, 0.86)
        val redFrame = frame(moved, Rgb(0.90, 0.08, 0.05))
        val tracked = assertNotNull(PreviewAnalyzer.trackExpectedRect(redFrame, old, Patch.RED, white))
        assertTrue(abs(tracked.left - moved.left) < 0.06, "$tracked")
        assertTrue(abs(tracked.top - moved.top) < 0.06, "$tracked")
        assertTrue(PreviewAnalyzer.matchesExpected(PreviewAnalyzer.sample(redFrame, tracked), Patch.RED, white))
    }

    @Test fun rejectsWrongPatchWhileWaitingForExpectedColor() {
        val tv = NormalizedRect(0.20, 0.22, 0.80, 0.76)
        val white = Rgb(0.90, 0.90, 0.90)
        val greenFrame = frame(tv, Rgb(0.05, 0.88, 0.07))
        assertTrue(!PreviewAnalyzer.matchesExpected(PreviewAnalyzer.sample(greenFrame, tv), Patch.RED, white))
    }

    @Test fun recognizesFinalBlackOnlyAfterScreenIsDark() {
        val tv = NormalizedRect(0.20, 0.22, 0.80, 0.76)
        val white = Rgb(0.90, 0.90, 0.90)
        val blackFrame = frame(tv, Rgb(0.012, 0.012, 0.012), Rgb(0.08, 0.08, 0.08))
        assertTrue(PreviewAnalyzer.matchesExpected(PreviewAnalyzer.sample(blackFrame, tv), Patch.BLACK, white))
    }
}
