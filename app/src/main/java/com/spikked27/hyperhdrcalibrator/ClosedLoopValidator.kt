package com.spikked27.hyperhdrcalibrator

data class ProcessedValidationSet(
    val colors: Map<Patch, Rgb>,
    val diagnostics: Map<Patch, WallColorResult>,
)

/** Measures the LED wall through HyperHDR's normal IMAGE processing path. */
object ClosedLoopValidator {
    fun measureProcessedSet(
        camera: CameraSampler,
        client: HyperHdrClient,
        tvRect: NormalizedRect,
        status: (String) -> Unit = {},
    ): ProcessedValidationSet {
        val spatial = linkedMapOf<Patch, SpatialFrame>()

        status("Validation • WHITE reference")
        client.setProcessedColor(Patch.WHITE.rgb)
        Thread.sleep(CalibrationProtocol.VALIDATION_SETTLE_MS)
        val white = camera.measureSpatial(
            samples = CalibrationProtocol.VALIDATION_SAMPLES,
            timeoutMs = CalibrationProtocol.VALIDATION_CAPTURE_TIMEOUT_MS,
        )
        spatial[Patch.WHITE] = white

        status("Validation • BLACK baseline 1/2")
        client.setProcessedColor(Patch.BLACK.rgb)
        Thread.sleep(CalibrationProtocol.VALIDATION_SETTLE_MS)
        val blackStart = camera.measureSpatial(
            samples = CalibrationProtocol.VALIDATION_SAMPLES,
            timeoutMs = CalibrationProtocol.VALIDATION_CAPTURE_TIMEOUT_MS,
        )

        for (patch in CalibrationEngine.chromaticPatches) {
            status("Validation • ${patch.label.uppercase()}")
            client.setProcessedColor(patch.rgb)
            Thread.sleep(CalibrationProtocol.VALIDATION_SETTLE_MS)
            spatial[patch] = camera.measureSpatial(
                samples = CalibrationProtocol.VALIDATION_SAMPLES,
                timeoutMs = CalibrationProtocol.VALIDATION_CAPTURE_TIMEOUT_MS,
            )
        }

        status("Validation • BLACK baseline 2/2")
        client.setProcessedColor(Patch.BLACK.rgb)
        Thread.sleep(CalibrationProtocol.VALIDATION_SETTLE_MS)
        val blackEnd = camera.measureSpatial(
            samples = CalibrationProtocol.VALIDATION_SAMPLES,
            timeoutMs = CalibrationProtocol.VALIDATION_CAPTURE_TIMEOUT_MS,
        )
        val black = SpatialCalibration.medianCombine(listOf(blackStart, blackEnd))
        spatial[Patch.BLACK] = black

        val model = SpatialCalibration.buildWallReference(white, black, tvRect)
        val colors = linkedMapOf<Patch, Rgb>()
        val diagnostics = linkedMapOf<Patch, WallColorResult>()
        colors[Patch.BLACK] = Rgb(0.0, 0.0, 0.0)

        val whiteResult = SpatialCalibration.wallColor(white, black, model)
        colors[Patch.WHITE] = whiteResult.rgb
        diagnostics[Patch.WHITE] = whiteResult

        for (patch in CalibrationEngine.chromaticPatches) {
            val result = SpatialCalibration.wallColor(spatial.getValue(patch), black, model)
            colors[patch] = result.rgb
            diagnostics[patch] = result
        }

        return ProcessedValidationSet(colors, diagnostics)
    }
}
