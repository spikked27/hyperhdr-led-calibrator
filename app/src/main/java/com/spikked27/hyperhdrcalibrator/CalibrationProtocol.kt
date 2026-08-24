package com.spikked27.hyperhdrcalibrator

/**
 * Shared protocol between the automated camera workflow and the companion calibration video.
 *
 * Beta 9 no longer identifies video state from the apparent RGB color. Each TV patch carries a
 * small machine-readable marker near the screen edge while the center remains a clean calibration
 * field. The marker is outside the RAW measurement ROI and lets the app resynchronize after a
 * dropped/late preview frame instead of hanging forever on one expected color.
 */
object CalibrationProtocol {
    // The video is paused on this unmarked BLACK opening while the app illuminates the wall and
    // snaps a 16:9 border to the physical TV. Playback starts only after that border is locked.
    const val VIDEO_LEAD_IN_SECONDS = 8

    // Beta 8's 10 s dwell was too close to the worst-case RAW burst/settle time. Fifteen seconds
    // gives the phone enough room to recognize the marker, lock exposure, and collect five frames.
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

    // Preview analysis is intentionally much higher resolution than Beta 8's 72x40 bitmap. It is
    // still lightweight compared with RAW capture and is used only for geometry + marker decoding.
    const val PREVIEW_SAMPLE_WIDTH = 320
    const val PREVIEW_SAMPLE_HEIGHT = 180
    const val PREVIEW_TICK_MS = 100L
    const val STABLE_MARKER_FRAMES = 3
    const val STABLE_BORDER_FRAMES = 6

    // Kept only because the non-launcher Beta 8 activity remains in the tree as a rollback/reference
    // implementation. Beta 9 synchronization uses STABLE_MARKER_FRAMES instead.
    const val STABLE_PREVIEW_FRAMES = 4

    const val LED_SETTLE_MS = 700L
    const val WHITE_EXPOSURE_SETTLE_MS = 1200L
}