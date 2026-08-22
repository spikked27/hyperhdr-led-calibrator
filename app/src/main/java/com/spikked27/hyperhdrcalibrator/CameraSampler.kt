package com.spikked27.hyperhdrcalibrator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class CameraSampler(private val activity: Activity, private val preview: TextureView) {
    private data class Exposure(val timeNs: Long, val iso: Int)

    private val manager = activity.getSystemService(Activity.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cal-camera").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var closed = false
    @Volatile private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var yuvReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var selectedChars: CameraCharacteristics? = null
    private var selectedCameraId: String? = null
    private var selectedEquivalentMm: Double? = null
    private var rawPattern: BayerPattern? = null
    private var rawWhiteLevel: Int = 1023
    private var rawBlackLevels = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private var rawSupported = false
    private var manualSensorSupported = false
    private var manualExposure: Exposure? = null
    private var latestAutoExposure: Exposure? = null
    private var freshManualLock = false
    @Volatile private var latestYuv: Rgb? = null
    @Volatile private var yuvFrameNumber: Long = 0
    private val rawQueue = LinkedBlockingQueue<RawFrameStats>(4)

    @Volatile var cameraSummary: String = "Camera not ready"
        private set
    @Volatile var exposureSummary: String = "Exposure not locked"
        private set
    @Volatile var lastMeasurementSummary: String = ""
        private set

    private var readyCallback: ((String) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCameraWhenReady()
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            releaseCameraResources()
            opening = false
            return true
        }
    }

    fun start(onReady: (String) -> Unit, onError: (String) -> Unit) {
        readyCallback = onReady
        errorCallback = onError
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission is required")
            return
        }
        preview.surfaceTextureListener = surfaceListener
        if (preview.isAvailable) openCameraWhenReady()
    }

    private fun cameraCandidates(): List<CameraCandidate> = manager.cameraIdList.map { id ->
        val c = manager.getCameraCharacteristics(id)
        val facing = c.get(CameraCharacteristics.LENS_FACING)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val sensorWidth = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width?.toDouble()
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { it.toDouble() }.orEmpty()
        val eq = if (sensorWidth != null && sensorWidth > 0.0) focal.map { it * 36.0 / sensorWidth } else focal.map { it * 4.0 }
        val supportsOneX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            r == null || (r.lower <= 1.0f && r.upper >= 1.0f)
        } else true
        CameraCandidate(id, facing == CameraCharacteristics.LENS_FACING_BACK, raw, eq, supportsOneX)
    }

    @Synchronized
    private fun openCameraWhenReady() {
        if (closed || opening || device != null || !preview.isAvailable) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission is required")
            return
        }

        opening = true
        try {
            val chosen = CameraSelection.chooseMain(cameraCandidates())
            val cameraId = chosen.id
            val chars = manager.getCameraCharacteristics(cameraId)
            selectedCameraId = cameraId
            selectedChars = chars
            selectedEquivalentMm = chosen.equivalentFocalLengthsMm.minByOrNull { abs(it - 24.0) }

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            manualSensorSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val arrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            rawPattern = when (arrangement) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> BayerPattern.RGGB
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> BayerPattern.GRBG
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> BayerPattern.GBRG
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> BayerPattern.BGGR
                else -> null
            }
            val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
            rawSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && manualSensorSupported && rawPattern != null && rawSizes.isNotEmpty()

            chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.let { rawWhiteLevel = it }
            chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { black ->
                rawBlackLevels = doubleArrayOf(
                    black.getOffsetForIndex(0, 0).toDouble(), black.getOffsetForIndex(1, 0).toDouble(),
                    black.getOffsetForIndex(0, 1).toDouble(), black.getOffsetForIndex(1, 1).toDouble(),
                )
            }

            if (rawSupported) {
                val rawSize = rawSizes.minByOrNull { it.width.toLong() * it.height.toLong() } ?: error("No RAW output size")
                rawReader?.close()
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).also { ir ->
                    ir.setOnImageAvailableListener({ reader ->
                        reader.acquireNextImage()?.use { image ->
                            runCatching { sampleRawCenter(image) }.onSuccess { rawQueue.offer(it) }
                        }
                    }, handler)
                }
                yuvReader?.close(); yuvReader = null
            } else {
                val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
                val size = sizes?.filter { it.width >= 1280 && it.height >= 720 }?.minByOrNull { it.width * it.height }
                    ?: sizes?.firstOrNull() ?: error("Camera has no YUV output")
                yuvReader?.close()
                yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { ir ->
                    ir.setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.use {
                            latestYuv = sampleYuvCenter(it)
                            yuvFrameNumber++
                        }
                    }, handler)
                }
                rawReader?.close(); rawReader = null
            }

            val eqText = selectedEquivalentMm?.let { "~${it.roundToInt()} mm eq" } ?: "1×"
            cameraSummary = "Main rear camera $eqText • ${if (rawSupported) "RAW_SENSOR" else "YUV fallback"}"

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (closed || !preview.isAvailable) { camera.close(); return }
                    device = camera
                    createSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); if (device === camera) device = null; opening = false; postError("Camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); if (device === camera) device = null; opening = false; postError("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            postError(e.message ?: "Could not open camera")
        }
    }

    private fun applyOneX(builder: CaptureRequest.Builder) {
        val c = selectedChars ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range == null || (range.lower <= 1.0f && range.upper >= 1.0f)) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
        } else {
            c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (manualExposure == null) {
                val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (t != null && iso != null) latestAutoExposure = Exposure(t, iso)
            }
        }
    }

    private fun createSession(camera: CameraDevice) {
        val texture = preview.surfaceTexture
        if (texture == null || !preview.isAvailable) { releaseCameraResources(); postError("Camera preview surface is not ready"); return }
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)
        previewSurface?.release(); previewSurface = surface
        val measurementSurface = rawReader?.surface ?: yuvReader?.surface ?: run {
            releaseCameraResources(); postError("Camera measurement surface is unavailable"); return
        }

        camera.createCaptureSession(listOf(surface, measurementSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed || device !== camera || !preview.isAvailable) { configured.close(); return }
                session = configured
                requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    if (!rawSupported) addTarget(measurementSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    applyOneX(this)
                }
                configured.setRepeatingRequest(requestBuilder!!.build(), captureCallback, handler)
                activity.runOnUiThread { readyCallback?.invoke("$cameraSummary. White reference will establish fixed ISO and shutter for each calibration half.") }
            }
            override fun onConfigureFailed(configured: CameraCaptureSession) { configured.close(); postError("Could not configure camera") }
        }, handler)
    }

    private fun clampExposure(exposure: Exposure): Exposure {
        val c = selectedChars ?: return exposure
        val tr: Range<Long>? = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ir: Range<Int>? = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        return Exposure(
            tr?.let { exposure.timeNs.coerceIn(it.lower, it.upper) } ?: exposure.timeNs,
            ir?.let { exposure.iso.coerceIn(it.lower, it.upper) } ?: exposure.iso,
        )
    }

    private fun applyManualExposure(exposure: Exposure, whiteBalanceLocked: Boolean) {
        val b = requestBuilder ?: return
        val e = clampExposure(exposure)
        manualExposure = e
        b.set(CaptureRequest.CONTROL_AE_LOCK, false)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.timeNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, e.iso)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
        applyOneX(b)
        session?.setRepeatingRequest(b.build(), captureCallback, handler)
        exposureSummary = "ISO ${e.iso} • 1/${(1_000_000_000.0 / e.timeNs).roundToInt().coerceAtLeast(1)} s (${e.timeNs / 1_000_000.0} ms)"
    }

    fun setLocks(exposureLocked: Boolean, whiteBalanceLocked: Boolean) {
        val b = requestBuilder ?: return
        if (!exposureLocked) {
            manualExposure = null
            freshManualLock = false
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_LOCK, false)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
            applyOneX(b)
            session?.setRepeatingRequest(b.build(), captureCallback, handler)
            exposureSummary = "Auto exposure settling"
            return
        }

        if (manualSensorSupported) {
            val deadline = System.currentTimeMillis() + 900
            var e = latestAutoExposure
            while (e == null && System.currentTimeMillis() < deadline) { Thread.sleep(20); e = latestAutoExposure }
            requireNotNull(e) { "Camera did not report ISO/shutter from auto exposure" }
            applyManualExposure(e, whiteBalanceLocked)
            freshManualLock = true
        } else {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
            session?.setRepeatingRequest(b.build(), captureCallback, handler)
            exposureSummary = "AE locked (manual sensor unavailable)"
        }
    }

    fun measure(samples: Int = 12, timeoutMs: Long = 2500): Rgb {
        return if (rawSupported) measureRaw(samples.coerceIn(5, 7), timeoutMs.coerceAtLeast(5000)) else measureYuv(samples, timeoutMs)
    }

    private fun measureRaw(samples: Int, timeoutMs: Long): Rgb {
        require(manualExposure != null) { "RAW measurement requires locked manual ISO and shutter" }
        var attempt = 0
        while (true) {
            val values = ArrayList<RawFrameStats>(samples)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (values.size < samples && System.currentTimeMillis() < deadline) {
                rawQueue.clear()
                captureOneRaw()
                rawQueue.poll(1400, TimeUnit.MILLISECONDS)?.let { values += it }
            }
            require(values.size >= 3) { "Not enough RAW frames (${values.size}/$samples)" }
            val clipped = values.map { it.clippedFraction }.average()
            if (freshManualLock && clipped > 0.01 && attempt < 3) {
                reduceManualExposure()
                attempt++
                continue
            }
            freshManualLock = false
            require(clipped <= 0.02) { "RAW measurement is clipping ${"%.1f".format(clipped * 100)}% of sampled pixels; re-measure the white reference" }
            fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
            val rgb = Rgb(
                median(values.map { it.rgb.r }),
                median(values.map { it.rgb.g }),
                median(values.map { it.rgb.b }),
            )
            lastMeasurementSummary = "$cameraSummary • $exposureSummary • ${values.size} RAW frames • clipping ${"%.2f".format(clipped * 100)}%"
            return rgb
        }
    }

    private fun reduceManualExposure() {
        val old = manualExposure ?: return
        val c = selectedChars ?: return
        val tr = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        var t = (old.timeNs * 0.60).toLong()
        var iso = old.iso
        if (tr != null && t < tr.lower) {
            t = tr.lower
            if (ir != null) iso = (old.iso * 0.60).roundToInt().coerceAtLeast(ir.lower)
        }
        applyManualExposure(Exposure(t, iso), true)
        Thread.sleep(180)
    }

    private fun captureOneRaw() {
        val camera = device ?: error("Camera is not open")
        val s = session ?: error("Camera session is not ready")
        val target = rawReader?.surface ?: error("RAW surface unavailable")
        val e = manualExposure ?: error("Manual exposure is not locked")
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.timeNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, e.iso)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyOneX(this)
        }.build()
        s.capture(request, null, handler)
    }

    private fun sampleRawCenter(image: Image): RawFrameStats {
        val pattern = rawPattern ?: error("Unsupported RAW Bayer pattern")
        val plane = image.planes.single()
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return RawColorSampler.sample(
            width = image.width,
            height = image.height,
            pattern = pattern,
            whiteLevel = rawWhiteLevel,
            blackLevels = rawBlackLevels,
            valueAt = { x, y ->
                val index = y * plane.rowStride + x * plane.pixelStride
                buffer.getShort(index).toInt() and 0xffff
            },
        )
    }

    private fun measureYuv(samples: Int, timeoutMs: Long): Rgb {
        val values = ArrayList<Rgb>(samples)
        val end = System.currentTimeMillis() + timeoutMs
        var lastFrame = yuvFrameNumber
        while (values.size < samples && System.currentTimeMillis() < end) {
            val frame = yuvFrameNumber
            val now = latestYuv
            if (now != null && frame != lastFrame) { values += now; lastFrame = frame }
            Thread.sleep(25)
        }
        require(values.size >= 3) { "Not enough camera frames" }
        fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
        lastMeasurementSummary = "$cameraSummary • $exposureSummary • ${values.size} YUV frames"
        return Rgb(median(values.map { it.r }), median(values.map { it.g }), median(values.map { it.b }))
    }

    private fun sampleYuvCenter(image: Image): Rgb {
        val w = image.width; val h = image.height
        val left = w * 2 / 5; val right = w * 3 / 5; val top = h * 2 / 5; val bottom = h * 3 / 5
        val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
        var rs = 0.0; var gs = 0.0; var bs = 0.0; var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val yi = y * yp.rowStride + x * yp.pixelStride
                val ui = (y / 2) * up.rowStride + (x / 2) * up.pixelStride
                val vi = (y / 2) * vp.rowStride + (x / 2) * vp.pixelStride
                val yy = (yp.buffer.get(yi).toInt() and 0xff).toDouble()
                val uu = (up.buffer.get(ui).toInt() and 0xff) - 128.0
                val vv = (vp.buffer.get(vi).toInt() and 0xff) - 128.0
                rs += (yy + 1.402 * vv).coerceIn(0.0, 255.0)
                gs += (yy - 0.344136 * uu - 0.714136 * vv).coerceIn(0.0, 255.0)
                bs += (yy + 1.772 * uu).coerceIn(0.0, 255.0)
                count++; x += 12
            }
            y += 12
        }
        return Rgb(rs / count / 255.0, gs / count / 255.0, bs / count / 255.0)
    }

    @Synchronized
    private fun releaseCameraResources() {
        runCatching { session?.stopRepeating() }; runCatching { session?.close() }; runCatching { device?.close() }
        runCatching { yuvReader?.close() }; runCatching { rawReader?.close() }; runCatching { previewSurface?.release() }
        session = null; device = null; yuvReader = null; rawReader = null; previewSurface = null; requestBuilder = null
        latestYuv = null; latestAutoExposure = null; manualExposure = null; rawQueue.clear()
    }

    private fun postError(message: String) { activity.runOnUiThread { errorCallback?.invoke(message) } }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        if (preview.surfaceTextureListener === surfaceListener) preview.surfaceTextureListener = null
        releaseCameraResources()
        thread.quitSafely()
    }
}
