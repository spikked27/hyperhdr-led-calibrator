package com.spikked27.hyperhdrcalibrator

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.os.Build
import kotlin.math.abs

object CameraCatalog {
    fun list(manager: CameraManager): List<CameraChoice> {
        val openableIds = manager.cameraIdList.toSet()
        val choices = mutableListOf<CameraChoice>()
        val physicalStreamIds = mutableSetOf<String>()

        for (openId in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(openId)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue

            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars.physicalCameraIds else emptySet()
            if (physicalIds.isNotEmpty()) {
                for (physicalId in physicalIds) {
                    val physicalChars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull() ?: continue
                    val physicalFacing = physicalChars.get(CameraCharacteristics.LENS_FACING)
                    if (physicalFacing != null && physicalFacing != CameraCharacteristics.LENS_FACING_BACK) continue

                    val directlyOpenable = physicalId in openableIds
                    choices += buildChoice(
                        openId = if (directlyOpenable) physicalId else openId,
                        physicalId = if (directlyOpenable) null else physicalId,
                        streamId = physicalId,
                        chars = physicalChars,
                        isLogicalFallback = false,
                    )
                    physicalStreamIds += physicalId
                }

                // Keep the logical stream as a last-resort/manual choice. We deliberately penalize it because
                // OEMs are allowed to switch physical lenses behind a logical camera.
                choices += buildChoice(
                    openId = openId,
                    physicalId = null,
                    streamId = openId,
                    chars = chars,
                    isLogicalFallback = true,
                )
            } else if (openId !in physicalStreamIds) {
                choices += buildChoice(
                    openId = openId,
                    physicalId = null,
                    streamId = openId,
                    chars = chars,
                    isLogicalFallback = false,
                )
            }
        }

        // Prefer a directly openable representation if an OEM exposes the same physical sensor both
        // independently and as part of a logical camera.
        return choices
            .groupBy { it.streamId }
            .map { (_, sameSensor) ->
                sameSensor.minWith(compareBy<CameraChoice>({ if (it.physicalId == null) 0 else 1 }, { if (it.isLogicalFallback) 1 else 0 }))
            }
            .let(CameraSelection::sortForUser)
    }

    private fun buildChoice(
        openId: String,
        physicalId: String?,
        streamId: String,
        chars: CameraCharacteristics,
        isLogicalFallback: Boolean,
    ): CameraChoice {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
        val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSizes.isNotEmpty()

        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorWidth = physicalSize?.width?.toDouble()
        val sensorArea = physicalSize?.let { it.width.toDouble() * it.height.toDouble() }
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { it.toDouble() }.orEmpty()
        val eqValues = if (sensorWidth != null && sensorWidth > 0.0) {
            focals.map { it * 36.0 / sensorWidth }
        } else {
            emptyList()
        }
        val eq = eqValues.minByOrNull { abs(it - 24.0) }

        return CameraChoice(
            openId = openId,
            physicalId = physicalId,
            streamId = streamId,
            backFacing = true,
            rawSupported = raw,
            manualSensorSupported = manual,
            equivalentFocalLengthMm = eq,
            sensorAreaMm2 = sensorArea,
            isLogicalFallback = isLogicalFallback,
        )
    }
}
