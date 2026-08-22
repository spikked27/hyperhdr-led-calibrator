package com.spikked27.hyperhdrcalibrator

data class Rgb(val r: Double, val g: Double, val b: Double) {
    fun max(): Double = maxOf(r, g, b)
    fun normalized(): Rgb {
        val m = max().coerceAtLeast(1e-9)
        return Rgb(r / m, g / m, b / m)
    }
    fun clamp01() = Rgb(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
    fun to255(): IntArray = intArrayOf(
        (r.coerceIn(0.0, 1.0) * 255.0).toInt(),
        (g.coerceIn(0.0, 1.0) * 255.0).toInt(),
        (b.coerceIn(0.0, 1.0) * 255.0).toInt()
    )
}

data class HyperHdrServer(
    val name: String,
    val host: String,
    val jsonPort: Int,
    val location: String? = null,
    val uuid: String? = null,
)

enum class Patch(val label: String, val rgb: IntArray) {
    RED("Red", intArrayOf(255, 0, 0)),
    GREEN("Green", intArrayOf(0, 255, 0)),
    BLUE("Blue", intArrayOf(0, 0, 255)),
    CYAN("Cyan", intArrayOf(0, 255, 255)),
    MAGENTA("Magenta", intArrayOf(255, 0, 255)),
    YELLOW("Yellow", intArrayOf(255, 255, 0)),
    WHITE("White", intArrayOf(255, 255, 255)),
    BLACK("Black", intArrayOf(0, 0, 0));
}

data class CalibrationResult(
    val targets: Map<Patch, IntArray>,
    val estimatedErrorBefore: Double,
    val estimatedErrorAfter: Double,
    val warning: String? = null,
)
