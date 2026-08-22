package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraSelectionTest {
    @Test fun choosesMainOverUltraWideAndTele() {
        val ultra = CameraCandidate("2", true, true, listOf(13.0), true)
        val main = CameraCandidate("0", true, true, listOf(24.0), true)
        val tele = CameraCandidate("3", true, true, listOf(70.0), true)
        assertEquals("0", CameraSelection.chooseMain(listOf(ultra, tele, main)).id)
    }

    @Test fun rawMainBeatsNearMainProcessedCamera() {
        val processed = CameraCandidate("1", true, false, listOf(23.0), true)
        val raw = CameraCandidate("0", true, true, listOf(24.5), true)
        assertEquals("0", CameraSelection.chooseMain(listOf(processed, raw)).id)
    }

    @Test fun frontCameraIsNeverPreferred() {
        val front = CameraCandidate("front", false, true, listOf(24.0), true)
        val rear = CameraCandidate("rear", true, true, listOf(27.0), true)
        assertEquals("rear", CameraSelection.chooseMain(listOf(front, rear)).id)
    }

    @Test fun logicalCameraWithOneXAndMainFocalLengthWins() {
        val logical = CameraCandidate("0", true, true, listOf(13.0, 24.0, 70.0), true)
        val ultra = CameraCandidate("2", true, true, listOf(13.0), true)
        assertEquals("0", CameraSelection.chooseMain(listOf(ultra, logical)).id)
    }
}
