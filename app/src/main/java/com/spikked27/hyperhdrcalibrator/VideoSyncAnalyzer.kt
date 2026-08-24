package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Preview-only geometry and synchronization analysis.
 *
 * Beta 9.2 deliberately separates three ideas that Beta 9 mixed together:
 *  1. the CAMERA frame may be portrait or landscape,
 *  2. a TV is *usually close to* 16:9 in image-space, but perspective can change its bounding box,
 *  3. once the user accepts a detected TV border the on-screen guide is frozen; the user keeps the
 *     TV inside that guide instead of the app continuously changing its shape underneath them.
 *
 * Actual calibration colors still come from CameraSampler RAW_SENSOR/YUV spatial measurements.
 */
object VideoSyncAnalyzer {
    const val TV_ASPECT = 16.0 / 9.0
    private const val MIN_HALO_CONTRAST = 0.070

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
     * Find a dark TV-like rectangle surrounded by the WHITE HyperHDR wall halo.
     *
     * 16:9 is only a scoring prior. We intentionally evaluate a broad range of apparent aspect
     * ratios so portrait use, perspective, slight keystone, bezels, and off-axis framing do not make
     * a legitimate TV impossible to select.
     */
    fun detectBlackTvWithHalo(frame: PreviewFrame): NormalizedRect? {
        val luma = DoubleArray(frame.width * frame.height) { i -> luminance(frame.pixels[i]) }
        val integral = Integral(frame.width, frame.height, luma)
        var best: PixelRect? = null
        var bestScore = Double.NEGATIVE_INFINITY

        val minWidth = (frame.width * 0.30).toInt().coerceAtLeast(28)
        val maxWidth = (frame.width * 0.95).toInt().coerceAtLeast(minWidth)
        val widthStep = max(5, frame.width / 32)
        val xStep = max(4, frame.width / 42)
        val yStep = max(4, frame.height / 60)
        val aspectCandidates = doubleArrayOf(1.38, 1.55, TV_ASPECT, 2.02, 2.22)

        var w = minWidth
        while (w <= maxWidth) {
            for (aspect in aspectCandidates) {
                val h = (w / aspect).toInt().coerceAtLeast(14)
                if (h >= frame.height * 0.78 || h < 14) continue

                val xMax = frame.width - w
                val yMin = (frame.height * 0.04).toInt()
                val yMax = (frame.height * 0.96).toInt() - h
                if (xMax < 0 || yMax < yMin) continue

                var y = yMin
                while (y <= yMax) {
                    var x = 0
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

                        val insetX = max(2, (w * 0.06).toInt())
                        val insetY = max(2, (h * 0.07).toInt())
                        val screen = PixelRect(
                            inner.left + insetX,
                            inner.top + insetY,
                            inner.right - insetX,
                            inner.bottom - insetY,
                        )
                        val screenMean = integral.mean(screen)
                        val haloMean = integral.ringMean(outer, inner)
                        val contrast = haloMean - screenMean
                        if (contrast < MIN_HALO_CONTRAST || haloMean < 0.08) {
                            x += xStep
                            continue
                        }

                        val edge = edgeContrast(integral, inner)
                        val cx = (inner.left + inner.right) * 0.5 / frame.width
                        val cy = (inner.top + inner.bottom) * 0.5 / frame.height
                        val centerPenalty = sqrt((cx - 0.5) * (cx - 0.5) + (cy - 0.5) * (cy - 0.5))
                        val areaBonus = inner.area.toDouble() / (frame.width * frame.height)
                        val apparentAspect = inner.width.toDouble() / inner.height.coerceAtLeast(1)
                        val aspectPenalty = abs(ln(apparentAspect / TV_ASPECT))

                        val score = contrast * 3.2 + edge * 1.9 + areaBonus * 0.11 -
                            centerPenalty * 0.14 - aspectPenalty * 0.13
                        if (score > bestScore) {
                            bestScore = score
                            best = inner
                        }
                        x += xStep
                    }
                    y += yStep
                }
            }
            w += widthStep
        }

        return best?.let { pixelToNormalized(it, frame.width, frame.height) }
    }

    /**
     * Refine an acquisition candidate before lock. Width and height can both move slightly; 16:9 is
     * a soft score only. Beta 9.2 stops calling this method once the guide is locked.
     */
    fun refineBorder(frame: PreviewFrame, previous: NormalizedRect): NormalizedRect {
        val luma = DoubleArray(frame.width * frame.height) { i -> luminance(frame.pixels[i]) }
        val integral = Integral(frame.width, frame.height, luma)
        val prior = normalizedToPixel(previous, frame.width, frame.height)
        val centerX = (prior.left + prior.right) / 2
        val centerY = (prior.top + prior.bottom) / 2
        var best = prior
        var bestScore = localBorderScore(integral, prior, 0.0, 0.0)

        val maxDx = max(4, (prior.width * 0.12).toInt())
        val maxDy = max(4, (prior.height * 0.16).toInt())
        val moveStep = max(2, frame.width / 100)
        val widthScales = doubleArrayOf(0.92, 0.97, 1.0, 1.03, 1.08)
        val heightScales = doubleArrayOf(0.92, 0.97, 1.0, 1.03, 1.08)

        for (ws in widthScales) for (hs in heightScales) {
            val w = (prior.width * ws).toInt().coerceAtLeast(24)
            val h = (prior.height * hs).toInt().coerceAtLeast(14)
            var dy = -maxDy
            while (dy <= maxDy) {
                var dx = -maxDx
                while (dx <= maxDx) {
                    val candidate = centeredRect(centerX + dx, centerY + dy, w, h, frame.width, frame.height)
                    val nx = dx.toDouble() / prior.width.coerceAtLeast(1)
                    val ny = dy.toDouble() / prior.height.coerceAtLeast(1)
                    val shift = sqrt(nx * nx + ny * ny)
                    val scaleChange = abs(ws - 1.0) + abs(hs - 1.0)
                    val score = localBorderScore(integral, candidate, shift, scaleChange)
                    if (score > bestScore) {
                        bestScore = score
                        best = candidate
                    }
                    dx += moveStep
                }
                dy += moveStep
            }
        }
        return pixelToNormalized(best, frame.width, frame.height)
    }

    private fun localBorderScore(integral: Integral, r: PixelRect, shift: Double, scaleChange: Double): Double {
        if (r.width < 12 || r.height < 8) return Double.NEGATIVE_INFINITY
        val apparentAspect = r.width.toDouble() / r.height.coerceAtLeast(1)
        val aspectPenalty = abs(ln(apparentAspect / TV_ASPECT))
        return edgeContrast(integral, r) - shift * 0.045 - scaleChange * 0.025 - aspectPenalty * 0.018
    }

    /**
     * Decode the 4 paired black/white marker bits. The guide is frozen during calibration, so the
     * decoder searches a small neighborhood around the guide's expected top-left marker location.
     * This tolerates ordinary hand motion while still letting the user visually keep the TV inside
     * the green box.
     */
    fun decodeMarker(frame: PreviewFrame, tv: NormalizedRect): MarkerReading? {
        val locked = normalizedToPixel(tv, frame.width, frame.height)
        if (locked.width < 44 || locked.height < 22) return null

        val luma = DoubleArray(frame.width * frame.height) { i -> luminance(frame.pixels[i]) }
        val integral = Integral(frame.width, frame.height, luma)
        var best: MarkerReading? = null

        val dxFractions = doubleArrayOf(-0.055, -0.0275, 0.0, 0.0275, 0.055)
        val dyFractions = doubleArrayOf(-0.070, -0.035, 0.0, 0.035, 0.070)
        val scaleFractions = doubleArrayOf(0.94, 1.0, 1.06)

        for (scale in scaleFractions) {
            val w = (locked.width * scale).toInt().coerceAtLeast(44)
            val h = (locked.height * scale).toInt().coerceAtLeast(22)
            val baseCx = (locked.left + locked.right) / 2
            val baseCy = (locked.top + locked.bottom) / 2
            for (dyf in dyFractions) for (dxf in dxFractions) {
                val candidate = centeredRect(
                    baseCx + (locked.width * dxf).toInt(),
                    baseCy + (locked.height * dyf).toInt(),
                    w,
                    h,
                    frame.width,
                    frame.height,
                )
                val reading = decodeMarkerAt(integral, candidate) ?: continue
                if (best == null || reading.confidence > best!!.confidence) best = reading
            }
        }
        return best
    }

    private fun decodeMarkerAt(integral: Integral, px: PixelRect): MarkerReading? {
        val markerLeft = px.left + (px.width * 0.055).toInt()
        val markerTop = px.top + (px.height * 0.055).toInt()
        val markerWidth = (px.width * 0.50).toInt().coerceAtLeast(28)
        val markerHeight = (px.height * 0.125).toInt().coerceAtLeast(4)
        val pairWidth = markerWidth / 4.0

        val bits = IntArray(4)
        val confidences = DoubleArray(4)
        for (pair in 0 until 4) {
            val x0 = (markerLeft + pair * pairWidth).toInt()
            val x1 = (markerLeft + (pair + 0.5) * pairWidth).toInt()
            val x2 = (markerLeft + (pair + 1.0) * pairWidth).toInt()
            val y0 = markerTop
            val y1 = (markerTop + markerHeight).coerceAtMost(integral.height)
            if (x0 < 0 || x2 > integral.width || x1 <= x0 || y0 < 0 || y1 <= y0) return null
            val a = integral.mean(PixelRect(x0, y0, x1, y1))
            val b = integral.mean(PixelRect(x1, y0, x2, y1))
            val d = a - b
            bits[pair] = if (d > 0) 1 else 0
            confidences[pair] = abs(d)
        }

        if (bits[0] != 1 || confidences[0] < 0.10) return null
        val weakestData = confidences.drop(1).minOrNull() ?: 0.0
        val confidence = confidences.average()
        if (confidence < 0.085 || weakestData < 0.060) return null
        val step = bits[1] or (bits[2] shl 1) or (bits[3] shl 2)
        return MarkerReading(step, confidence.coerceIn(0.0, 1.0))
    }

    /** Retained for old tests/rollback code; Beta 9.2 acquisition does not force this shape. */
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
        left = left.coerceAtLeast(0)
        top = top.coerceAtLeast(0)
        return PixelRect(left, top, right.coerceAtMost(maxW), bottom.coerceAtMost(maxH))
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
