package com.spikked27.hyperhdrcalibrator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
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
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    @Volatile private var latest: Rgb? = null

    fun start(onReady: (String) -> Unit, onError: (String) -> Unit) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission is required")
            return
        }
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return onError("No camera found")
        val chars = manager.getCameraCharacteristics(cameraId)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes?.filter { it.width >= 1280 && it.height >= 720 }?.minByOrNull { it.width * it.height }
            ?: sizes?.firstOrNull() ?: return onError("Camera has no YUV output")
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { ir ->
            ir.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { latest = sampleCenter(it) } }, handler)
        }
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) { device = c; createSession(c, onReady, onError) }
            override fun onDisconnected(c: CameraDevice) { c.close(); activity.runOnUiThread { onError("Camera disconnected") } }
            override fun onError(c: CameraDevice, error: Int) { c.close(); activity.runOnUiThread { onError("Camera error $error") } }
        }, handler)
    }

    private fun createSession(c: CameraDevice, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val texture = preview.surfaceTexture ?: return onError("Preview surface is not ready")
        texture.setDefaultBufferSize(1280, 720)
        val previewSurface = Surface(texture)
        val imageSurface = reader!!.surface
        c.createCaptureSession(listOf(previewSurface, imageSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                requestBuilder = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface); addTarget(imageSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }
                s.setRepeatingRequest(requestBuilder!!.build(), null, handler)
                activity.runOnUiThread { onReady("Camera ready. Exposure/WB will lock on the white reference.") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { activity.runOnUiThread { onError("Could not configure camera") } }
        }, handler)
    }

    fun setLocks(exposureLocked: Boolean, whiteBalanceLocked: Boolean) {
        val b = requestBuilder ?: return
        b.set(CaptureRequest.CONTROL_AE_LOCK, exposureLocked)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, whiteBalanceLocked)
        session?.setRepeatingRequest(b.build(), null, handler)
    }

    fun measure(samples: Int = 12, timeoutMs: Long = 2500): Rgb {
        val values = ArrayList<Rgb>(samples)
        val end = System.currentTimeMillis() + timeoutMs
        var last: Rgb? = null
        while (values.size < samples && System.currentTimeMillis() < end) {
            val now = latest
            if (now != null && now !== last) { values += now; last = now }
            Thread.sleep(35)
        }
        require(values.size >= 3) { "Not enough camera frames" }
        fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
        return Rgb(median(values.map { it.r }), median(values.map { it.g }), median(values.map { it.b }))
    }

    private fun sampleCenter(image: Image): Rgb {
        val w=image.width; val h=image.height
        val left=w*2/5; val right=w*3/5; val top=h*2/5; val bottom=h*3/5
        val yPlane=image.planes[0]; val uPlane=image.planes[1]; val vPlane=image.planes[2]
        var rs=0.0; var gs=0.0; var bs=0.0; var count=0
        var y=top
        while (y < bottom) {
            var x=left
            while (x < right) {
                val yi = y*yPlane.rowStride + x*yPlane.pixelStride
                val ui = (y/2)*uPlane.rowStride + (x/2)*uPlane.pixelStride
                val vi = (y/2)*vPlane.rowStride + (x/2)*vPlane.pixelStride
                val yy=(yPlane.buffer.get(yi).toInt() and 0xff).toDouble()
                val uu=(uPlane.buffer.get(ui).toInt() and 0xff)-128.0
                val vv=(vPlane.buffer.get(vi).toInt() and 0xff)-128.0
                rs += (yy + 1.402*vv).coerceIn(0.0,255.0)
                gs += (yy - 0.344136*uu - 0.714136*vv).coerceIn(0.0,255.0)
                bs += (yy + 1.772*uu).coerceIn(0.0,255.0)
                count++; x += 12
            }
            y += 12
        }
        return Rgb(rs/count/255.0, gs/count/255.0, bs/count/255.0)
    }

    fun close() { session?.close(); device?.close(); reader?.close(); thread.quitSafely() }
}
