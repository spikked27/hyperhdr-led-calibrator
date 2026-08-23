package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.math.sqrt

data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y >= top && y <= bottom
    fun inset(fraction: Double) = NormalizedRect(
        left + width * fraction,
        top + height * fraction,
        right - width * fraction,
        bottom - height * fraction,
    )
    fun expand(xFraction: Double, yFraction: Double) = NormalizedRect(
        (left - width * xFraction).coerceAtLeast(0.0),
        (top - height * yFraction).coerceAtLeast(0.0),
        (right + width * xFraction).coerceAtMost(1.0),
        (bottom + height * yFraction).coerceAtMost(1.0),
    )
}

data class SpatialFrame(
    val columns: Int,
    val rows: Int,
    val tiles: List<Rgb>,
    val clippedFraction: Double = 0.0,
) {
    init { require(columns > 0 && rows > 0 && tiles.size == columns * rows) }
    operator fun get(x: Int, y: Int): Rgb = tiles[y * columns + x]
}

data class WallReferenceModel(
    val columns: Int,
    val rows: Int,
    val tileIndices: List<Int>,
    val whiteSignals: List<Rgb>,
    val brightnessGradient: Double,
)

data class WallColorResult(
    val rgb: Rgb,
    val tilesUsed: Int,
    val availableTiles: Int,
    val brightnessGradient: Double,
    val chromaSpread: Double,
)

object SpatialCalibration {
    fun medianCombine(frames: List<SpatialFrame>): SpatialFrame {
        require(frames.isNotEmpty())
        val columns = frames.first().columns
        val rows = frames.first().rows
        require(frames.all { it.columns == columns && it.rows == rows })
        val tiles = (0 until columns * rows).map { i ->
            Rgb(
                median(frames.map { it.tiles[i].r }),
                median(frames.map { it.tiles[i].g }),
                median(frames.map { it.tiles[i].b }),
            )
        }
        return SpatialFrame(columns, rows, tiles, median(frames.map { it.clippedFraction }))
    }

    fun detectTvRect(white: SpatialFrame): NormalizedRect {
        val luma = white.tiles.map(::luminance)
        val border = mutableListOf<Double>()
        for (y in 0 until white.rows) for (x in 0 until white.columns) {
            if (x < white.columns / 6 || x >= white.columns * 5 / 6 || y < white.rows / 6 || y >= white.rows * 5 / 6) {
                border += luma[y * white.columns + x]
            }
        }
        val background = median(border)
        val centerValues = mutableListOf<Double>()
        for (y in white.rows / 4 until white.rows * 3 / 4) for (x in white.columns / 4 until white.columns * 3 / 4) {
            centerValues += luma[y * white.columns + x]
        }
        val foreground = percentile(centerValues, 0.90)
        require(foreground > background + 0.02) {
            "Could not distinguish the bright TV from the surrounding wall. Show the WHITE patch and keep the entire TV plus some wall visible."
        }

        val threshold = background + (foreground - background) * 0.42
        val bright = BooleanArray(white.columns * white.rows) { luma[it] >= threshold }
        val centerX = white.columns / 2
        val centerY = white.rows / 2
        var seed = centerY * white.columns + centerX
        if (!bright[seed]) {
            var bestDistance = Int.MAX_VALUE
            for (y in 0 until white.rows) for (x in 0 until white.columns) {
                val i = y * white.columns + x
                if (!bright[i]) continue
                val d = abs(x - centerX) + abs(y - centerY)
                if (d < bestDistance) { bestDistance = d; seed = i }
            }
        }
        require(bright[seed]) { "Could not find the TV near the center of the frame." }

        val visited = BooleanArray(bright.size)
        val queue = ArrayDeque<Int>()
        queue += seed
        visited[seed] = true
        var minX = white.columns
        var minY = white.rows
        var maxX = -1
        var maxY = -1
        var count = 0
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (!bright[i]) continue
            val x = i % white.columns
            val y = i / white.columns
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            count++
            val neighbors = intArrayOf(i - 1, i + 1, i - white.columns, i + white.columns)
            for (j in neighbors) {
                if (j < 0 || j >= bright.size || visited[j]) continue
                val jx = j % white.columns
                val jy = j / white.columns
                if (abs(jx - x) + abs(jy - y) != 1) continue
                visited[j] = true
                if (bright[j]) queue += j
            }
        }

        val area = count.toDouble() / bright.size
        require(area in 0.06..0.88 && maxX > minX + 2 && maxY > minY + 2) {
            "TV detection was not reliable. Keep the full TV visible with a border of wall around it, then retry WHITE."
        }

        val rect = NormalizedRect(
            left = minX.toDouble() / white.columns,
            top = minY.toDouble() / white.rows,
            right = (maxX + 1).toDouble() / white.columns,
            bottom = (maxY + 1).toDouble() / white.rows,
        )
        require(rect.width in 0.20..0.95 && rect.height in 0.12..0.90) { "Detected bright region does not look like a TV." }
        return rect
    }

    fun screenColor(frame: SpatialFrame, tvRect: NormalizedRect): Rgb {
        val values = tilesInside(frame, tvRect.inset(0.16)).map { it.second }
        require(values.size >= 4) { "Not enough sensor tiles inside the detected TV area" }
        return robustRgb(values)
    }

    fun buildWallReference(white: SpatialFrame, black: SpatialFrame, tvRect: NormalizedRect): WallReferenceModel {
        requireSameGrid(white, black)
        val inner = tvRect.expand(0.035, 0.055)
        val outer = tvRect.expand(0.42, 0.62)
        val candidates = mutableListOf<Pair<Int, Rgb>>()
        val luminances = mutableListOf<Double>()

        for (y in 0 until white.rows) for (x in 0 until white.columns) {
            val nx = (x + 0.5) / white.columns
            val ny = (y + 0.5) / white.rows
            if (!outer.contains(nx, ny) || inner.contains(nx, ny)) continue
            val i = y * white.columns + x
            val signal = subtract(white.tiles[i], black.tiles[i])
            val l = luminance(signal)
            if (l > 1e-6) {
                candidates += i to signal
                luminances += l
            }
        }
        require(candidates.size >= 12) { "Not enough illuminated wall is visible around the TV. Frame more wall around the screen." }

        val p90 = percentile(luminances, 0.90)
        val brightEnough = candidates.filter { (_, signal) -> luminance(signal) >= p90 * 0.08 }
        require(brightEnough.size >= 10) { "The reflected LED white signal is too weak across the visible wall." }

        // Remove obvious colored objects/shadows using WHITE only, then lock this exact spatial mask for
        // every LED color. Keeping one mask is important: all colors must see the same geometry.
        val whiteChromas = brightEnough.map { (_, signal) -> chromaticity(signal) }
        val center = robustRgb(whiteChromas)
        val distances = whiteChromas.map { chromaDistance(it, center) }
        val medianDistance = median(distances)
        val mad = median(distances.map { abs(it - medianDistance) }).coerceAtLeast(0.002)
        val retained = brightEnough.filterIndexed { i, _ -> distances[i] <= medianDistance + 4.0 * mad }
            .ifEmpty { brightEnough }
        require(retained.size >= 8) { "Too little neutral wall remains after rejecting colored/shadowed regions." }

        val retainedLuma = retained.map { luminance(it.second) }
        val low = percentile(retainedLuma, 0.10)
        val high = percentile(retainedLuma, 0.90)
        val gradient = if (low <= 1e-9) Double.POSITIVE_INFINITY else high / low

        return WallReferenceModel(
            columns = white.columns,
            rows = white.rows,
            tileIndices = retained.map { it.first },
            whiteSignals = retained.map { it.second },
            brightnessGradient = gradient,
        )
    }

    fun wallColor(frame: SpatialFrame, black: SpatialFrame, model: WallReferenceModel): WallColorResult {
        require(frame.columns == model.columns && frame.rows == model.rows)
        requireSameGrid(frame, black)
        require(model.tileIndices.size == model.whiteSignals.size)

        val ratios = mutableListOf<Rgb>()
        for (n in model.tileIndices.indices) {
            val i = model.tileIndices[n]
            val signal = subtract(frame.tiles[i], black.tiles[i])
            val white = model.whiteSignals[n]
            if (white.r <= 1e-6 || white.g <= 1e-6 || white.b <= 1e-6) continue
            ratios += Rgb(signal.r / white.r, signal.g / white.g, signal.b / white.b)
        }
        require(ratios.size >= 8) { "Too few wall tiles had enough signal for this LED color." }

        // Channel-wise division by the WHITE measurement at the same tile cancels the wall's brightness
        // falloff, lens shading, much of the wall reflectance, and the different TV-vs-wall exposure.
        // We then reject only chromatic outliers; brightness gradient itself is not an error condition.
        val center = robustRgb(ratios)
        val centerChroma = chromaticity(center)
        val distances = ratios.map { chromaDistance(chromaticity(it), centerChroma) }
        val medianDistance = median(distances)
        val mad = median(distances.map { abs(it - medianDistance) }).coerceAtLeast(0.002)
        val filtered = ratios.filterIndexed { i, _ -> distances[i] <= medianDistance + 4.0 * mad }
        val used = if (filtered.size >= 8) filtered else ratios
        val result = robustRgb(used)
        val spread = used.maxOfOrNull { chromaDistance(chromaticity(it), chromaticity(result)) } ?: 0.0

        return WallColorResult(
            rgb = result,
            tilesUsed = used.size,
            availableTiles = ratios.size,
            brightnessGradient = model.brightnessGradient,
            chromaSpread = spread,
        )
    }

    private fun requireSameGrid(a: SpatialFrame, b: SpatialFrame) {
        require(a.columns == b.columns && a.rows == b.rows) { "Spatial measurements use different grids" }
    }

    private fun tilesInside(frame: SpatialFrame, rect: NormalizedRect): List<Pair<Int, Rgb>> {
        val result = mutableListOf<Pair<Int, Rgb>>()
        for (y in 0 until frame.rows) for (x in 0 until frame.columns) {
            val nx = (x + 0.5) / frame.columns
            val ny = (y + 0.5) / frame.rows
            if (rect.contains(nx, ny)) result += (y * frame.columns + x) to frame[x, y]
        }
        return result
    }

    private fun subtract(a: Rgb, b: Rgb) = Rgb(
        (a.r - b.r).coerceAtLeast(0.0),
        (a.g - b.g).coerceAtLeast(0.0),
        (a.b - b.b).coerceAtLeast(0.0),
    )

    private fun robustRgb(values: List<Rgb>): Rgb = Rgb(
        median(values.map { it.r }),
        median(values.map { it.g }),
        median(values.map { it.b }),
    )

    private fun chromaticity(v: Rgb): Rgb {
        val sum = (v.r + v.g + v.b).coerceAtLeast(1e-12)
        return Rgb(v.r / sum, v.g / sum, v.b / sum)
    }

    private fun chromaDistance(a: Rgb, b: Rgb): Double = sqrt(
        (a.r - b.r) * (a.r - b.r) +
        (a.g - b.g) * (a.g - b.g) +
        (a.b - b.b) * (a.b - b.b)
    )

    private fun luminance(v: Rgb): Double = (v.r + 2.0 * v.g + v.b) / 4.0

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val i = ((s.lastIndex) * p.coerceIn(0.0, 1.0)).toInt()
        return s[i]
    }
}
