package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.math.roundToInt

data class CameraChoice(
    val openId: String,
    val physicalId: String?,
    val streamId: String,
    val backFacing: Boolean,
    val rawSupported: Boolean,
    val manualSensorSupported: Boolean,
    val equivalentFocalLengthMm: Double?,
    val sensorAreaMm2: Double?,
    val isLogicalFallback: Boolean,
) {
    val key: String get() = "$openId|${physicalId ?: "-"}|$streamId"

    fun displayName(): String {
        val eq = equivalentFocalLengthMm
        val lens = when {
            eq == null -> "Rear camera"
            eq < 18.0 -> "Ultrawide"
            eq <= 36.0 -> "Main / wide"
            else -> "Telephoto"
        }
        val zoom = eq?.let { it / 24.0 }
        val zoomText = zoom?.let { "~${"%.1f".format(it)}×" } ?: ""
        val eqText = eq?.let { "~${it.roundToInt()} mm eq" } ?: "focal length unknown"
        val path = if (physicalId != null) "physical $physicalId via logical $openId" else "camera $openId"
        return listOf(lens, zoomText, eqText, path).filter { it.isNotBlank() }.joinToString(" • ")
    }
}

object CameraSelection {
    private const val TARGET_MAIN_EQUIV_MM = 24.0

    fun chooseMain(candidates: List<CameraChoice>): CameraChoice {
        require(candidates.isNotEmpty()) { "No camera candidates" }
        return candidates
            .filter { it.backFacing }
            .minWithOrNull(compareBy<CameraChoice>({ score(it) }, { it.key }))
            ?: candidates.minWith(compareBy({ score(it) }, { it.key }))
    }

    fun sortForUser(candidates: List<CameraChoice>): List<CameraChoice> = candidates
        .filter { it.backFacing }
        .distinctBy { it.key }
        .sortedWith(compareBy<CameraChoice>({ it.equivalentFocalLengthMm ?: 999.0 }, { score(it) }, { it.key }))

    fun score(candidate: CameraChoice): Double {
        val eq = candidate.equivalentFocalLengthMm
        val lensClassPenalty = when {
            eq == null -> 45.0
            eq < 18.0 -> 90.0 + (18.0 - eq) * 3.0
            eq > 38.0 -> 70.0 + (eq - 38.0) * 1.5
            else -> 0.0
        }
        val focalPenalty = eq?.let { abs(it - TARGET_MAIN_EQUIV_MM) * 2.0 } ?: 30.0
        val rawPenalty = if (candidate.rawSupported) 0.0 else 8.0
        val manualPenalty = if (candidate.manualSensorSupported) 0.0 else 5.0
        val logicalPenalty = if (candidate.isLogicalFallback) 30.0 else 0.0
        val physicalSpecificBonus = if (candidate.physicalId != null) -8.0 else 0.0
        val sensorBonus = -((candidate.sensorAreaMm2 ?: 0.0).coerceAtMost(100.0) * 0.18)
        val facingPenalty = if (candidate.backFacing) 0.0 else 1000.0
        return lensClassPenalty + focalPenalty + rawPenalty + manualPenalty + logicalPenalty + physicalSpecificBonus + sensorBonus + facingPenalty
    }
}
