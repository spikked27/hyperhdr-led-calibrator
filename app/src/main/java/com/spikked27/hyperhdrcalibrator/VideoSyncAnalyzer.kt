package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Preview-only analysis for Beta 9.
 *
 * Calibration color values never come from this path; CameraSampler RAW_SENSOR/YUV spatial
 * measurements remain the source of calibration data. This analyzer only does three jobs:
 *  1. find the physical 16:9 TV border while the TV is black and the backlight is white,
 *  2. keep the fitted border attached to the TV while the phone moves, and
 *  3. decode the explicit machine-readable step marker embedded in the companion video.
 *
 * Using a marker instead of inferring video state from RGB removes camera white-balance/exposure
 * from the synchronization problem. A missed frame can therefore resynchronize on the next frame.
 */
object VideoSyncAnalyzer {
    const val TV_ASPECT = 16.0 / 9.0
    private const val MIN_HALO_CONTRAST = 0.075

    data class MarkerReading(
        val step: Int,
        val confidence: Double,
    )

    private data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Int get() = width * height
    }

    /**
     * Finds a dark 16:9 rectangle surrounded by a brighter halo. This is deliberately used before
     * the video starts: the TV is paused on black and HyperHDR is commanded to white, creating a
     * strong screen-vs-wall boundary even for an OLED on a dark wall.
     */
    fun detectBlackTvWithHalo(frame: PreviewFrame): NormalizedRect? {
        val luma = DoubleArray(frame.width * frame.height) { i -> luminance(frame.pixels[i]) }
        val integral = Integral(frame.width, frame.height, luma)
        var best: PixelRect? = null
        var bestScore = Double.NEGATIVE_INFINITY

        val minWidth = (frame.width * 0.30).toInt().coerceAtLeast(24)
        val maxWidth = (frame.width * 0.94).toInt().coerceAtLeast(minWidth)
        val widthStep = max(4, frame.width / 35)
        val xStep = max(3, frame.width / 55)
        val yStep = max(3, frame.height / 80)

        var w = minWidth
        while (w <= maxWidth) {
            val h = (w / TV_ASPECT).toInt().coerceAtLeast(12)
            if (h >= frame.height * 0.72) {
                w += widthStep
                continue
            }
            val xMin = 0
            val xMax = frame.width - w
            val yMin = (frame.height * 0.08).toInt()
            val yMax = (frame.height * 0.92).toInt() - h
            var y = yMin
            while (y <= yMax) {
                var x = xMin
                while (x <= xMax) {
                    val inner = PixelRect(x, y, x + w, y + h)
                    val marginX = max(3, (w * 0.08).toInt())
                    val marginY = max(3, (h * 0.13).toInt())
                    val outer = PixelRect(
                        (inner.left - marginX).coerceAtLeast(0),
                        (inner.top - marginY).coerceAtLeast(0),
                        (inner.right + marginX).coerceAtMost(frame.width),
                        (inner.bottom + marginY).coerceAtMost(frame.height),
                    )
                    if (outer.area <= inner.area) {
                        x += xStep
                        continue
                    }

                    // Avoid the bezel/halo itself by looking slightly inside the candidate screen.
                    val insetX = max(2, (w * 0.06).toInt())
                    val insetY = max(2, (h * 0.07).toInt())
                    val screen = PixelRect(
                        inner.left + insetX,
                        inner.top + insetY,
                        inner.right - insetX,
                        inner.bottom - insetY,
                    )
                    val screenMean = integral.mean(screen)
                    val outerMean = integral.ringMean(outer, inner)
                    val contrast = outerMean - screenMean
                    if (contrast < MIN_HALO_CONTRAST || outerMean < 0.09) {
                        x += xStep
                        continue
                    }

                    val edge = edgeContrast(integral, inner)
                    val cx = (inner.left + inner.right) * 0.5 / frame.width
                    val cy = (inner.top + inner.bottom) * 0.5 / frame.height
                    val centerPenalty = sqrt((cx - 0.5) * (cx - 0.5) + (cy - 0.50) * (cy - 0.50))
                    val areaBonus = inner.area.toDouble() / (frame.width * frame.height)
                    val score = contrast * 3.0 + edge * 1.8 + areaBonus * 0.10 - centerPenalty * 0.18
                    if (score > bestScore) {
                        bestScore = score
                        best = inner
                    }
                    x += xStep
                }
                y += yStep
            }
            w += widthStep
        }

        val rect = best ?: return null
        return pixelToNormalized(rect, frame.width, frame.height)
    }

    /**
     * Refines an already-known TV rectangle using local edge contrast while enforcing a physical
     * 16:9 screen shape. The returned overlay therefore snaps to the TV border instead of following
     * an arbitrary same-colored blob.
     */
    fun refineBorder(frame: PreviewFrame, previous: NormalizedRect): NormalizedRect {
        val luma = DoubleArray(frame.width * frame.height) { i -> luminance(frame.pixels[i]) }
        val integral = Integral(frame.width, frame.height, luma)
        val prior = normalizedToPixel(previous, frame.width, frame.height)
        val centerX = (prior.left + prior.right) / 2
        val centerY = (prior.top + prior.bottom) / 2
        var best = snapPixelTo16By9(prior, frame.width, frame.height)
        var bestScore = edgeContrast(integral, best)

        val baseWidth = best.width
        for (scalePct in 90..110 step 4) {
            val w = (baseWidth * scalePct / 100.0).toInt().coerceAtLeast(24)
            val h = (w / TV_ASPECT).toInt().coerceAtLeast(12)
            val maxDx = max(5, (prior.width * 0.16).toInt())
            val maxDy = max(5, (prior.height * 0.22).toInt())
            val step = max(2, frame.width / 90)
            var dy = -maxDy
            while (dy <= maxDy) {
                var dx = -maxDx
                while (dx <= maxDx) {
                    val candidate = centeredRect(centerX + dx, centerY + dy, w, h, frame.width, frame.height)
                    val edge = edgeContrast(integral, candidate)
                    val centerShift = sqrt(
                        (dx.toDouble() / prior.width.coerceAtLeast(1)).let { it * it } +
                            (dy.toDouble() / prior.height.coerceAtLeast(1)).let { it * it }
                    )
                    val scaleShift = abs(candidate.width - prior.width).toDouble() / prior.width.coerceAtLeast(1)
                    val score = edge - centerShift * 0.045 - scaleShift * 0.035
                    if (score > bestScore) {
                        bestScore = score
                        best = candidate
                    }
                    dx += step
                }
                dy += step
            }
        }
        return pixelToNormalized(best, frame.width, frame.height)
    }

    /**
     * Decode the 4 Manchester-like marker pairs in the top-left safe edge of the TV.
     * Pair 0 is a fixed orientation/sync bit (1). Pairs 1..3 encode step 0..7 LSB first.
     * Every bit contains one black and one white half, so the code remains readable on every patch,
     * including WHITE and BLACK, and is independent of camera white balance.
     */
    fun decodeMarker(frame: PreviewFrame, tv: NormalizedRect): MarkerReading? {
        val px = normalizedToPixel(tv, frame.width, frame.height)
        if (px.width < 48 || px.height < 24) return null

        val markerLeft = px.left + (px.width * 0.055).toInt()
        val markerTop = px.top + (px.height * 0.055).toInt()
        val markerWidth = (px.width * 0.50).toInt().coerceAtLeast(32)
        val markerHeight = (px.height * 0.125).toInt().coerceAtLeast(5)
        val pairWidth = markerWidth / 4.0
        val luma = DoubleArray(frame.width * frame.height) { i -> luminance(frame.pixels[i]) }
        val integral = Integral(frame.width, frame.height, luma)

        val bits = IntArray(4)
        val confidences = DoubleArray(4)
        for (pair in 0 until 4) {
            val x0 = (markerLeft + pair * pairWidth).toInt()
            val x1 = (markerLeft + (pair + 0.5) * pairWidth).toInt()
            val x2 = (markerLeft + (pair + 1.0) * pairWidth).toInt()
            val y0 = markerTop
            val y1 = (markerTop + markerHeight).coerceAtMost(frame.height)
            if (x2 > frame.width || x1 <= x0 || y1 <= y0) return null
            val a = integral.mean(PixelRect(x0, y0, x1, y1))
            val b = integral.mean(PixelRect(x1, y0, x2, y1))
            val d = a - b
            bits[pair] = if (d > 0) 1 else 0
            confidences[pair] = abs(d)
        }

        // The first pair is always WHITE|BLACK. It protects against false decoding from content.
        if (bits[0] != 1 || confidences[0] < 0.12) return null
        val confidence = confidences.average()
        if (confidence < 0.095 || confidences.drop(1).minOrNull() ?: 0.0 < 0.07) return null
        val step = bits[1] or (bits[2] shl 1) or (bits[3] shl 2)
        return MarkerReading(step, confidence.coerceIn(0.0, 1.0))
    }

    /** Returns a 16:9 rectangle with the same center and roughly the same area. */
    fun snapTo16By9(frame: PreviewFrame, rect: NormalizedRect): NormalizedRect {
        return pixelToNormalized(
            snapPixelTo16By9(normalizedToPixel(rect, frame.width, frame.height), frame.width, frame.height),
            frame.width,
            frame.height,
        )
    }

    private fun edgeContrast(integral: Integral, r: PixelRect): Double {
        if (r.width < 12 || r.height < 8) return 0.0
        val t = max(2, minOf(r.width, r.height) / 18)
        val outer = PixelRect(
            (r.left - t).coerceAtLeast(0),
            (r.top - t).coerceAtLeast(0),
            (r.right + t).coerceAtMost(integral.width),
            (r.bottom + t).coerceAtMost(integral.height),
        )
        val inner = PixelRect(
            (r.left + t).coerceAtMost(r.right - 1),
            (r.top + t).coerceAtMost(r.bottom - 1),
            (r.right - t).coerceAtLeast(r.left + 1),
            (r.bottom - t).coerceAtLeast(r.top + 1),
        )
        if (inner.area <= 0 || outer.area <= r.area) return 0.0
        val insideEdge = integral.ringMean(r, inner)
        val outsideEdge = integral.ringMean(outer, r)
        return abs(outsideEdge - insideEdge)
    }

    private fun centeredRect(cx: Int, cy: Int, width: Int, height: Int, maxW: Int, maxH: Int): PixelRect {
        var left = cx - width / 2
        var top = cy - height / 2
        var right = left + width
        var bottom = top + height
        if (left < 0) { right -= left; left = 0 }
        if (top < 0) { bottom -= top; top = 0 }
        if (right > maxW) { left -= right - maxW; right = maxW }
        if (bottom > maxH) { top -= bottom - maxH; bottom = maxH }
        return PixelRect(left.coerceAtLeast(0), top.coerceAtLeast(0), right, bottom)
    }

    private fun snapPixelTo16By9(r: PixelRect, maxW: Int, maxH: Int): PixelRect {
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        val area = r.area.toDouble().coerceAtLeast(1.0)
        var w = sqrt(area * TV_ASPECT).toInt().coerceAtLeast(16)
        var h = (w / TV_ASPECT).toInt().coerceAtLeast(9)
        if (w > maxW) { w = maxW; h = (w / TV_ASPECT).toInt() }
        if (h > maxH) { h = maxH; w = (h * TV_ASPECT).toInt() }
        return centeredRect(cx, cy, w, h, maxW, maxH)
    }

    private fun normalizedToPixel(r: NormalizedRect, width: Int, height: Int) = PixelRect(
        (r.left * width).toInt().coerceIn(0, width - 1),
        (r.top * height).toInt().coerceIn(0, height - 1),
        (r.right * width).toInt().coerceIn(1, width),
        (r.bottom * height).toInt().coerceIn(1, height),
    )

    private fun pixelToNormalized(r: PixelRect, width: Int, height: Int) = NormalizedRect(
        r.left.toDouble() / width,
        r.top.toDouble() / height,
        r.right.toDouble() / width,
        r.bottom.toDouble() / height,
    )

    private fun luminance(c: Rgb) = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b

    private class Integral(val width: Int, val height: Int, values: DoubleArray) {
        private val stride = width + 1
        private val data = DoubleArray((width + 1) * (height + 1))

        init {
            for (y in 0 until height) {
                var row = 0.0
                for (x in 0 until width) {
                    row += values[y * width + x]
                    data[(y + 1) * stride + (x + 1)] = data[y * stride + (x + 1)] + row
                }
            }
        }

        fun sum(r: PixelRect): Double {
            val l = r.left.coerceIn(0, width)
            val rr = r.right.coerceIn(l, width)
            val t = r.top.coerceIn(0, height)
            val b = r.bottom.coerceIn(t, height)
            return data[b * stride + rr] - data[t * stride + rr] - data[b * stride + l] + data[t * stride + l]
        }

        fun mean(r: PixelRect): Double = if (r.area <= 0) 0.0 else sum(r) / r.area

        fun ringMean(outer: PixelRect, inner: PixelRect): Double {
            val area = outer.area - inner.area
            return if (area <= 0) 0.0 else (sum(outer) - sum(inner)) / area
        }
    }
}