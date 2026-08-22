package com.spikked27.hyperhdrcalibrator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.TextureView
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var preview: TextureView
    private lateinit var action: Button
    private lateinit var results: TextView
    private var sampler: CameraSampler? = null
    private var server: HyperHdrServer? = null
    private var client: HyperHdrClient? = null
    private val tv = linkedMapOf<Patch,Rgb>()
    private val led = linkedMapOf<Patch,Rgb>()
    private var phase = 0 // 0 TV, 1 LED, 2 done
    private var index = 0
    private var cameraReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(28,28,28,28)
            setBackgroundColor(Color.rgb(250,248,255))
        }
        root.addView(TextView(this).apply {
            text="HyperHDR LED Calibrator"
            textSize=25f
            setTextColor(Color.rgb(35,31,40))
        })
        status = TextView(this).apply { text="Starting…"; textSize=15f; setPadding(0,12,0,12) }
        root.addView(status)
        preview = TextureView(this).apply { layoutParams=LinearLayout.LayoutParams(-1,0,1f) }
        root.addView(preview)
        action = Button(this).apply {
            text="Waiting for camera…"
            isEnabled=false
            setOnClickListener { captureCurrent() }
        }
        root.addView(action)
        val rediscover = Button(this).apply {
            text="Rediscover HyperHDR"
            setOnClickListener { discover() }
        }
        root.addView(rediscover)
        results = TextView(this).apply { textSize=14f; setPadding(0,12,0,0) }
        root.addView(results)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        } else startCamera()
        discover()
    }

    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode==10 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) startCamera()
        else status.text="Camera permission denied"
    }

    private fun startCamera() {
        fun open() {
            if (sampler != null) return
            sampler = CameraSampler(this@MainActivity,preview).also { camera ->
                camera.start({ msg -> cameraReady=true; status.text=msg; updateAction() }, { e -> status.text=e })
            }
        }
        if (preview.isAvailable) open()
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s:android.graphics.SurfaceTexture,w:Int,h:Int) = open()
            override fun onSurfaceTextureSizeChanged(s:android.graphics.SurfaceTexture,w:Int,h:Int) {}
            override fun onSurfaceTextureDestroyed(s:android.graphics.SurfaceTexture)=true
            override fun onSurfaceTextureUpdated(s:android.graphics.SurfaceTexture) {}
        }
    }

    private fun discover() {
        status.text="Searching local network for HyperHDR…"
        executor.execute {
            val found = runCatching { SsdpDiscovery(this).discover() }.getOrElse { emptyList() }
            val chosen = found.firstOrNull { HyperHdrClient(it).ping() }
            runOnUiThread {
                if (chosen != null) {
                    server=chosen
                    client=HyperHdrClient(chosen)
                    status.text="Connected: ${chosen.name} (${chosen.host}:${chosen.jsonPort})"
                } else {
                    status.text="No HyperHDR found. Confirm phone and HyperHDR are on the same LAN and SSDP is enabled."
                }
                updateAction()
            }
        }
    }

    private fun updateAction() {
        if (!cameraReady || sampler==null) return
        if (phase==2) { action.text="Calibration complete"; action.isEnabled=false; return }
        val p = Patch.entries[index]
        action.isEnabled = phase==0 || server!=null
        action.text = if (phase==0) "Measure TV: ${p.label}" else "Measure LED wall: ${p.label}"
    }

    private fun captureCurrent() {
        val p=Patch.entries[index]
        action.isEnabled=false
        status.text = if (phase==0)
            "Fill the camera center with the TV's ${p.label} patch and hold steady…"
        else
            "Setting HyperHDR LEDs to ${p.label}…"

        executor.execute {
            try {
                if (phase==0 && index==0) {
                    Thread.sleep(1400)
                    sampler!!.setLocks(exposureLocked=true, whiteBalanceLocked=true)
                    Thread.sleep(250)
                }
                if (phase==1) {
                    client!!.setColor(p.rgb)
                    if (index==0) {
                        sampler!!.setLocks(exposureLocked=false, whiteBalanceLocked=true)
                        Thread.sleep(1400)
                        sampler!!.setLocks(exposureLocked=true, whiteBalanceLocked=true)
                        Thread.sleep(250)
                    } else Thread.sleep(650)
                }

                val sample=sampler!!.measure()
                if (phase==0) tv[p]=sample else led[p]=sample
                runOnUiThread {
                    results.text="Measured ${if(phase==0) "TV" else "LED"} ${p.label}: %.3f, %.3f, %.3f".format(sample.r,sample.g,sample.b)
                }
                index++
                if (index>=Patch.entries.size) {
                    index=0
                    if (phase==0) {
                        phase=1
                        runOnUiThread {
                            status.text="TV measurements complete. Point the phone at the representative wall area illuminated by the LEDs."
                        }
                    } else finishSolve()
                }
            } catch (e:Exception) {
                runOnUiThread { status.text="Measurement failed: ${e.message}"; action.isEnabled=true }
                return@execute
            }
            runOnUiThread { updateAction() }
        }
    }

    private fun finishSolve() {
        runCatching { client?.clear() }
        try {
            val solved=CalibrationEngine.solve(tv,led)
            phase=2
            val body=buildString {
                appendLine("Suggested HyperHDR full ICE LED calibration values:")
                for (p in Patch.entries) {
                    val c=solved.targets.getValue(p)
                    appendLine("${p.label.padEnd(8)} [${c[0]}, ${c[1]}, ${c[2]}]")
                }
                appendLine()
                appendLine("Estimated relative validation error: %.1f → %.1f".format(solved.estimatedErrorBefore, solved.estimatedErrorAfter))
                appendLine("Brightness is intentionally not calibrated. This beta does not write your saved HyperHDR settings.")
                solved.warning?.let { appendLine("Warning: $it") }
            }
            runOnUiThread { status.text="Calibration solved"; results.text=body; updateAction() }
        } catch (e:Exception) {
            runOnUiThread { status.text="Could not solve calibration: ${e.message}"; action.isEnabled=true }
        }
    }

    override fun onDestroy() {
        runCatching { client?.clear() }
        sampler?.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
