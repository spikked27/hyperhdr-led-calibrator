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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

/**
 * Beta 8 automated workflow.
 *
 * The companion video is the clock. The app watches the actual TV image, recognizes each full-screen
 * patch, captures it automatically, and then starts the complete LED-wall sequence when the video
 * reaches its long final BLACK section. The operator never has to move the phone or press Capture.
 */
class AutoCalibrationActivity : ComponentActivity() {
    private enum class Stage { DISCOVERY, INTRO, CALIBRATION, ANALYZING, RESULTS }

    private val executor = Executors.newSingleThreadExecutor()
    private val previewHandler = Handler(Looper.getMainLooper())

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

    private var videoArmed by mutableStateOf(false)
    private var expectedTvIndex by mutableStateOf(0)
    private var stablePreviewFrames = 0
    private var previewTvRect by mutableStateOf<NormalizedRect?>(null)
    private var previewWhiteReference: Rgb? = null
    private var autoProgress by mutableStateOf("Ready")
    private var latestMeasurement by mutableStateOf<String?>(null)
    private var resultText by mutableStateOf("")
    private var solveError by mutableStateOf<String?>(null)

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
            if (stage == Stage.CALIBRATION && videoArmed && !busy && cameraReady && expectedTvIndex < CalibrationProtocol.tvSequence.size) {
                analyzePreviewForVideo()
            }
            previewHandler.postDelayed(this, CalibrationProtocol.PREVIEW_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Beta8Theme { AppContent() } }
        previewHandler.post(previewRunnable)
        discoverTargets()
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
                            Text("HyperHDR LED Calibrator", fontWeight = FontWeight.SemiBold)
                            selectedTarget?.let {
                                if (stage != Stage.DISCOVERY) Text(it.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (stage) {
                    Stage.DISCOVERY -> DiscoveryScreen()
                    Stage.INTRO -> IntroScreen()
                    Stage.CALIBRATION -> CalibrationScreen()
                    Stage.ANALYZING -> AnalyzingScreen()
                    Stage.RESULTS -> ResultsScreen()
                }
            }
        }
    }

    @Composable
    private fun DiscoveryScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Choose a HyperHDR instance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Select the exact instance this calibration session should control.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Searching with HyperHDR SSDP…")
                }
            }
            StatusCard(statusMessage)
            targets.forEach { target ->
                ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(target.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${target.server.host}:${target.server.jsonPort} • Instance ${target.instance.instanceId}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = target.instance.running && !busy,
                            onClick = { connectToTarget(target) },
                        ) { Text(if (busy) "Connecting…" else "Connect") }
                    }
                }
            }
            if (!scanning && targets.isEmpty()) Text("No running HyperHDR instances were found.")
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !scanning && !busy, onClick = { discoverTargets() }) {
                Text("Scan again")
            }
        }
    }

    @Composable
    private fun IntroScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Automatic video calibration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            InstructionCard("1", "Cue the matching video", "Load the Beta 8 calibration video on the TV and pause it at 0:00. The first ${CalibrationProtocol.VIDEO_LEAD_IN_SECONDS} seconds are black so playback controls can disappear before WHITE begins.")
            InstructionCard("2", "Frame TV + wall", "Keep the complete TV and a useful border of wall visible. A tripod is best, but the app now tracks small hand movement during TV capture and spatially aligns the LED-wall measurements.")
            InstructionCard("3", "One button starts everything", "Choose the correct rear camera, press START VIDEO NOW in the next screen, then immediately start playback. The app recognizes WHITE → RED → GREEN → BLUE → CYAN → MAGENTA → YELLOW → BLACK and takes every TV measurement automatically.")
            InstructionCard("4", "Leave the video playing", "The final BLACK patch lasts ${CalibrationProtocol.FINAL_BLACK_SECONDS} seconds. When the app sees it, it automatically measures every LED color while the TV remains black.")
            Button(modifier = Modifier.fillMaxWidth(), onClick = { beginCalibration() }) { Text("Open camera") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun CalibrationScreen() {
        val expected = CalibrationProtocol.tvSequence.getOrNull(expectedTvIndex)
        val canChangeCamera = !videoArmed && !busy && cameraChoices.size > 1

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    !videoArmed -> "Ready to start"
                    expected != null -> "TV auto-capture • ${expected.label}"
                    else -> "LED auto-capture"
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

            if (canChangeCamera) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { switchRearCamera() }) {
                    Text("Switch rear camera")
                }
            }

            if (!videoArmed) {
                Text(
                    "Cue the companion video at 0:00. Press below, then start playback on the TV. Do not press Capture—the app handles the complete sequence.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = cameraReady && !busy,
                    onClick = { armVideoSequence() },
                ) { Text(if (busy) "Preparing…" else "START VIDEO NOW") }
            } else if (expected != null) {
                Text(
                    "Watching the TV for ${expected.label.uppercase()}. Hold the phone roughly steady; the white outline follows the detected screen before each capture.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { restartAutoSequence() }) {
                    Text("Restart video detection")
                }
            } else {
                Text("TV reference sequence is complete. Keep the video playing on BLACK while the app controls and measures the LEDs.")
            }
        }
    }

    @Composable
    private fun CameraPreview() {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> TextureView(context).also { bindPreviewView(it) } },
                update = { bindPreviewView(it) },
            )

            val rect = previewTvRect
            if (rect != null) {
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * rect.left.toFloat(), y = maxHeight * rect.top.toFloat())
                        .width(maxWidth * rect.width.toFloat())
                        .height(maxHeight * rect.height.toFloat())
                        .border(3.dp, Color.White, RoundedCornerShape(6.dp)),
                )
            }

            Text(
                if (rect == null) "Keep full TV + surrounding wall visible" else "TV tracked automatically",
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
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
            CircularProgressIndicator(modifier = Modifier.size(54.dp), strokeWidth = 5.dp)
            Text("Analyzing calibration", modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(statusMessage, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun ResultsScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (solveError == null) "Calibration complete" else "Calibration needs another pass", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(resultText, modifier = Modifier.padding(20.dp), fontFamily = FontFamily.Monospace)
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = { restartForSameTarget() }) { Text("Run calibration again") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun StatusCard(text: String) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun InstructionCard(number: String, title: String, body: String) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Text(number, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    private fun discoverTargets() {
        if (scanning) return
        scanning = true
        targets = emptyList()
        selectedTarget = null
        statusMessage = "Searching your local network for HyperHDR…"
        executor.execute {
            val foundTargets = mutableListOf<HyperHdrTarget>()
            val errors = mutableListOf<String>()
            val servers = runCatching { SsdpDiscovery(this).discover() }.getOrElse {
                errors += (it.message ?: "SSDP discovery failed")
                emptyList()
            }
            servers.forEach { server ->
                val probe = HyperHdrClient(server)
                try {
                    probe.discoverInstances().forEach { instance -> foundTargets += HyperHdrTarget(server, instance) }
                } catch (e: Exception) {
                    errors += "${server.name}: ${e.message}"
                } finally {
                    probe.close()
                }
            }
            runOnUiThread {
                targets = foundTargets.sortedWith(compareBy({ it.server.host }, { it.instance.instanceId }))
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
        stage = Stage.CALIBRATION
        statusMessage = "Choose the normal 1× rear camera, frame the complete TV plus wall, and cue the companion video at 0:00."
        autoProgress = "Waiting to start"
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
        previewWhiteReference = null
        expectedTvIndex = 0
        stablePreviewFrames = 0
        videoArmed = false
        latestMeasurement = null
        solveError = null
        resultText = ""
        cameraError = null
    }

    private fun armVideoSequence() {
        if (busy || !cameraReady || videoArmed) return
        busy = true
        statusMessage = "Forcing HyperHDR backlights off…"
        executor.execute {
            try {
                client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(300)
                runOnUiThread {
                    busy = false
                    videoArmed = true
                    expectedTvIndex = 0
                    stablePreviewFrames = 0
                    previewTvRect = null
                    previewWhiteReference = null
                    autoProgress = "TV 1 of ${CalibrationProtocol.tvSequence.size}"
                    statusMessage = "START THE VIDEO NOW. Waiting for the full-screen WHITE patch; captures are automatic."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    statusMessage = "Could not turn off the backlights: ${e.message}"
                }
            }
        }
    }

    private fun restartAutoSequence() {
        if (busy) return
        tvMeasurements.clear()
        ledMeasurements.clear()
        ledSpatialMeasurements.clear()
        rawTvRect = null
        previewTvRect = null
        previewWhiteReference = null
        expectedTvIndex = 0
        stablePreviewFrames = 0
        videoArmed = true
        autoProgress = "TV 1 of ${CalibrationProtocol.tvSequence.size}"
        statusMessage = "Restart the companion video from 0:00. Waiting for WHITE."
    }

    private fun analyzePreviewForVideo() {
        val view = previewView ?: return
        if (!view.isAvailable) return
        val bitmap = runCatching {
            view.getBitmap(CalibrationProtocol.PREVIEW_SAMPLE_WIDTH, CalibrationProtocol.PREVIEW_SAMPLE_HEIGHT)
        }.getOrNull() ?: return
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        val frame = PreviewAnalyzer.fromArgb(pixels, CalibrationProtocol.PREVIEW_SAMPLE_WIDTH, CalibrationProtocol.PREVIEW_SAMPLE_HEIGHT)
        val expected = CalibrationProtocol.tvSequence.getOrNull(expectedTvIndex) ?: return

        val rect = if (expected == Patch.WHITE && previewTvRect == null) {
            PreviewAnalyzer.detectWhiteTv(frame)
        } else {
            val previous = previewTvRect ?: return
            val white = previewWhiteReference ?: return
            PreviewAnalyzer.trackExpectedRect(frame, previous, expected, white)
        }

        if (rect == null) {
            stablePreviewFrames = 0
            return
        }
        previewTvRect = rect
        val sample = PreviewAnalyzer.sample(frame, rect)
        if (expected == Patch.WHITE && previewWhiteReference == null) previewWhiteReference = sample
        val white = previewWhiteReference ?: sample

        if (PreviewAnalyzer.matchesExpected(sample, expected, white)) {
            stablePreviewFrames++
            statusMessage = "Detected ${expected.label.uppercase()} • stabilizing ${stablePreviewFrames}/${CalibrationProtocol.STABLE_PREVIEW_FRAMES}…"
            if (stablePreviewFrames >= CalibrationProtocol.STABLE_PREVIEW_FRAMES) {
                stablePreviewFrames = 0
                captureTvPatch(expected)
            }
        } else {
            stablePreviewFrames = 0
        }
    }

    private fun captureTvPatch(patch: Patch) {
        if (busy || CalibrationProtocol.tvSequence.getOrNull(expectedTvIndex) != patch) return
        val camera = sampler ?: return
        busy = true
        statusMessage = "Automatically measuring TV ${patch.label}…"
        executor.execute {
            try {
                if (patch == Patch.WHITE && tvMeasurements.isEmpty()) {
                    camera.setLocks(exposureLocked = false, whiteBalanceLocked = false)
                    Thread.sleep(850)
                    camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
                    Thread.sleep(250)
                }

                val frame = camera.measureSpatial(samples = 5, timeoutMs = 7000)
                val rect = when {
                    rawTvRect == null -> SpatialCalibration.detectTvRect(frame)
                    patch == Patch.BLACK -> requireNotNull(rawTvRect)
                    else -> SpatialCalibration.trackTvRect(frame, requireNotNull(rawTvRect)) ?: requireNotNull(rawTvRect)
                }
                rawTvRect = rect
                val sample = SpatialCalibration.screenColor(frame, rect)
                tvMeasurements[patch] = sample
                val captured = "TV ${patch.label}: %.4f, %.4f, %.4f • %s".format(sample.r, sample.g, sample.b, camera.lastMeasurementSummary)

                if (patch == Patch.BLACK) {
                    runOnUiThread {
                        expectedTvIndex = CalibrationProtocol.tvSequence.size
                        autoProgress = "TV complete • LED automation starting"
                        latestMeasurement = captured
                        statusMessage = "Final BLACK detected. Keep the video playing; starting automatic LED-wall measurements…"
                    }
                    runAutomaticLedPhase(camera)
                } else {
                    val next = expectedTvIndex + 1
                    runOnUiThread {
                        expectedTvIndex = next
                        latestMeasurement = captured
                        busy = false
                        autoProgress = "TV ${next + 1} of ${CalibrationProtocol.tvSequence.size}"
                        statusMessage = "TV ${patch.label.uppercase()} captured. Waiting for ${CalibrationProtocol.tvSequence[next].label.uppercase()}…"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    stablePreviewFrames = 0
                    statusMessage = "TV measurement failed: ${e.message}. The app will keep waiting for ${patch.label.uppercase()}."
                }
            }
        }
    }

    private fun runAutomaticLedPhase(camera: CameraSampler) {
        try {
            runOnUiThread {
                autoProgress = "LED 1 of ${CalibrationProtocol.ledSequence.size}"
                statusMessage = "TV stays BLACK. Establishing LED WHITE exposure…"
            }

            camera.setLocks(exposureLocked = false, whiteBalanceLocked = true)
            client?.setColor(Patch.WHITE.rgb) ?: error("HyperHDR is not connected")
            Thread.sleep(CalibrationProtocol.WHITE_EXPOSURE_SETTLE_MS)
            camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
            Thread.sleep(250)
            val white = camera.measureSpatial(samples = 5, timeoutMs = 7000)
            ledSpatialMeasurements[Patch.WHITE] = white
            runOnUiThread { latestMeasurement = "LED White spatial field • ${camera.lastMeasurementSummary}" }

            client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
            Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
            val blackStart = camera.measureSpatial(samples = 5, timeoutMs = 7000)

            val colors = listOf(Patch.RED, Patch.GREEN, Patch.BLUE, Patch.CYAN, Patch.MAGENTA, Patch.YELLOW)
            colors.forEachIndexed { i, patch ->
                runOnUiThread {
                    autoProgress = "LED ${i + 2} of ${CalibrationProtocol.ledSequence.size}"
                    statusMessage = "TV remains BLACK • setting LED ${patch.label.uppercase()}…"
                }
                client?.setColor(patch.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
                val frame = camera.measureSpatial(samples = 5, timeoutMs = 7000)
                ledSpatialMeasurements[patch] = frame
                runOnUiThread { latestMeasurement = "LED ${patch.label} spatial field • ${camera.lastMeasurementSummary}" }
            }

            runOnUiThread {
                autoProgress = "LED ${CalibrationProtocol.ledSequence.size} of ${CalibrationProtocol.ledSequence.size}"
                statusMessage = "Capturing final LED BLACK ambient baseline…"
            }
            client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
            Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
            val blackEnd = camera.measureSpatial(samples = 5, timeoutMs = 7000)
            ledSpatialMeasurements[Patch.BLACK] = SpatialCalibration.medianCombine(listOf(blackStart, blackEnd))

            runOnUiThread {
                statusMessage = "All automatic measurements captured. Aligning wall fields and solving calibration…"
                stage = Stage.ANALYZING
            }
            finalizeLedSpatialAndSolve()
        } catch (e: Exception) {
            runCatching { client?.clear() }
            runOnUiThread {
                busy = false
                solveError = e.message ?: e.javaClass.simpleName
                resultText = "Automatic LED measurement failed\n\n${solveError}\n\nNormal HyperHDR control has been restored."
                statusMessage = "Calibration could not finish."
                closeCamera()
                stage = Stage.RESULTS
            }
        }
    }

    private fun finalizeLedSpatialAndSolve() {
        try {
            val rect = rawTvRect ?: error("TV rectangle is missing")
            val black = ledSpatialMeasurements[Patch.BLACK] ?: error("LED black baseline is missing")
            val white = ledSpatialMeasurements[Patch.WHITE] ?: error("LED white field is missing")
            val model = SpatialCalibration.buildWallReference(white, black, rect)

            ledMeasurements.clear()
            wallDiagnostics.clear()
            for (patch in CalibrationProtocol.ledSequence) {
                if (patch == Patch.BLACK) {
                    ledMeasurements[patch] = Rgb(0.0, 0.0, 0.0)
                    continue
                }
                val frame = ledSpatialMeasurements[patch] ?: error("Missing LED ${patch.label} spatial field")
                val result = SpatialCalibration.wallColor(frame, black, model)
                ledMeasurements[patch] = result.rgb
                wallDiagnostics[patch] = result
            }
            runCatching { client?.clear() }
            finishSolve()
        } catch (e: Exception) {
            runCatching { client?.clear() }
            throw e
        }
    }

    private fun finishSolve() {
        try {
            val solved = CalibrationEngine.solve(tvMeasurements, ledMeasurements)
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
                    appendLine("${patch.label.padEnd(8)} ${d.tilesUsed}/${d.availableTiles} tiles • gradient ${"%.1f".format(d.brightnessGradient)}× • alignment ${d.alignmentDx},${d.alignmentDy} tiles • chroma spread ${"%.3f".format(d.chromaSpread)}")
                }
                solved.warning?.let { appendLine("Warning: $it") }
            }
            runOnUiThread {
                busy = false
                closeCamera()
                statusMessage = "Calibration solved from the synchronized video + automatic LED sequence."
                stage = Stage.RESULTS
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            runCatching { client?.clear() }
            runOnUiThread {
                solveError = reason
                resultText = buildString {
                    appendLine("Calibration solve failed")
                    appendLine()
                    appendLine(reason)
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

    private fun prepareCameraChoices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val choices = CameraCatalog.list(manager)
            cameraChoices = choices
            val chosen = selectedCameraChoiceKey?.let { key -> choices.firstOrNull { it.key == key } }
                ?: CameraSelection.chooseMain(choices)
            selectedCameraChoiceKey = chosen.key
            cameraStatus = "Selected: ${chosen.displayName()}${if (choices.size > 1) " • use Switch rear camera if needed" else ""}"
        }.onFailure {
            cameraError = "Could not enumerate rear cameras: ${it.message}"
        }
    }

    private fun selectedCameraChoice(): CameraChoice? = cameraChoices.firstOrNull { it.key == selectedCameraChoiceKey }

    private fun switchRearCamera() {
        if (busy || videoArmed || cameraChoices.size < 2) return
        val current = cameraChoices.indexOfFirst { it.key == selectedCameraChoiceKey }.coerceAtLeast(0)
        val next = cameraChoices[(current + 1) % cameraChoices.size]
        selectedCameraChoiceKey = next.key
        cameraStatus = "Switching to ${next.displayName()}…"
        closeSamplerOnly()
        startCameraIfPossible()
    }

    private fun bindPreviewView(view: TextureView) {
        if (previewView !== view) {
            sampler?.close()
            sampler = null
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
                if (sampler === camera) {
                    cameraReady = true
                    cameraError = null
                    cameraStatus = msg
                    selectedCameraChoiceKey = camera.cameraChoiceKey ?: selectedCameraChoiceKey
                }
            },
            onError = { error ->
                if (sampler === camera) {
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
        runCatching { client?.clear() }
        closeCamera()
        resetMeasurements()
        stage = Stage.INTRO
        statusMessage = selectedTarget?.let { "Still connected to ${it.displayName}." } ?: "Ready."
    }

    private fun disconnectAndReturn() {
        executor.execute {
            runCatching { client?.clear() }
            client?.close()
            client = null
        }
        closeCamera()
        resetMeasurements()
        selectedTarget = null
        stage = Stage.DISCOVERY
        discoverTargets()
    }

    override fun onDestroy() {
        previewHandler.removeCallbacks(previewRunnable)
        val oldClient = client
        Thread {
            runCatching { oldClient?.clear() }
            oldClient?.close()
        }.start()
        closeCamera()
        executor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun Beta8Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
