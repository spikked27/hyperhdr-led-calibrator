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
        val c = frames.first().columns
        val r = frames.first().rows
        require(frames.all { it.columns == c && it.rows == r })
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
        val tiles = (0 until c * r).map { i ->
            Rgb(
                median(frames.map { it.tiles[i].r }),
                median(frames.map { it.tiles[i].g }),
                median(frames.map { it.tiles[i].b }),
            )
        }
        return SpatialFrame(c, r, tiles, median(frames.map { it.clippedFraction }))
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
            "Could not distinguish the bright TV from the surrounding wall. Show the WHITE patch and frame the entire screen with some wall visible around it."
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
                if (bright[i]) {
                    val d = abs(x - centerX) + abs(y - centerY)
                    if (d < bestDistance) { bestDistance = d; seed = i }
                }
            }
        }
        require(bright[seed]) { "Could not find the TV in the center of the frame." }

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
            val n = intArrayOf(i - 1, i + 1, i - white.columns, i + white.columns)
            for (j in n) {
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
        val rect = tvRect.inset(0.16)
        val values = tilesInside(frame, rect).map { it.second }
        require(values.size >= 4) { "Not enough sensor tiles inside the detected TV area" }
        return robustRgb(values)
    }

    fun wallColor(frame: SpatialFrame, black: SpatialFrame, tvRect: NormalizedRect): WallColorResult {
        require(frame.columns == black.columns && frame.rows == black.rows)
        val inner = tvRect.expand(0.035, 0.055)
        val outer = tvRect.expand(0.42, 0.62)
        val candidates = mutableListOf<Rgb>()
        val brightness = mutableListOf<Double>()

        for (y in 0 until frame.rows) for (x in 0 until frame.columns) {
            val nx = (x + 0.5) / frame.columns
            val ny = (y + 0.5) / frame.rows
            if (!outer.contains(nx, ny) || inner.contains(nx, ny)) continue
            val a = frame[x, y]
            val b = black[x, y]
            val v = Rgb(
                (a.r - b.r).coerceAtLeast(0.0),
                (a.g - b.g).coerceAtLeast(0.0),
                (a.b - b.b).coerceAtLeast(0.0),
            )
            val l = luminance(v)
            if (l > 1e-6) {
                candidates += v
                brightness += l
            }
        }
        require(candidates.size >= 8) { "Not enough illuminated wall is visible around the TV. Frame more wall around the screen." }

        // Preserve the real spatial gradient for diagnostics, but deliberately remove its magnitude from
        // the color estimate: each tile votes by chromaticity, not by how bright it is. Bright LED hot
        // spots therefore cannot dominate the answer just because they are closer to the strip.
        val high = percentile(brightness, 0.90)
        val usable = candidates.zip(brightness)
            .filter { (_, l) -> l >= high * 0.10 }
            .map { (rgb, _) -> chromaticity(rgb) }
        require(usable.size >= 6) { "The wall signal is too weak compared with the brightest LED area." }

        val first = robustRgb(usable)
        val firstC = chromaticity(first)
        val distances = usable.map { chromaDistance(it, firstC) }
        val medD = median(distances)
        val mad = median(distances.map { abs(it - medD) }).coerceAtLeast(0.002)
        val filtered = usable.filterIndexed { i, _ -> distances[i] <= medD + 3.5 * mad }
        val used = if (filtered.size >= 6) filtered else usable
        val result = chromaticity(robustRgb(used))

        val positiveBrightness = brightness.filter { it >= high * 0.10 }
        val p10 = percentile(positiveBrightness, 0.10)
        val p90 = percentile(positiveBrightness, 0.90)
        val gradient = if (p10 <= 1e-9) 0.0 else p90 / p10
        val spread = used.maxOfOrNull { chromaDistance(it, result) } ?: 0.0

        return WallColorResult(
            rgb = result,
            tilesUsed = used.size,
            availableTiles = candidates.size,
            brightnessGradient = gradient,
            chromaSpread = spread,
        )
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
