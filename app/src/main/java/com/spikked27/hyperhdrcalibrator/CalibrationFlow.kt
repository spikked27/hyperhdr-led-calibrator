package com.spikked27.hyperhdrcalibrator

internal object CalibrationFlow {
    fun nextPatchIndex(currentIndex: Int): Int? =
        if (currentIndex < Patch.entries.lastIndex) currentIndex + 1 else null

    fun shouldAutoCaptureLedBlack(currentIndex: Int): Boolean {
        val next = nextPatchIndex(currentIndex) ?: return false
        return Patch.entries[next] == Patch.BLACK
    }
}
