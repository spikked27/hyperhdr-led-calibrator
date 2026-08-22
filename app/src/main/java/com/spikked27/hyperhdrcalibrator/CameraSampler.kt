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
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView

class CameraSampler(private val activity: Activity, private val preview: TextureView) {
    private val manager = activity.getSystemService(Activity.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cal-camera").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var closed = false
    @Volatile private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    @Volatile private var latest: Rgb? = null

    private var readyCallback: ((String) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCameraWhenReady()
        }

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
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: error("No camera found")

            val chars = manager.getCameraCharacteristics(cameraId)
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
            val size = sizes?.filter { it.width >= 1280 && it.height >= 720 }
                ?.minByOrNull { it.width * it.height }
                ?: sizes?.firstOrNull()
                ?: error("Camera has no YUV output")

            reader?.close()
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { ir ->
                ir.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.use { latest = sampleCenter(it) }
                }, handler)
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (closed || !preview.isAvailable) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    opening = false
                    postError("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    opening = false
                    postError("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            postError(e.message ?: "Could not open camera")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val texture = preview.surfaceTexture
        if (texture == null || !preview.isAvailable) {
            releaseCameraResources()
            postError("Camera preview surface is not ready")
            return
        }

        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)
        previewSurface?.close()
        previewSurface = surface
        val imageSurface = reader?.surface ?: run {
            releaseCameraResources()
            postError("Camera measurement surface is unavailable")
            return
        }

        camera.createCaptureSession(listOf(surface, imageSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed || device !== camera || !preview.isAvailable) {
                    configured.close()
                    return
                }
                session = configured
                requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    addTarget(imageSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }
                configured.setRepeatingRequest(requestBuilder!!.build(), null, handler)
                activity.runOnUiThread {
                    readyCallback?.invoke("Camera ready. Exposure/WB will lock on the white reference.")
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close()
                postError("Could not configure camera")
            }
        }, handler)
    }

    fun setLocks(exposureLocked: Boolean, whiteBalanceLocked: Boolean) {
        val builder = requestBuilder ?: return
        builder.set(CaptureRequest.CONTROL_AE_LOCK, exposureLocked)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
        session?.setRepeatingRequest(builder.build(), null, handler)
    }

    fun measure(samples: Int = 12, timeoutMs: Long = 2500): Rgb {
        val values = ArrayList<Rgb>(samples)
        val end = System.currentTimeMillis() + timeoutMs
        var last: Rgb? = null
        while (values.size < samples && System.currentTimeMillis() < end) {
            val now = latest
            if (now != null && now !== last) {
                values += now
                last = now
            }
            Thread.sleep(35)
        }
        require(values.size >= 3) { "Not enough camera frames" }
        fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
        return Rgb(
            median(values.map { it.r }),
            median(values.map { it.g }),
            median(values.map { it.b })
        )
    }

    private fun sampleCenter(image: Image): Rgb {
        val w = image.width
        val h = image.height
        val left = w * 2 / 5
        val right = w * 3 / 5
        val top = h * 2 / 5
        val bottom = h * 3 / 5
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        var rs = 0.0
        var gs = 0.0
        var bs = 0.0
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val yi = y * yPlane.rowStride + x * yPlane.pixelStride
                val ui = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
                val vi = (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride
                val yy = (yPlane.buffer.get(yi).toInt() and 0xff).toDouble()
                val uu = (uPlane.buffer.get(ui).toInt() and 0xff) - 128.0
                val vv = (vPlane.buffer.get(vi).toInt() and 0xff) - 128.0
                rs += (yy + 1.402 * vv).coerceIn(0.0, 255.0)
                gs += (yy - 0.344136 * uu - 0.714136 * vv).coerceIn(0.0, 255.0)
                bs += (yy + 1.772 * uu).coerceIn(0.0, 255.0)
                count++
                x += 12
            }
            y += 12
        }
        return Rgb(rs / count / 255.0, gs / count / 255.0, bs / count / 255.0)
    }

    @Synchronized
    private fun releaseCameraResources() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { previewSurface?.close() }
        session = null
        device = null
        reader = null
        previewSurface = null
        requestBuilder = null
        latest = null
    }

    private fun postError(message: String) {
        activity.runOnUiThread { errorCallback?.invoke(message) }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        if (preview.surfaceTextureListener === surfaceListener) preview.surfaceTextureListener = null
        releaseCameraResources()
        thread.quitSafely()
    }
}
