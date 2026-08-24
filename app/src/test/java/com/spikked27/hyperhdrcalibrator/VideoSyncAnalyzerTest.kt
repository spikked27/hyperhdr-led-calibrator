package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoSyncAnalyzerTest {
    @Test
    fun `finds perspective TV in portrait frame using 16 by 9 only as a prior`() {
        val width = 180
        val height = 320
        val pixels = MutableList(width * height) { Rgb(0.62, 0.62, 0.62) }
        // Apparent aspect is ~1.64 rather than exactly 1.777 because the phone can be off-axis.
        fill(pixels, width, 18, 104, 162, 192, Rgb(0.015, 0.015, 0.015))
        val frame = PreviewFrame(width, height, pixels)

        val rect = assertNotNull(VideoSyncAnalyzer.detectBlackTvWithHalo(frame))
        val pixelAspect = rect.width * width / (rect.height * height)
        assertTrue(pixelAspect in 1.30..2.25)
        assertTrue(abs((rect.left + rect.right) / 2.0 - 0.5) < 0.12)
        assertTrue(abs((rect.top + rect.bottom) / 2.0 - 0.4625) < 0.15)
    }

    @Test
    fun `decodes every marker in portrait analysis frame`() {
        for (step in 0..7) {
            val width = 180
            val height = 320
            val tv = NormalizedRect(0.10, 0.32, 0.90, 0.60)
            val base = patchBase(step)
            val pixels = MutableList(width * height) { Rgb(0.12, 0.12, 0.12) }
            fillNormalized(pixels, width, height, tv, base)
            paintMarker(pixels, width, height, tv, step)
            val reading = assertNotNull(VideoSyncAnalyzer.decodeMarker(PreviewFrame(width, height, pixels), tv))
            assertEquals(step, reading.step)
            assertTrue(reading.confidence > 0.20)
        }
    }

    @Test
    fun `frozen guide still decodes marker after small handheld shift`() {
        val width = 180
        val height = 320
        val lockedGuide = NormalizedRect(0.10, 0.32, 0.90, 0.60)
        val actualTv = NormalizedRect(0.125, 0.333, 0.925, 0.613)
        val pixels = MutableList(width * height) { Rgb(0.10, 0.10, 0.10) }
        fillNormalized(pixels, width, height, actualTv, patchBase(1))
        paintMarker(pixels, width, height, actualTv, 1)

        val reading = assertNotNull(VideoSyncAnalyzer.decodeMarker(PreviewFrame(width, height, pixels), lockedGuide))
        assertEquals(1, reading.step)
        assertTrue(reading.confidence > 0.12)
    }

    @Test
    fun `legacy snap helper remains physical 16 by 9`() {
        val frame = PreviewFrame(320, 180, List(320 * 180) { Rgb(0.0, 0.0, 0.0) })
        val snapped = VideoSyncAnalyzer.snapTo16By9(frame, NormalizedRect(0.19, 0.21, 0.83, 0.79))
        val aspect = snapped.width * frame.width / (snapped.height * frame.height)
        assertTrue(abs(aspect - 16.0 / 9.0) < 0.03)
    }

    private fun patchBase(step: Int): Rgb = when (step) {
        0 -> Rgb(0.92, 0.92, 0.92)
        1 -> Rgb(0.85, 0.04, 0.03)
        2 -> Rgb(0.03, 0.85, 0.04)
        3 -> Rgb(0.03, 0.05, 0.85)
        4 -> Rgb(0.03, 0.82, 0.82)
        5 -> Rgb(0.82, 0.03, 0.82)
        6 -> Rgb(0.82, 0.82, 0.03)
        else -> Rgb(0.01, 0.01, 0.01)
    }

    private fun paintMarker(pixels: MutableList<Rgb>, width: Int, height: Int, tv: NormalizedRect, step: Int) {
        val left = (tv.left * width).toInt()
        val top = (tv.top * height).toInt()
        val tvW = (tv.width * width).toInt()
        val tvH = (tv.height * height).toInt()
        val markerLeft = left + (tvW * 0.055).toInt()
        val markerTop = top + (tvH * 0.055).toInt()
        val markerWidth = (tvW * 0.50).toInt().coerceAtLeast(28)
        val markerHeight = (tvH * 0.125).toInt().coerceAtLeast(4)
        val bits = intArrayOf(1, step and 1, (step shr 1) and 1, (step shr 2) and 1)
        for (pair in 0 until 4) {
            val x0 = (markerLeft + pair * markerWidth / 4.0).toInt()
            val x1 = (markerLeft + (pair + 0.5) * markerWidth / 4.0).toInt()
            val x2 = (markerLeft + (pair + 1.0) * markerWidth / 4.0).toInt()
            val first = if (bits[pair] == 1) Rgb(1.0, 1.0, 1.0) else Rgb(0.0, 0.0, 0.0)
            val second = if (bits[pair] == 1) Rgb(0.0, 0.0, 0.0) else Rgb(1.0, 1.0, 1.0)
            fill(pixels, width, x0, markerTop, x1, markerTop + markerHeight, first)
            fill(pixels, width, x1, markerTop, x2, markerTop + markerHeight, second)
        }
    }

    private fun fillNormalized(pixels: MutableList<Rgb>, width: Int, height: Int, rect: NormalizedRect, color: Rgb) {
        fill(
            pixels,
            width,
            (rect.left * width).toInt(),
            (rect.top * height).toInt(),
            (rect.right * width).toInt(),
            (rect.bottom * height).toInt(),
            color,
        )
    }

    private fun fill(pixels: MutableList<Rgb>, width: Int, left: Int, top: Int, right: Int, bottom: Int, color: Rgb) {
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(pixels.size / width)) {
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) pixels[y * width + x] = color
        }
    }
}
