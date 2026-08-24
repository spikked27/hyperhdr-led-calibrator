package com.spikked27.hyperhdrcalibrator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

/**
 * Beta 9 field-reliability pass.
 *
 * Major differences from Beta 8:
 * - calibration UI stays portrait with a true 9:16 camera viewport (no stretched preview),
 * - TV border is found before playback using BLACK TV + WHITE backlight and is constrained to 16:9,
 * - overlay follows/refines the physical border instead of a generic color component,
 * - companion video carries a machine-readable marker, so synchronization does not depend on the
 *   phone's processed RGB interpretation of RED/GREEN/etc,
 * - already-captured steps are retained and missing steps can be picked up on another video pass,
 * - leaving the activity cancels capture work and closes Camera2 cleanly,
 * - solved values can be committed directly to HyperHDR.
 */
class Beta9CalibrationActivity : ComponentActivity() {
    private enum class Stage { DISCOVERY, INTRO, BORDER, CALIBRATION, ANALYZING, RESULTS }

    private val executor = Executors.newSingleThreadExecutor()
    private val previewHandler = Handler(Looper.getMainLooper())
    private var activeCaptureJob: Future<*>? = null
    private var generation = 1
    private var previewLoopPosted = false

    private var stage by mutableStateOf(Stage.DISCOVERY)
    private var targets by mutableStateOf<List<HyperHdrTarget>>(emptyList())
    private var selectedTarget by mutableStateOf<HyperHdrTarget?>(null)
    private var statusMessage by mutableStateOf("Searching your local network for HyperHDR…")
    private var scanning by mutableStateOf(false)
    private var busy by mutableStateOf(false)

    private var cameraReady by mutableStateOf(false)
    private var cameraError by mutableStateOf<String?>(null)
    private var cameraChoices by mutableStateOf<List<CameraChoice>>(emptyList())
    private var selectedCameraChoiceKey by mutableStateOf<String?>(null)
    private var cameraStatus by mutableStateOf("Camera not started")

    private var borderLightingActive = false
    private var borderLocked by mutableStateOf(false)
    private var borderStableFrames = 0
    private var borderCandidate: NormalizedRect? = null
    private var previewTvRect by mutableStateOf<NormalizedRect?>(null)

    private var videoArmed by mutableStateOf(false)
    private var markerStep: Int? = null
    private var markerStableFrames = 0
    private var lastObservedStep: Int? = null
    private var previewTick = 0
    private var autoProgress by mutableStateOf("Ready")
    private var latestMeasurement by mutableStateOf<String?>(null)

    private var resultText by mutableStateOf("")
    private var solveError by mutableStateOf<String?>(null)
    private var solvedResult by mutableStateOf<CalibrationResult?>(null)
    private var commitStatus by mutableStateOf<String?>(null)
    private var committing by mutableStateOf(false)

    private var sampler: CameraSampler? = null
    private var previewView: TextureView? = null
    private var client: HyperHdrClient? = null
    private var rawTvRect: NormalizedRect? = null

    private val tvMeasurements = linkedMapOf<Patch, Rgb>()
    private val ledMeasurements = linkedMapOf<Patch, Rgb>()
    private val ledSpatialMeasurements = linkedMapOf<Patch, SpatialFrame>()
    private val wallDiagnostics = linkedMapOf<Patch, WallColorResult>()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prepareCameraChoices()
            startCameraIfPossible()
        } else {
            cameraError = "Camera permission is required to measure the TV and LED colors."
        }
    }

    private val previewRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                when {
                    stage == Stage.BORDER && borderLightingActive && cameraReady && !busy -> analyzeBorderPreview()
                    stage == Stage.CALIBRATION && videoArmed && cameraReady && !busy -> analyzeVideoPreview()
                }
                previewHandler.postDelayed(this, CalibrationProtocol.PREVIEW_TICK_MS)
                previewLoopPosted = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Beta9Theme { AppContent() } }
        postPreviewLoop()
        discoverTargets()
    }

    override fun onStart() {
        super.onStart()
        postPreviewLoop()
    }

    override fun onStop() {
        // Invalidate capture work and release Camera2 before doing any network cleanup. This keeps
        // activity transitions from waiting on HyperHDR TCP timeouts and prevents a stale camera
        // callback from touching the next session after the user returns to the app.
        generation++
        activeCaptureJob?.cancel(true)
        activeCaptureJob = null
        previewHandler.removeCallbacks(previewRunnable)
        previewLoopPosted = false
        closeCamera()
        clearPriorityAsync(client)
        borderLightingActive = false
        videoArmed = false
        busy = false

        if (stage == Stage.BORDER || stage == Stage.CALIBRATION || stage == Stage.ANALYZING) {
            resetMeasurements()
            stage = Stage.INTRO
            statusMessage = "Calibration was interrupted when the app left the foreground. Camera resources were released cleanly; start a new run."
        }
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        activeCaptureJob?.cancel(true)
        previewHandler.removeCallbacks(previewRunnable)
        val old = client
        client = null
        closeCamera()
        Thread {
            runCatching { old?.clear() }
            old?.close()
        }.start()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun postPreviewLoop() {
        if (!previewLoopPosted) {
            previewLoopPosted = true
            previewHandler.post(previewRunnable)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppContent() {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("HyperHDR LED Calibrator • Beta 9.1", fontWeight = FontWeight.SemiBold)
                            selectedTarget?.let {
                                if (stage != Stage.DISCOVERY) Text(it.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (stage) {
                    Stage.DISCOVERY -> DiscoveryScreen()
                    Stage.INTRO -> IntroScreen()
                    Stage.BORDER, Stage.CALIBRATION -> CalibrationScreen()
                    Stage.ANALYZING -> AnalyzingScreen()
                    Stage.RESULTS -> ResultsScreen()
                }
            }
        }
    }

    @Composable
    private fun DiscoveryScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Choose a HyperHDR instance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Searching with HyperHDR SSDP…")
                }
            }
            targets.forEach { target ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(target.displayName, fontWeight = FontWeight.SemiBold)
                        Text("${target.server.host}:${target.server.jsonPort} • Instance ${target.instance.instanceId}")
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = target.instance.running && !busy,
                            onClick = { connectToTarget(target) },
                        ) { Text("Connect") }
                    }
                }
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !scanning && !busy, onClick = { discoverTargets() }) {
                Text("Scan again")
            }
        }
    }

    @Composable
    private fun IntroScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Reliable synchronized calibration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            InstructionCard("1", "Pause the Beta 9 video at 0:00", "The opening is an unmarked BLACK screen. Do not start playback yet.")
            InstructionCard("2", "Frame the full TV and wall", "The app turns the backlights WHITE while the TV is black, then snaps the overlay to a 16:9 TV border. Keep a useful wall halo visible around the screen.")
            InstructionCard("3", "Wait for BORDER LOCKED", "As soon as the border is stable, the app turns the backlights back OFF. The outline should sit on the actual TV border.")
            InstructionCard("4", "Press START VIDEO NOW", "The video carries an edge marker identifying each patch. The app no longer has to guess whether a processed camera frame looks red, green, or blue.")
            InstructionCard("5", "If a patch is missed, keep going", "Captured patches are retained. The app never hangs on one expected color; replay the video only if the final black reports a missing patch.")
            Button(modifier = Modifier.fillMaxWidth(), onClick = { beginCalibration() }) { Text("Open camera and find TV") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun CalibrationScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (stage) {
                    Stage.BORDER -> if (borderLocked) "TV border locked" else "Finding TV border"
                    Stage.CALIBRATION -> "Automatic TV calibration"
                    else -> "Calibration"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(autoProgress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            StatusCard(statusMessage)
            CameraPreview()
            Text(cameraStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            latestMeasurement?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (stage == Stage.BORDER && !borderLocked && cameraChoices.size > 1 && !busy) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { switchRearCamera() }) { Text("Switch rear camera") }
            }
            if (stage == Stage.BORDER && borderLocked) {
                Button(modifier = Modifier.fillMaxWidth(), enabled = cameraReady && !busy, onClick = { armVideoSequence() }) {
                    Text("START VIDEO NOW")
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { reacquireBorder() }) {
                    Text("Re-find TV border")
                }
            }
            if (stage == Stage.CALIBRATION) {
                Text(
                    "Captured ${tvMeasurements.size}/${CalibrationProtocol.tvSequence.size}: ${capturedPatchLabels()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val missing = missingTvPatches()
                if (lastObservedStep == CalibrationProtocol.tvSequence.lastIndex && missing.isNotEmpty()) {
                    Text(
                        "Missing: ${missing.joinToString { it.label }}. Restart the video at 0:00; already-captured colors will be skipped automatically.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    @Composable
    private fun CameraPreview() {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(18.dp)).background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().zIndex(0f),
                factory = { context ->
                    TextureView(context).also { view ->
                        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> PreviewGeometry.configure(v as TextureView) }
                        bindPreviewView(view)
                        view.post { PreviewGeometry.configure(view) }
                    }
                },
                update = { view ->
                    bindPreviewView(view)
                    view.post { PreviewGeometry.configure(view) }
                },
            )

            previewTvRect?.let { rect ->
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * rect.left.toFloat(), y = maxHeight * rect.top.toFloat())
                        .width(maxWidth * rect.width.toFloat())
                        .height(maxHeight * rect.height.toFloat())
                        .zIndex(2f)
                        .border(5.dp, if (borderLocked) Color.Green else Color.White, RoundedCornerShape(4.dp)),
                )
            }
            Text(
                when {
                    stage == Stage.BORDER && borderLocked -> "BORDER LOCKED • 16:9"
                    stage == Stage.BORDER -> "TV black • backlights on • snapping to 16:9 border"
                    markerStep != null -> "Video marker ${markerStep!! + 1}/8 • border tracked"
                    else -> "Watching Beta 9 sync marker"
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).zIndex(3f),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    @Composable
    private fun AnalyzingScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(52.dp), strokeWidth = 5.dp)
            Text("Analyzing calibration", modifier = Modifier.padding(top = 18.dp), style = MaterialTheme.typography.headlineSmall)
            Text(statusMessage, modifier = Modifier.padding(top = 10.dp))
        }
    }

    @Composable
    private fun ResultsScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (solveError == null) "Calibration complete" else "Calibration needs another pass", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(resultText, modifier = Modifier.padding(16.dp), fontFamily = FontFamily.Monospace)
            }
            commitStatus?.let { StatusCard(it) }
            if (solvedResult != null && solveError == null) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !committing,
                    onClick = { commitCalibration() },
                ) { Text(if (committing) "Committing…" else "Commit calibration values to HyperHDR") }
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !committing, onClick = { restartForSameTarget() }) { Text("Run calibration again") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !committing, onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun StatusCard(text: String) {
        ElevatedCard(Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp)) }
    }

    @Composable
    private fun InstructionCard(number: String, title: String, body: String) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Text(number, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    private fun discoverTargets() {
        if (scanning || executor.isShutdown) return
        scanning = true
        targets = emptyList()
        statusMessage = "Searching your local network for HyperHDR…"
        executor.execute {
            val found = mutableListOf<HyperHdrTarget>()
            val errors = mutableListOf<String>()
            val servers = runCatching { SsdpDiscovery(this).discover() }.getOrElse {
                errors += (it.message ?: "SSDP discovery failed")
                emptyList()
            }
            servers.forEach { server ->
                val probe = HyperHdrClient(server)
                try {
                    probe.discoverInstances().forEach { found += HyperHdrTarget(server, it) }
                } catch (e: Exception) {
                    errors += "${server.name}: ${e.message}"
                } finally {
                    probe.close()
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                targets = found.sortedWith(compareBy({ it.server.host }, { it.instance.instanceId }))
                scanning = false
                statusMessage = when {
                    targets.isNotEmpty() -> "Found ${targets.size} HyperHDR instance${if (targets.size == 1) "" else "s"}."
                    errors.isNotEmpty() -> errors.joinToString("\n")
                    else -> "No HyperHDR SSDP responses were found."
                }
            }
        }
    }

    private fun connectToTarget(target: HyperHdrTarget) {
        if (busy) return
        busy = true
        statusMessage = "Connecting to ${target.displayName}…"
        executor.execute {
            val newClient = HyperHdrClient(target.server)
            try {
                newClient.connectTo(target.instance.instanceId)
                runOnUiThread {
                    if (isDestroyed) { newClient.close(); return@runOnUiThread }
                    client?.close()
                    client = newClient
                    selectedTarget = target
                    busy = false
                    statusMessage = "Connected to ${target.displayName}."
                    stage = Stage.INTRO
                }
            } catch (e: Exception) {
                newClient.close()
                runOnUiThread {
                    busy = false
                    statusMessage = "Connection failed: ${e.message}"
                }
            }
        }
    }

    private fun beginCalibration() {
        resetMeasurements()
        generation++
        stage = Stage.BORDER
        autoProgress = "Border acquisition"
        statusMessage = "Pause the Beta 9 video at 0:00 BLACK. Opening the camera; the app will turn the backlights on to find the TV border."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prepareCameraChoices()
            startCameraIfPossible()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun resetMeasurements() {
        tvMeasurements.clear()
        ledMeasurements.clear()
        ledSpatialMeasurements.clear()
        wallDiagnostics.clear()
        rawTvRect = null
        previewTvRect = null
        borderCandidate = null
        borderStableFrames = 0
        borderLocked = false
        borderLightingActive = false
        videoArmed = false
        markerStep = null
        markerStableFrames = 0
        lastObservedStep = null
        previewTick = 0
        latestMeasurement = null
        resultText = ""
        solveError = null
        solvedResult = null
        commitStatus = null
        committing = false
        cameraError = null
    }

    private fun startBorderLighting() {
        if (borderLightingActive || busy || stage != Stage.BORDER) return
        val token = generation
        busy = true
        statusMessage = "Turning the backlights WHITE so the black 16:9 TV edge is easy to see…"
        executor.execute {
            try {
                client?.setColor(Patch.WHITE.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(500)
                uiIfCurrent(token) {
                    borderLightingActive = true
                    busy = false
                    statusMessage = "Backlights ON. Keep the video paused on BLACK while the box snaps to the TV border."
                }
            } catch (e: Exception) {
                uiIfCurrent(token) {
                    busy = false
                    statusMessage = "Could not enable the border-identification backlight: ${e.message}"
                }
            }
        }
    }

    private fun analyzeBorderPreview() {
        val frame = readPreviewFrame() ?: return
        val prior = borderCandidate
        val candidate = if (prior == null) {
            VideoSyncAnalyzer.detectBlackTvWithHalo(frame)
        } else {
            VideoSyncAnalyzer.refineBorder(frame, prior)
        }
        if (candidate == null) {
            borderStableFrames = 0
            borderCandidate = null
            return
        }
        borderCandidate = candidate
        previewTvRect = candidate
        borderStableFrames = if (prior != null && rectClose(prior, candidate)) borderStableFrames + 1 else 1
        statusMessage = "Snapping to 16:9 TV border • stable $borderStableFrames/${CalibrationProtocol.STABLE_BORDER_FRAMES}"
        if (borderStableFrames >= CalibrationProtocol.STABLE_BORDER_FRAMES) lockBorder(candidate)
    }

    private fun lockBorder(rect: NormalizedRect) {
        if (busy || borderLocked) return
        val token = generation
        busy = true
        previewTvRect = rect
        statusMessage = "TV border found. Turning the backlights OFF before calibration…"
        executor.execute {
            try {
                client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(350)
                uiIfCurrent(token) {
                    borderLightingActive = false
                    borderLocked = true
                    busy = false
                    autoProgress = "BORDER LOCKED"
                    statusMessage = "BORDER LOCKED. The box is snapped to the TV edge. Press START VIDEO NOW, then start playback."
                }
            } catch (e: Exception) {
                uiIfCurrent(token) {
                    busy = false
                    statusMessage = "Border found, but the backlights could not be turned off: ${e.message}"
                }
            }
        }
    }

    private fun reacquireBorder() {
        borderLocked = false
        borderStableFrames = 0
        borderCandidate = null
        previewTvRect = null
        markerStep = null
        startBorderLighting()
    }

    private fun armVideoSequence() {
        if (busy || !cameraReady || !borderLocked) return
        videoArmed = true
        markerStep = null
        markerStableFrames = 0
        lastObservedStep = null
        stage = Stage.CALIBRATION
        autoProgress = "TV 0/${CalibrationProtocol.tvSequence.size}"
        statusMessage = "START THE VIDEO NOW. Marker synchronization is automatic; you do not need to press Capture."
    }

    private fun analyzeVideoPreview() {
        val frame = readPreviewFrame() ?: return
        var border = previewTvRect ?: return

        previewTick++
        if (previewTick % 2 == 0) {
            border = VideoSyncAnalyzer.refineBorder(frame, border)
            previewTvRect = border
        }

        val reading = VideoSyncAnalyzer.decodeMarker(frame, border)
        if (reading == null || reading.step !in CalibrationProtocol.tvSequence.indices) {
            markerStep = null
            markerStableFrames = 0
            return
        }

        val step = reading.step
        val patch = CalibrationProtocol.tvSequence[step]
        lastObservedStep = step
        if (markerStep == step) markerStableFrames++ else {
            markerStep = step
            markerStableFrames = 1
        }

        statusMessage = "Marker: ${patch.label.uppercase()} • confidence ${"%.0f".format(reading.confidence * 100)}% • stable $markerStableFrames/${CalibrationProtocol.STABLE_MARKER_FRAMES}"
        if (markerStableFrames < CalibrationProtocol.STABLE_MARKER_FRAMES) return

        if (tvMeasurements.containsKey(patch)) {
            autoProgress = "TV ${tvMeasurements.size}/${CalibrationProtocol.tvSequence.size} • ${patch.label} already captured"
            if (patch == Patch.BLACK && missingTvPatches().isEmpty()) startLedPhaseFromExistingBlack()
            return
        }

        if (patch != Patch.WHITE && !tvMeasurements.containsKey(Patch.WHITE)) {
            statusMessage = "Saw ${patch.label.uppercase()}, but WHITE has not been captured yet. Continue the video, then replay it once; captured values are retained."
            return
        }
        captureTvPatch(patch)
    }

    private fun startLedPhaseFromExistingBlack() {
        if (busy || !videoArmed || missingTvPatches().isNotEmpty()) return
        val camera = sampler ?: return
        val token = generation
        busy = true
        videoArmed = false
        statusMessage = "All TV references are present and final BLACK is on-screen. Starting LED-wall calibration…"
        activeCaptureJob = executor.submit { runAutomaticLedPhase(camera, token) }
    }

    private fun captureTvPatch(patch: Patch) {
        if (busy || tvMeasurements.containsKey(patch)) return
        val camera = sampler ?: return
        val token = generation
        busy = true
        markerStableFrames = 0
        statusMessage = "Measuring TV ${patch.label} from the locked camera stream…"
        activeCaptureJob = executor.submit {
            try {
                if (patch == Patch.WHITE && tvMeasurements.isEmpty()) {
                    camera.setLocks(exposureLocked = false, whiteBalanceLocked = false)
                    Thread.sleep(850)
                    ensureCurrent(token)
                    camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
                    Thread.sleep(250)
                }
                ensureCurrent(token)
                val spatial = camera.measureSpatial(samples = 5, timeoutMs = 7000)
                ensureCurrent(token)
                val rect = when {
                    rawTvRect == null -> SpatialCalibration.detectTvRect(spatial)
                    patch == Patch.BLACK -> requireNotNull(rawTvRect)
                    else -> SpatialCalibration.trackTvRect(spatial, requireNotNull(rawTvRect)) ?: requireNotNull(rawTvRect)
                }
                rawTvRect = rect
                val sample = SpatialCalibration.screenColor(spatial, rect)
                tvMeasurements[patch] = sample
                val captured = "TV ${patch.label}: %.4f, %.4f, %.4f • %s".format(sample.r, sample.g, sample.b, camera.lastMeasurementSummary)

                val allCaptured = missingTvPatches().isEmpty()
                val finalBlackOnScreen = lastObservedStep == CalibrationProtocol.tvSequence.lastIndex
                uiIfCurrent(token) {
                    latestMeasurement = captured
                    busy = allCaptured && finalBlackOnScreen
                    autoProgress = "TV ${tvMeasurements.size}/${CalibrationProtocol.tvSequence.size}"
                    statusMessage = when {
                        allCaptured && finalBlackOnScreen -> "All TV references captured on final BLACK. Starting LED-wall calibration automatically…"
                        allCaptured -> "All TV references are captured. Continue playback until the final BLACK marker to start the LED phase."
                        finalBlackOnScreen -> "Final BLACK reached; still missing ${missingTvPatches().joinToString { it.label }}. Restart the video at 0:00; captured colors are retained."
                        else -> "Captured ${patch.label.uppercase()}. Waiting for the next marker; missed colors can be recovered on another pass."
                    }
                }
                if (allCaptured && finalBlackOnScreen) {
                    videoArmed = false
                    runAutomaticLedPhase(camera, token)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                uiIfCurrent(token) {
                    busy = false
                    statusMessage = "TV ${patch.label} capture failed: ${e.message}. If that patch is still on-screen the app will retry; otherwise it can recover it on the next video pass."
                }
            }
        }
    }

    private fun runAutomaticLedPhase(camera: CameraSampler, token: Int) {
        try {
            ensureCurrent(token)
            uiIfCurrent(token) {
                autoProgress = "LED 1/${CalibrationProtocol.ledSequence.size}"
                statusMessage = "TV remains on final BLACK. Establishing LED WHITE exposure…"
            }
            camera.setLocks(exposureLocked = false, whiteBalanceLocked = true)
            client?.setColor(Patch.WHITE.rgb) ?: error("HyperHDR is not connected")
            Thread.sleep(CalibrationProtocol.WHITE_EXPOSURE_SETTLE_MS)
            ensureCurrent(token)
            camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
            Thread.sleep(250)
            val white = camera.measureSpatial(samples = 5, timeoutMs = 7000)
            ledSpatialMeasurements[Patch.WHITE] = white
            uiIfCurrent(token) { latestMeasurement = "LED White spatial field • ${camera.lastMeasurementSummary}" }

            client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
            Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
            val blackStart = camera.measureSpatial(samples = 5, timeoutMs = 7000)

            val colors = listOf(Patch.RED, Patch.GREEN, Patch.BLUE, Patch.CYAN, Patch.MAGENTA, Patch.YELLOW)
            colors.forEachIndexed { i, patch ->
                ensureCurrent(token)
                uiIfCurrent(token) {
                    autoProgress = "LED ${i + 2}/${CalibrationProtocol.ledSequence.size}"
                    statusMessage = "TV BLACK • commanding and measuring LED ${patch.label.uppercase()}…"
                }
                client?.setColor(patch.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
                ledSpatialMeasurements[patch] = camera.measureSpatial(samples = 5, timeoutMs = 7000)
                uiIfCurrent(token) { latestMeasurement = "LED ${patch.label} spatial field • ${camera.lastMeasurementSummary}" }
            }

            ensureCurrent(token)
            uiIfCurrent(token) {
                autoProgress = "LED ${CalibrationProtocol.ledSequence.size}/${CalibrationProtocol.ledSequence.size}"
                statusMessage = "Capturing final LED BLACK ambient baseline…"
            }
            client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
            Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
            val blackEnd = camera.measureSpatial(samples = 5, timeoutMs = 7000)
            ledSpatialMeasurements[Patch.BLACK] = SpatialCalibration.medianCombine(listOf(blackStart, blackEnd))

            uiIfCurrent(token) {
                statusMessage = "All measurements captured. Aligning wall fields and solving calibration…"
                stage = Stage.ANALYZING
            }
            finalizeLedSpatialAndSolve(token)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            runCatching { client?.clear() }
            uiIfCurrent(token) {
                busy = false
                solveError = e.message ?: e.javaClass.simpleName
                resultText = "Automatic LED measurement failed\n\n$solveError\n\nNormal HyperHDR control has been restored."
                statusMessage = "Calibration could not finish."
                closeCamera()
                stage = Stage.RESULTS
            }
        }
    }

    private fun finalizeLedSpatialAndSolve(token: Int) {
        try {
            ensureCurrent(token)
            val rect = rawTvRect ?: error("TV rectangle is missing")
            val black = ledSpatialMeasurements[Patch.BLACK] ?: error("LED black baseline is missing")
            val white = ledSpatialMeasurements[Patch.WHITE] ?: error("LED white field is missing")
            val model = SpatialCalibration.buildWallReference(white, black, rect)

            ledMeasurements.clear()
            wallDiagnostics.clear()
            for (patch in CalibrationProtocol.ledSequence) {
                if (patch == Patch.BLACK) {
                    ledMeasurements[patch] = Rgb(0.0, 0.0, 0.0)
                } else {
                    val frame = ledSpatialMeasurements[patch] ?: error("Missing LED ${patch.label} spatial field")
                    val result = SpatialCalibration.wallColor(frame, black, model)
                    ledMeasurements[patch] = result.rgb
                    wallDiagnostics[patch] = result
                }
            }
            runCatching { client?.clear() }
            val solved = CalibrationEngine.solve(tvMeasurements, ledMeasurements)
            solvedResult = solved
            solveError = null
            resultText = buildString {
                appendLine("Suggested HyperHDR full ICE LED calibration values")
                appendLine()
                for (patch in Patch.entries) {
                    val c = solved.targets.getValue(patch)
                    appendLine("${patch.label.padEnd(8)} [${c[0]}, ${c[1]}, ${c[2]}]")
                }
                appendLine()
                appendLine("Estimated relative validation error: %.1f → %.1f".format(solved.estimatedErrorBefore, solved.estimatedErrorAfter))
                appendLine("Brightness is intentionally not calibrated.")
                appendLine()
                appendLine("Spatial wall analysis")
                wallDiagnostics.forEach { (patch, d) ->
                    appendLine("${patch.label.padEnd(8)} ${d.tilesUsed}/${d.availableTiles} tiles • gradient ${"%.1f".format(d.brightnessGradient)}× • alignment ${d.alignmentDx},${d.alignmentDy} • spread ${"%.3f".format(d.chromaSpread)}")
                }
                solved.warning?.let { appendLine("Warning: $it") }
            }
            uiIfCurrent(token) {
                busy = false
                closeCamera()
                statusMessage = "Calibration solved. Review the values, then use Commit calibration values to HyperHDR if they look reasonable."
                stage = Stage.RESULTS
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            runCatching { client?.clear() }
            uiIfCurrent(token) {
                solveError = e.message ?: e.javaClass.simpleName
                solvedResult = null
                resultText = buildString {
                    appendLine("Calibration solve failed")
                    appendLine()
                    appendLine(solveError)
                    appendLine()
                    append(measurementDiagnostics())
                }
                busy = false
                closeCamera()
                statusMessage = "Calibration measurements need another pass."
                stage = Stage.RESULTS
            }
        }
    }

    private fun commitCalibration() {
        val solved = solvedResult ?: return
        if (committing) return
        committing = true
        commitStatus = "Applying and saving the eight ICE calibration anchors…"
        executor.execute {
            try {
                client?.commitCalibration(solved.targets) ?: error("HyperHDR is not connected")
                runOnUiThread {
                    committing = false
                    commitStatus = "Calibration values committed successfully to ${selectedTarget?.displayName ?: "HyperHDR"}."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    committing = false
                    commitStatus = "Could not persist the calibration: ${e.message}. HyperHDR may require admin authorization for config changes; the values remain shown above so nothing is lost."
                }
            }
        }
    }

    private fun missingTvPatches(): List<Patch> = CalibrationProtocol.tvSequence.filterNot(tvMeasurements::containsKey)

    private fun capturedPatchLabels(): String = if (tvMeasurements.isEmpty()) "none" else tvMeasurements.keys.joinToString { it.label }

    private fun measurementDiagnostics(): String = buildString {
        for (patch in Patch.entries) {
            val tv = tvMeasurements[patch]
            val led = ledMeasurements[patch]
            append("${patch.label.padEnd(8)} TV=")
            append(if (tv == null) "missing" else "%.4f,%.4f,%.4f".format(tv.r, tv.g, tv.b))
            append(" LED=")
            append(if (led == null) "missing" else "%.4f,%.4f,%.4f".format(led.r, led.g, led.b))
            appendLine()
        }
    }

    private fun readPreviewFrame(): PreviewFrame? {
        val view = previewView ?: return null
        if (!view.isAvailable) return null
        val bitmap = runCatching {
            view.getBitmap(CalibrationProtocol.PREVIEW_SAMPLE_WIDTH, CalibrationProtocol.PREVIEW_SAMPLE_HEIGHT)
        }.getOrNull() ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return PreviewAnalyzer.fromArgb(pixels, CalibrationProtocol.PREVIEW_SAMPLE_WIDTH, CalibrationProtocol.PREVIEW_SAMPLE_HEIGHT)
    }

    private fun rectClose(a: NormalizedRect, b: NormalizedRect): Boolean {
        return abs(a.left - b.left) < 0.035 && abs(a.top - b.top) < 0.035 &&
            abs(a.right - b.right) < 0.035 && abs(a.bottom - b.bottom) < 0.035
    }

    private fun prepareCameraChoices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val choices = CameraCatalog.list(manager)
            cameraChoices = choices
            val chosen = selectedCameraChoiceKey?.let { key -> choices.firstOrNull { it.key == key } }
                ?: CameraSelection.chooseMain(choices)
            selectedCameraChoiceKey = chosen.key
            cameraStatus = "Selected: ${chosen.displayName()}${if (choices.size > 1) " • Switch rear camera if needed" else ""}"
        }.onFailure {
            cameraError = "Could not enumerate rear cameras: ${it.message}"
        }
    }

    private fun selectedCameraChoice(): CameraChoice? = cameraChoices.firstOrNull { it.key == selectedCameraChoiceKey }

    private fun switchRearCamera() {
        if (busy || stage != Stage.BORDER || borderLocked || cameraChoices.size < 2) return
        val current = cameraChoices.indexOfFirst { it.key == selectedCameraChoiceKey }.coerceAtLeast(0)
        val next = cameraChoices[(current + 1) % cameraChoices.size]
        selectedCameraChoiceKey = next.key
        cameraStatus = "Switching to ${next.displayName()}…"
        borderLightingActive = false
        closeSamplerOnly()
        startCameraIfPossible()
    }

    private fun bindPreviewView(view: TextureView) {
        if (previewView !== view) {
            closeSamplerOnly()
            previewView = view
            cameraReady = false
        }
        startCameraIfPossible()
    }

    private fun startCameraIfPossible() {
        if (sampler != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val view = previewView ?: return
        if (cameraChoices.isEmpty()) prepareCameraChoices()
        val camera = CameraSampler(this, view, selectedCameraChoice())
        sampler = camera
        camera.start(
            onReady = { msg ->
                if (sampler === camera && !isDestroyed) {
                    cameraReady = true
                    cameraError = null
                    cameraStatus = msg
                    selectedCameraChoiceKey = camera.cameraChoiceKey ?: selectedCameraChoiceKey
                    PreviewGeometry.configure(view)
                    if (stage == Stage.BORDER && !borderLocked) startBorderLighting()
                }
            },
            onError = { error ->
                if (sampler === camera && !isDestroyed) {
                    cameraReady = false
                    cameraError = error
                }
            },
        )
    }

    private fun closeSamplerOnly() {
        val old = sampler
        sampler = null
        cameraReady = false
        old?.close()
    }

    private fun closeCamera() {
        closeSamplerOnly()
        previewView = null
    }

    private fun restartForSameTarget() {
        clearPriorityAsync(client)
        closeCamera()
        resetMeasurements()
        generation++
        stage = Stage.INTRO
        statusMessage = selectedTarget?.let { "Still connected to ${it.displayName}." } ?: "Ready."
    }

    private fun disconnectAndReturn() {
        generation++
        activeCaptureJob?.cancel(true)
        val old = client
        client = null
        closeCamera()
        resetMeasurements()
        selectedTarget = null
        stage = Stage.DISCOVERY
        Thread {
            runCatching { old?.clear() }
            old?.close()
        }.start()
        discoverTargets()
    }

    private fun clearPriorityAsync(target: HyperHdrClient?) {
        if (target == null) return
        Thread { runCatching { target.clear() } }.start()
    }

    private fun ensureCurrent(token: Int) {
        if (token != generation || Thread.currentThread().isInterrupted || isFinishing || isDestroyed) {
            throw InterruptedException("Calibration session ended")
        }
    }

    private fun uiIfCurrent(token: Int, block: () -> Unit) {
        runOnUiThread {
            if (token == generation && !isFinishing && !isDestroyed) block()
        }
    }
}

@Composable
private fun Beta9Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}