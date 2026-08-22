package com.spikked27.hyperhdrcalibrator

object CalibrationEngine {
    fun solve(tv: Map<Patch, Rgb>, led: Map<Patch, Rgb>): CalibrationResult {
        val required = Patch.entries.toSet() - Patch.BLACK
        require(tv.keys.containsAll(required) && led.keys.containsAll(required)) { "Missing calibration measurements" }

        val ledBasis = Matrix3.columns(
            led.getValue(Patch.RED).normalized(),
            led.getValue(Patch.GREEN).normalized(),
            led.getValue(Patch.BLUE).normalized(),
        )
        val invLed = ledBasis.inverse()

        val targets = linkedMapOf<Patch, IntArray>()
        for (patch in Patch.entries) {
            if (patch == Patch.BLACK) {
                targets[patch] = intArrayOf(0,0,0)
                continue
            }
            val command = (invLed * tv.getValue(patch).normalized()).clamp01()
            targets[patch] = command.normalized().to255()
        }

        val validationPatches = listOf(Patch.CYAN, Patch.MAGENTA, Patch.YELLOW, Patch.WHITE)
        val before = validationPatches.map { ColorScience.deltaE76(tv.getValue(it), led.getValue(it)) }.average()
        val after = validationPatches.map { p ->
            val q = targets.getValue(p)
            val predicted = ledBasis * Rgb(q[0]/255.0, q[1]/255.0, q[2]/255.0)
            ColorScience.deltaE76(tv.getValue(p), predicted)
        }.average()

        val warning = if (after > before * 0.95) "Correction did not materially improve the validation colors; re-measure with fixed camera exposure/WB and a neutral wall." else null
        return CalibrationResult(targets, before, after, warning)
    }
}
