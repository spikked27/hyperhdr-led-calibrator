package com.spikked27.hyperhdrcalibrator

/**
 * Shared protocol between the automated camera workflow and the companion calibration video.
 *
 * Beta 9.2 keeps synchronization independent of apparent camera RGB. Each TV patch carries a
 * high-contrast machine-readable marker near the screen edge; RAW/YUV spatial capture remains the
 * source of the actual calibration measurement.
 */
object CalibrationProtocol {
    // The user presses READY and starts playback immediately. The app keeps the LEDs WHITE during a
    // 5 s framing countdown, then has roughly 10 s of remaining black video to acquire the TV edge.
    const val VIDEO_LEAD_IN_SECONDS = 15
    const val BORDER_COUNTDOWN_SECONDS = 5

    const val TV_PATCH_SECONDS = 15
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

    // Preserve the actual TextureView aspect instead of forcing portrait camera pixels into a
    // landscape 320x180 bitmap. The long edge is capped at 320 px for lightweight live analysis.
    const val PREVIEW_SAMPLE_LONG_EDGE = 320
    const val PREVIEW_SAMPLE_SHORT_EDGE_MIN = 120
    const val PREVIEW_TICK_MS = 100L
    const val STABLE_MARKER_FRAMES = 3
    const val STABLE_BORDER_FRAMES = 6

    // Kept because the older Beta 8 activity remains in the source tree as a rollback reference.
    const val PREVIEW_SAMPLE_WIDTH = 320
    const val PREVIEW_SAMPLE_HEIGHT = 180
    const val STABLE_PREVIEW_FRAMES = 4

    const val LED_SETTLE_MS = 700L
    const val WHITE_EXPOSURE_SETTLE_MS = 1200L
}
