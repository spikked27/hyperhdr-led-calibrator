package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs

data class CameraCandidate(
    val id: String,
    val backFacing: Boolean,
    val rawSupported: Boolean,
    val equivalentFocalLengthsMm: List<Double>,
    val supportsOneX: Boolean,
)

object CameraSelection {
    private const val TARGET_MAIN_EQUIV_MM = 24.0

    fun chooseMain(candidates: List<CameraCandidate>): CameraCandidate {
        require(candidates.isNotEmpty()) { "No camera candidates" }
        return candidates
            .filter { it.backFacing }
            .minWithOrNull(compareBy<CameraCandidate>({ score(it) }, { it.id }))
            ?: candidates.minWith(compareBy({ score(it) }, { it.id }))
    }

    fun score(candidate: CameraCandidate): Double {
        val focalPenalty = candidate.equivalentFocalLengthsMm
            .minOfOrNull { abs(it - TARGET_MAIN_EQUIV_MM) }
            ?: 20.0
        val rawPenalty = if (candidate.rawSupported) 0.0 else 4.0
        val oneXPenalty = if (candidate.supportsOneX) 0.0 else 3.0
        val facingPenalty = if (candidate.backFacing) 0.0 else 1000.0
        return focalPenalty + rawPenalty + oneXPenalty + facingPenalty
    }
}
