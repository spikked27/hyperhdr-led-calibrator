package com.spikked27.hyperhdrcalibrator

/**
 * Shared protocol between the automated camera workflow and the companion calibration video.
 *
 * The app does not rely on wall-clock timing to identify TV colors; it recognizes the actual
 * full-screen patch. These timings are deliberately generous so RAW bursts can complete even on
 * slower phones or after a brief YouTube playback hiccup.
 */
object CalibrationProtocol {
    const val VIDEO_LEAD_IN_SECONDS = 6
    const val TV_PATCH_SECONDS = 10
    const val FINAL_BLACK_SECONDS = 120

    val tvSequence: List<Patch> = listOf(
        Patch.WHITE,
        Patch.RED,
        Patch.GREEN,
        Patch.BLUE,
        Patch.CYAN,
        Patch.MAGENTA,
        Patch.YELLOW,
        Patch.BLACK,
    )

    val ledSequence: List<Patch> = listOf(
        Patch.WHITE,
        Patch.RED,
        Patch.GREEN,
        Patch.BLUE,
        Patch.CYAN,
        Patch.MAGENTA,
        Patch.YELLOW,
        Patch.BLACK,
    )

    const val PREVIEW_SAMPLE_WIDTH = 72
    const val PREVIEW_SAMPLE_HEIGHT = 40
    const val PREVIEW_TICK_MS = 120L
    const val STABLE_PREVIEW_FRAMES = 4
    const val LED_SETTLE_MS = 700L
    const val WHITE_EXPOSURE_SETTLE_MS = 1200L
}
