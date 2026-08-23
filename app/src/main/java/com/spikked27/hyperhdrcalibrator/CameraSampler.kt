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
import android.hardware.camera2.params.OutputConfiguration
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
import kotlin.math.roundToInt

class CameraSampler(
    private val activity: Activity,
    private val preview: TextureView,
    private val requestedChoice: CameraChoice? = null,
) {
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
    private var selectedOpenChars: CameraCharacteristics? = null
    private var selectedChoice: CameraChoice? = null
    private var selectedPhysicalId: String? = null
    private var rawPattern: BayerPattern? = null
    private var rawWhiteLevel: Int = 1023
    private var rawBlackLevels = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private var rawSupported = false
    private var manualSensorSupported = false
    private var manualExposure: Exposure? = null
    private var latestAutoExposure: Exposure? = null
    private var freshManualLock = false

    private val rawQueue = LinkedBlockingQueue<SpatialFrame>(8)
    private val yuvQueue = LinkedBlockingQueue<SpatialFrame>(8)

    @Volatile var cameraSummary: String = "Camera not ready"
        private set
    @Volatile var exposureSummary: String = "Exposure not locked"
        private set
    @Volatile var lastMeasurementSummary: String = ""
        private set

    val cameraChoiceKey: String? get() = selectedChoice?.key

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

    @Synchronized
    private fun openCameraWhenReady() {
        if (closed || opening || device != null || !preview.isAvailable) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission is required")
            return
        }
        opening = true
        try {
            val choices = CameraCatalog.list(manager)
            val chosen = requestedChoice?.let { wanted -> choices.firstOrNull { it.key == wanted.key } }
                ?: CameraSelection.chooseMain(choices)
            selectedChoice = chosen
            selectedPhysicalId = chosen.physicalId

            val openChars = manager.getCameraCharacteristics(chosen.openId)
            val streamChars = manager.getCameraCharacteristics(chosen.streamId)
            selectedOpenChars = openChars
            selectedChars = streamChars

            val caps = streamChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            manualSensorSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            rawPattern = when (streamChars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> BayerPattern.RGGB
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> BayerPattern.GRBG
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> BayerPattern.GBRG
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> BayerPattern.BGGR
                else -> null
            }
            val rawSizes = streamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
            rawSupported = chosen.rawSupported && manualSensorSupported && rawPattern != null && rawSizes.isNotEmpty()

            streamChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.let { rawWhiteLevel = it }
            streamChars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { black ->
                rawBlackLevels = doubleArrayOf(
                    black.getOffsetForIndex(0, 0).toDouble(),
                    black.getOffsetForIndex(1, 0).toDouble(),
                    black.getOffsetForIndex(0, 1).toDouble(),
                    black.getOffsetForIndex(1, 1).toDouble(),
                )
            }

            if (rawSupported) {
                val rawSize = rawSizes.minByOrNull { it.width.toLong() * it.height.toLong() } ?: error("No RAW output size")
                rawReader?.close()
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3).also { ir ->
                    ir.setOnImageAvailableListener({ reader ->
                        reader.acquireNextImage()?.use { image ->
                            runCatching { sampleRawGrid(image) }.onSuccess { rawQueue.offer(it) }
                        }
                    }, handler)
                }
                yuvReader?.close(); yuvReader = null
            } else {
                val sizes = streamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)
                val size = sizes?.filter { it.width >= 1280 && it.height >= 720 }
                    ?.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: sizes?.firstOrNull()
                    ?: error("Camera has no YUV output")
                yuvReader?.close()
                yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { ir ->
                    ir.setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.use { image ->
                            runCatching { sampleYuvGrid(image) }.onSuccess {
                                if (yuvQueue.remainingCapacity() == 0) yuvQueue.poll()
                                yuvQueue.offer(it)
                            }
                        }
                    }, handler)
                }
                rawReader?.close(); rawReader = null
            }

            val streamPath = if (chosen.physicalId != null) "physical sensor ${chosen.streamId}" else "camera ${chosen.streamId}"
            cameraSummary = "${chosen.displayName()} • $streamPath • ${if (rawSupported) "RAW_SENSOR" else "YUV fallback"}"

            manager.openCamera(chosen.openId, object : CameraDevice.StateCallback() {
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
        if (selectedPhysicalId != null) return
        val c = selectedOpenChars ?: selectedChars ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range == null || (range.lower <= 1.0f && range.upper >= 1.0f)) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
            }
        } else {
            c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                builder.set(CaptureRequest.SCALER_CROP_REGION, it)
            }
        }
    }

    private fun <T> setForSelectedStream(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        builder.set(key, value)
        val physicalId = selectedPhysicalId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            val allowed = selectedOpenChars?.get(CameraCharacteristics.REQUEST_AVAILABLE_PHYSICAL_CAMERA_REQUEST_KEYS)
            if (allowed?.contains(key) == true) {
                runCatching { builder.setPhysicalCameraKey(key, value, physicalId) }
            }
        }
    }

    private fun resultForSelectedStream(result: TotalCaptureResult): CaptureResult {
        val physicalId = selectedPhysicalId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            return result.getPhysicalCameraTotalResults()[physicalId] ?: result
        }
        return result
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (manualExposure != null) return
            val selectedResult = resultForSelectedStream(result)
            val time = selectedResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = selectedResult.get(CaptureResult.SENSOR_SENSITIVITY)
            if (time != null && iso != null) latestAutoExposure = Exposure(time, iso)
        }
    }

    private fun newRequestBuilder(camera: CameraDevice, template: Int): CaptureRequest.Builder {
        val physicalId = selectedPhysicalId
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            camera.createCaptureRequest(template, setOf(physicalId))
        } else {
            camera.createCaptureRequest(template)
        }
    }

    @Suppress("DEPRECATION")
    private fun createConfiguredSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
    ) {
        val physicalId = selectedPhysicalId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && physicalId != null) {
            val outputs = surfaces.map { surface -> OutputConfiguration(surface).apply { setPhysicalCameraId(physicalId) } }
            camera.createCaptureSessionByOutputConfigurations(outputs, callback, handler)
        } else {
            camera.createCaptureSession(surfaces, callback, handler)
        }
    }

    private fun createSession(camera: CameraDevice) {
        val texture = preview.surfaceTexture
        if (texture == null || !preview.isAvailable) {
            releaseCameraResources(); postError("Camera preview surface is not ready"); return
        }
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)
        previewSurface?.release(); previewSurface = surface
        val measurementSurface = rawReader?.surface ?: yuvReader?.surface ?: run {
            releaseCameraResources(); postError("Camera measurement surface is unavailable"); return
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed || device !== camera || !preview.isAvailable) { configured.close(); return }
                session = configured
                requestBuilder = newRequestBuilder(camera, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    if (!rawSupported) addTarget(measurementSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    applyOneX(this)
                }
                configured.setRepeatingRequest(requestBuilder!!.build(), captureCallback, handler)
                activity.runOnUiThread {
                    readyCallback?.invoke("$cameraSummary. Keep this framing fixed; the app will identify the TV and analyze the wall around it.")
                }
            }
            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                postError("Could not configure ${selectedChoice?.displayName() ?: "camera"}. Try another rear lens.")
            }
        }
        createConfiguredSession(camera, listOf(surface, measurementSurface), callback)
    }

    private fun clampExposure(exposure: Exposure): Exposure {
        val c = selectedChars ?: return exposure
        val timeRange: Range<Long>? = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange: Range<Int>? = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        return Exposure(
            timeRange?.let { exposure.timeNs.coerceIn(it.lower, it.upper) } ?: exposure.timeNs,
            isoRange?.let { exposure.iso.coerceIn(it.lower, it.upper) } ?: exposure.iso,
        )
    }

    private fun applyManualExposure(exposure: Exposure, whiteBalanceLocked: Boolean) {
        val b = requestBuilder ?: return
        val e = clampExposure(exposure)
        manualExposure = e
        setForSelectedStream(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        setForSelectedStream(b, CaptureRequest.SENSOR_EXPOSURE_TIME, e.timeNs)
        setForSelectedStream(b, CaptureRequest.SENSOR_SENSITIVITY, e.iso)
        b.set(CaptureRequest.CONTROL_AE_LOCK, false)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
        applyOneX(b)
        session?.setRepeatingRequest(b.build(), captureCallback, handler)
        exposureSummary = "ISO ${e.iso} • 1/${(1_000_000_000.0 / e.timeNs).roundToInt().coerceAtLeast(1)} s (${"%.2f".format(e.timeNs / 1_000_000.0)} ms)"
    }

    fun setLocks(exposureLocked: Boolean, whiteBalanceLocked: Boolean) {
        val b = requestBuilder ?: return
        if (!exposureLocked) {
            manualExposure = null
            freshManualLock = false
            latestAutoExposure = null
            setForSelectedStream(b, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_LOCK, false)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
            applyOneX(b)
            session?.setRepeatingRequest(b.build(), captureCallback, handler)
            exposureSummary = "Auto exposure settling"
            return
        }

        if (manualSensorSupported) {
            val deadline = System.currentTimeMillis() + 1400
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

    fun measureSpatial(samples: Int = 5, timeoutMs: Long = 6500): SpatialFrame {
        return if (rawSupported) measureRawSpatial(samples.coerceIn(3, 7), timeoutMs)
        else measureYuvSpatial(samples.coerceIn(3, 9), timeoutMs.coerceAtMost(4000))
    }

    fun measure(samples: Int = 5, timeoutMs: Long = 6500): Rgb {
        val frame = measureSpatial(samples, timeoutMs)
        val rect = NormalizedRect(0.42, 0.42, 0.58, 0.58)
        return SpatialCalibration.screenColor(frame, rect)
    }

    private fun measureRawSpatial(samples: Int, timeoutMs: Long): SpatialFrame {
        require(manualExposure != null) { "RAW measurement requires locked manual ISO and shutter" }
        var attempt = 0
        while (true) {
            rawQueue.clear()
            val values = ArrayList<SpatialFrame>(samples)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (values.size < samples && System.currentTimeMillis() < deadline) {
                captureOneRaw()
                rawQueue.poll(1500, TimeUnit.MILLISECONDS)?.let { values += it }
            }
            require(values.size >= 3) { "Not enough RAW frames (${values.size}/$samples)" }
            val combined = SpatialCalibration.medianCombine(values)
            val clipped = combined.clippedFraction
            if (freshManualLock && clipped > 0.01 && attempt < 3) {
                reduceManualExposure()
                attempt++
                continue
            }
            freshManualLock = false
            require(clipped <= 0.025) {
                "RAW measurement is clipping ${"%.1f".format(clipped * 100)}% of sampled pixels; re-measure the white reference"
            }
            lastMeasurementSummary = "$cameraSummary • $exposureSummary • ${values.size} RAW frames • clipping ${"%.2f".format(clipped * 100)}%"
            return combined
        }
    }

    private fun measureYuvSpatial(samples: Int, timeoutMs: Long): SpatialFrame {
        yuvQueue.clear()
        val values = ArrayList<SpatialFrame>(samples)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (values.size < samples && System.currentTimeMillis() < deadline) {
            yuvQueue.poll(500, TimeUnit.MILLISECONDS)?.let { values += it }
        }
        require(values.size >= 3) { "Not enough YUV camera frames" }
        val combined = SpatialCalibration.medianCombine(values)
        lastMeasurementSummary = "$cameraSummary • $exposureSummary • ${values.size} YUV frames"
        return combined
    }

    private fun reduceManualExposure() {
        val old = manualExposure ?: return
        val c = selectedChars ?: return
        val timeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        var time = (old.timeNs * 0.60).toLong()
        var iso = old.iso
        if (timeRange != null && time < timeRange.lower) {
            time = timeRange.lower
            if (isoRange != null) iso = (old.iso * 0.60).roundToInt().coerceAtLeast(isoRange.lower)
        }
        applyManualExposure(Exposure(time, iso), true)
        Thread.sleep(200)
    }

    private fun captureOneRaw() {
        val camera = device ?: error("Camera is not open")
        val s = session ?: error("Camera session is not ready")
        val target = rawReader?.surface ?: error("RAW surface unavailable")
        val e = manualExposure ?: error("Manual exposure is not locked")
        val request = newRequestBuilder(camera, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(target)
            setForSelectedStream(this, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            setForSelectedStream(this, CaptureRequest.SENSOR_EXPOSURE_TIME, e.timeNs)
            setForSelectedStream(this, CaptureRequest.SENSOR_SENSITIVITY, e.iso)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyOneX(this)
        }.build()
        s.capture(request, null, handler)
    }

    private fun sampleRawGrid(image: Image): SpatialFrame {
        val pattern = rawPattern ?: error("Unsupported RAW Bayer pattern")
        val plane = image.planes.single()
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return RawColorSampler.sampleGrid(
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

    private fun sampleYuvGrid(image: Image, columns: Int = 36, rows: Int = 24): SpatialFrame {
        val yp = image.planes[0]
        val up = image.planes[1]
        val vp = image.planes[2]
        val tiles = ArrayList<Rgb>(columns * rows)
        for (ty in 0 until rows) {
            val cy = (((ty + 0.5) * image.height / rows).toInt()).coerceIn(2, image.height - 3)
            for (tx in 0 until columns) {
                val cx = (((tx + 0.5) * image.width / columns).toInt()).coerceIn(2, image.width - 3)
                var rs = 0.0; var gs = 0.0; var bs = 0.0; var count = 0
                var y = cy - 4
                while (y <= cy + 4) {
                    var x = cx - 4
                    while (x <= cx + 4) {
                        val yi = y * yp.rowStride + x * yp.pixelStride
                        val ui = (y / 2) * up.rowStride + (x / 2) * up.pixelStride
                        val vi = (y / 2) * vp.rowStride + (x / 2) * vp.pixelStride
                        val yy = (yp.buffer.get(yi).toInt() and 0xff).toDouble()
                        val uu = (up.buffer.get(ui).toInt() and 0xff) - 128.0
                        val vv = (vp.buffer.get(vi).toInt() and 0xff) - 128.0
                        rs += (yy + 1.402 * vv).coerceIn(0.0, 255.0)
                        gs += (yy - 0.344136 * uu - 0.714136 * vv).coerceIn(0.0, 255.0)
                        bs += (yy + 1.772 * uu).coerceIn(0.0, 255.0)
                        count++
                        x += 4
                    }
                    y += 4
                }
                tiles += Rgb(rs / count / 255.0, gs / count / 255.0, bs / count / 255.0)
            }
        }
        return SpatialFrame(columns, rows, tiles, 0.0)
    }

    @Synchronized
    private fun releaseCameraResources() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { yuvReader?.close() }
        runCatching { rawReader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        yuvReader = null
        rawReader = null
        previewSurface = null
        requestBuilder = null
        latestAutoExposure = null
        manualExposure = null
        rawQueue.clear()
        yuvQueue.clear()
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
