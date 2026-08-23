package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraSelectionTest {
    private fun camera(
        id: String,
        eq: Double?,
        raw: Boolean = true,
        manual: Boolean = true,
        physical: String? = null,
        logicalFallback: Boolean = false,
        area: Double = 40.0,
    ) = CameraChoice(
        openId = id,
        physicalId = physical,
        streamId = physical ?: id,
        backFacing = true,
        rawSupported = raw,
        manualSensorSupported = manual,
        equivalentFocalLengthMm = eq,
        sensorAreaMm2 = area,
        isLogicalFallback = logicalFallback,
    )

    @Test fun choosesMainOverUltraWideAndTele() {
        val ultra = camera("2", 13.0)
        val main = camera("0", 24.0)
        val tele = camera("3", 70.0)
        assertEquals("0", CameraSelection.chooseMain(listOf(ultra, tele, main)).openId)
    }

    @Test fun physicalMainBeatsLogicalMultiCameraFallback() {
        val logical = camera("0", 24.0, logicalFallback = true)
        val physicalMain = camera("0", 24.5, physical = "3", logicalFallback = false)
        assertEquals("3", CameraSelection.chooseMain(listOf(logical, physicalMain)).streamId)
    }

    @Test fun ultrawideDoesNotWinJustBecauseItSupportsRaw() {
        val ultraRaw = camera("uw", 13.0, raw = true)
        val mainProcessed = camera("main", 25.0, raw = false)
        assertEquals("main", CameraSelection.chooseMain(listOf(ultraRaw, mainProcessed)).openId)
    }

    @Test fun userOrderIsWideToTele() {
        val sorted = CameraSelection.sortForUser(listOf(camera("tele", 70.0), camera("main", 24.0), camera("ultra", 13.0)))
        assertEquals(listOf("ultra", "main", "tele"), sorted.map { it.openId })
    }

    @Test fun displayLabelsUltrawideClearly() {
        assertTrue(camera("2", 13.0).displayName().contains("Ultrawide"))
        assertTrue(camera("0", 24.0).displayName().contains("Main / wide"))
    }
}
