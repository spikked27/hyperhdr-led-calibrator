package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoSyncAnalyzerTest {
    @Test
    fun `finds black 16 by 9 TV inside bright wall halo`() {
        val width = 320
        val height = 180
        val pixels = MutableList(width * height) { Rgb(0.62, 0.62, 0.62) }
        fill(pixels, width, 62, 36, 258, 146, Rgb(0.015, 0.015, 0.015))
        val frame = PreviewFrame(width, height, pixels)

        val rect = assertNotNull(VideoSyncAnalyzer.detectBlackTvWithHalo(frame))
        val pixelAspect = rect.width * width / (rect.height * height)
        assertTrue(abs(pixelAspect - 16.0 / 9.0) < 0.08)
        assertTrue(abs((rect.left + rect.right) / 2.0 - 0.5) < 0.08)
    }

    @Test
    fun `decodes every beta 9 marker independent of patch color`() {
        for (step in 0..7) {
            val width = 320
            val height = 180
            val tv = NormalizedRect(0.18, 0.18, 0.82, 0.82)
            val base = when (step) {
                0 -> Rgb(0.92, 0.92, 0.92)
                1 -> Rgb(0.85, 0.04, 0.03)
                2 -> Rgb(0.03, 0.85, 0.04)
                3 -> Rgb(0.03, 0.05, 0.85)
                4 -> Rgb(0.03, 0.82, 0.82)
                5 -> Rgb(0.82, 0.03, 0.82)
                6 -> Rgb(0.82, 0.82, 0.03)
                else -> Rgb(0.01, 0.01, 0.01)
            }
            val pixels = MutableList(width * height) { Rgb(0.12, 0.12, 0.12) }
            fillNormalized(pixels, width, height, tv, base)
            paintMarker(pixels, width, height, tv, step)
            val reading = assertNotNull(VideoSyncAnalyzer.decodeMarker(PreviewFrame(width, height, pixels), tv))
            assertEquals(step, reading.step)
            assertTrue(reading.confidence > 0.20)
        }
    }

    @Test
    fun `snap result is physical 16 by 9 in image pixels`() {
        val frame = PreviewFrame(320, 180, List(320 * 180) { Rgb(0.0, 0.0, 0.0) })
        val snapped = VideoSyncAnalyzer.snapTo16By9(frame, NormalizedRect(0.19, 0.21, 0.83, 0.79))
        val aspect = snapped.width * frame.width / (snapped.height * frame.height)
        assertTrue(abs(aspect - 16.0 / 9.0) < 0.03)
    }

    private fun paintMarker(pixels: MutableList<Rgb>, width: Int, height: Int, tv: NormalizedRect, step: Int) {
        val left = (tv.left * width).toInt()
        val top = (tv.top * height).toInt()
        val tvW = (tv.width * width).toInt()
        val tvH = (tv.height * height).toInt()
        val markerLeft = left + (tvW * 0.055).toInt()
        val markerTop = top + (tvH * 0.055).toInt()
        val markerWidth = (tvW * 0.50).toInt().coerceAtLeast(32)
        val markerHeight = (tvH * 0.125).toInt().coerceAtLeast(5)
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
        for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
    }
}