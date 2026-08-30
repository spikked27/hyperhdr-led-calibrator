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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Beta 9.3 closes the calibration loop.
 *
 * HyperHDR's static JSON COLOR source is useful for characterization because current HyperHDR does
 * not run Infinite Color Engine processing for COMP_COLOR. Beta 9.2 mistakenly treated the solver's
 * mathematical prediction as validation. Beta 9.3 instead measures three physical states:
 *
 *  1. raw LED response using static COLOR,
 *  2. currently installed ICE calibration using a solid IMAGE input,
 *  3. newly solved candidate ICE calibration using the same solid IMAGE path.
 *
 * The original calibration is restored after validation. Commit is enabled only when the candidate
 * demonstrates real measured RGB/CMY improvement and does not materially regress the installed
 * calibration. The dedicated white channel remains unchanged and is not assigned a fake error.
 */
class Beta93CalibrationActivity : ComponentActivity() {
    private enum class Stage { DISCOVERY, INTRO, FRAMING, COUNTDOWN, BORDER, TV, MEASURING, RESULTS }

    private val executor = Executors.newSingleThreadExecutor()
    private val previewHandler = Handler(Looper.getMainLooper())
    private var activeJob: Future<*>? = null
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
    private var countdownValue by mutableStateOf<Int?>(null)

    private var videoArmed = false
    private var markerStep: Int? = null
    private var markerStableFrames = 0
    private var lastObservedStep: Int? = null
    private var autoProgress by mutableStateOf("Ready")
    private var latestMeasurement by mutableStateOf<String?>(null)

    private var resultText by mutableStateOf("")
    private var solveError by mutableStateOf<String?>(null)
    private var solvedResult by mutableStateOf<CalibrationResult?>(null)
    private var validationPassed by mutableStateOf(false)
    private var alreadyInstalled by mutableStateOf(false)
    private var commitStatus by mutableStateOf<String?>(null)
    private var committing by mutableStateOf(false)

    private var sampler: CameraSampler? = null
    private var previewView: TextureView? = null
    private var client: HyperHdrClient? = null
    private var rawTvRect: NormalizedRect? = null

    private val tvMeasurements = linkedMapOf<Patch, Rgb>()
    private val rawLedMeasurements = linkedMapOf<Patch, Rgb>()
    private val rawLedSpatial = linkedMapOf<Patch, SpatialFrame>()
    private val rawDiagnostics = linkedMapOf<Patch, WallColorResult>()

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
                    stage == Stage.TV && videoArmed && cameraReady && !busy -> analyzeVideoPreview()
                }
                previewHandler.postDelayed(this, CalibrationProtocol.PREVIEW_TICK_MS)
                previewLoopPosted = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Beta93Theme { AppContent() } }
        postPreviewLoop()
        discoverTargets()
    }

    override fun onStart() {
        super.onStart()
        postPreviewLoop()
    }

    override fun onStop() {
        generation++
        activeJob?.cancel(true)
        activeJob = null
        previewHandler.removeCallbacks(previewRunnable)
        previewLoopPosted = false
        closeCamera()
        clearPriorityAsync(client)
        borderLightingActive = false
        videoArmed = false
        busy = false
        countdownValue = null
        if (stage !in listOf(Stage.DISCOVERY, Stage.INTRO, Stage.RESULTS)) {
            resetMeasurements()
            stage = Stage.INTRO
            statusMessage = "Calibration was interrupted. Camera resources were released cleanly; start a new run."
        }
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        activeJob?.cancel(true)
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
                            Text("HyperHDR LED Calibrator • Beta 9.3", fontWeight = FontWeight.SemiBold)
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
                    Stage.FRAMING, Stage.COUNTDOWN, Stage.BORDER, Stage.TV -> CameraScreen()
                    Stage.MEASURING -> MeasuringScreen()
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
            Text("Closed-loop calibration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            InstructionCard("1", "Open the camera", "Point the phone at the TV in portrait and include the whole TV plus some illuminated wall.")
            InstructionCard("2", "Press READY", "Start the Beta 9.3 video when prompted. The backlights turn WHITE and a five-second countdown gives you time to finish framing.")
            InstructionCard("3", "Keep the TV in the guide", "The TV stays BLACK during border detection. Once the guide turns green, its position and shape remain frozen.")
            InstructionCard("4", "Let the full run finish", "After TV colors, Beta 9.3 measures raw LEDs, the currently installed calibration, and the new candidate. Commit is offered only after real measured validation.")
            Button(modifier = Modifier.fillMaxWidth(), onClick = { beginFraming() }) { Text("Open camera") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun CameraScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (stage) {
                    Stage.FRAMING -> "Frame the TV"
                    Stage.COUNTDOWN -> "START VIDEO NOW"
                    Stage.BORDER -> "Finding TV border"
                    Stage.TV -> "Automatic TV calibration"
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

            if (stage == Stage.FRAMING) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = cameraReady && !busy,
                    onClick = { readyAndStartVideo() },
                ) { Text("READY — START VIDEO") }
                if (cameraChoices.size > 1 && !busy) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { switchRearCamera() }) { Text("Switch rear camera") }
                }
            }
            if (stage == Stage.COUNTDOWN) {
                countdownValue?.let { n ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("$n", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                            Text("Keep framing the TV • backlights are ON")
                        }
                    }
                }
            }
            if (stage == Stage.BORDER) {
                Text("The white box may move while the TV is being found. It freezes and turns green when locked.")
            }
            if (stage == Stage.TV) {
                Text("Captured ${tvMeasurements.size}/${CalibrationProtocol.tvSequence.size}: ${capturedPatchLabels()}")
                val missing = missingTvPatches()
                if (lastObservedStep == CalibrationProtocol.tvSequence.lastIndex && missing.isNotEmpty()) {
                    Text("Missing: ${missing.joinToString { it.label }}. Replay from 0:00; captured colors are retained.", color = MaterialTheme.colorScheme.error)
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
                    stage == Stage.COUNTDOWN -> "START VIDEO NOW • ${countdownValue ?: 0}"
                    stage == Stage.BORDER -> "TV BLACK • backlights ON • detecting border"
                    borderLocked && stage == Stage.TV -> "GUIDE LOCKED • keep TV inside green box"
                    else -> "Point camera at the TV"
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).zIndex(3f),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    @Composable
    private fun MeasuringScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(52.dp), strokeWidth = 5.dp)
            Text("Measuring LED output", modifier = Modifier.padding(top = 18.dp), style = MaterialTheme.typography.headlineSmall)
            Text(autoProgress, modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
            Text(statusMessage, modifier = Modifier.padding(top = 8.dp))
            latestMeasurement?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun ResultsScreen() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    solveError != null -> "Calibration needs attention"
                    validationPassed -> "Calibration validated"
                    else -> "Calibration not validated"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            StatusCard(statusMessage)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(resultText, modifier = Modifier.padding(16.dp), fontFamily = FontFamily.Monospace)
            }
            commitStatus?.let { StatusCard(it) }
            if (solvedResult != null && validationPassed && !alreadyInstalled && solveError == null) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !committing,
                    onClick = { commitCalibration() },
                ) { Text(if (committing) "Committing…" else "Commit validated calibration to HyperHDR") }
            }
            if (alreadyInstalled && validationPassed) {
                StatusCard("The currently installed ICE anchors already match this candidate closely; there is nothing meaningful to recommit.")
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

    private fun beginFraming() {
        resetMeasurements()
        generation++
        stage = Stage.FRAMING
        autoProgress = "Frame TV, then press READY"
        statusMessage = "Point the phone at the TV. Nothing starts until you press READY — START VIDEO."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prepareCameraChoices()
            startCameraIfPossible()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun readyAndStartVideo() {
        if (busy || !cameraReady || stage != Stage.FRAMING) return
        val token = generation
        busy = true
        statusMessage = "Turning the backlights WHITE…"
        executor.execute {
            try {
                client?.setColor(Patch.WHITE.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(300)
                uiIfCurrent(token) {
                    borderLightingActive = true
                    busy = false
                    stage = Stage.COUNTDOWN
                    autoProgress = "START VIDEO NOW"
                    statusMessage = "START THE BETA 9.3 VIDEO NOW. Use the countdown to finish framing; the TV stays BLACK and the backlights stay ON."
                    startBorderCountdown(token)
                }
            } catch (e: Exception) {
                uiIfCurrent(token) {
                    busy = false
                    statusMessage = "Could not turn on the border-identification backlights: ${e.message}"
                }
            }
        }
    }

    private fun startBorderCountdown(token: Int) {
        fun tick(value: Int) {
            if (token != generation || isFinishing || isDestroyed || stage != Stage.COUNTDOWN) return
            if (value > 0) {
                countdownValue = value
                autoProgress = "START VIDEO NOW • border scan in ${value}s"
                previewHandler.postDelayed({ tick(value - 1) }, 1000L)
            } else {
                countdownValue = null
                borderStableFrames = 0
                borderCandidate = null
                previewTvRect = null
                stage = Stage.BORDER
                autoProgress = "Detecting TV border"
                statusMessage = "Detecting the BLACK TV against the WHITE backlight halo. Keep the whole TV in view."
            }
        }
        tick(CalibrationProtocol.BORDER_COUNTDOWN_SECONDS)
    }

    private fun analyzeBorderPreview() {
        val frame = readPreviewFrame() ?: return
        val prior = borderCandidate
        val candidate = if (prior == null) VideoSyncAnalyzer.detectBlackTvWithHalo(frame)
        else VideoSyncAnalyzer.refineBorder(frame, prior)
        if (candidate == null) {
            borderStableFrames = 0
            borderCandidate = null
            previewTvRect = null
            statusMessage = "Still looking for the TV. Keep the full black screen and some white-lit wall visible."
            return
        }
        borderCandidate = candidate
        previewTvRect = candidate
        borderStableFrames = if (prior != null && rectClose(prior, candidate)) borderStableFrames + 1 else 1
        val aspect = candidate.width * frame.width / (candidate.height * frame.height).coerceAtLeast(1e-6)
        statusMessage = "TV candidate • apparent aspect ${"%.2f".format(aspect)} • stable $borderStableFrames/${CalibrationProtocol.STABLE_BORDER_FRAMES}"
        if (borderStableFrames >= CalibrationProtocol.STABLE_BORDER_FRAMES) lockBorder(candidate)
    }

    private fun lockBorder(rect: NormalizedRect) {
        if (busy || borderLocked || stage != Stage.BORDER) return
        val token = generation
        busy = true
        previewTvRect = rect
        borderCandidate = rect
        borderLocked = true
        autoProgress = "GUIDE LOCKED"
        statusMessage = "TV guide locked. Turning the backlights OFF; keep the TV inside the green box."
        executor.execute {
            try {
                client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR is not connected")
                Thread.sleep(350)
                uiIfCurrent(token) {
                    borderLightingActive = false
                    videoArmed = true
                    markerStep = null
                    markerStableFrames = 0
                    lastObservedStep = null
                    busy = false
                    stage = Stage.TV
                    autoProgress = "TV 0/${CalibrationProtocol.tvSequence.size}"
                    statusMessage = "GUIDE LOCKED. Backlights are OFF. Waiting for the first video marker…"
                }
            } catch (e: Exception) {
                uiIfCurrent(token) {
                    borderLocked = false
                    busy = false
                    stage = Stage.BORDER
                    statusMessage = "TV was found, but the backlights could not be turned off: ${e.message}"
                }
            }
        }
    }

    private fun analyzeVideoPreview() {
        val frame = readPreviewFrame() ?: return
        val guide = previewTvRect ?: return
        val reading = VideoSyncAnalyzer.decodeMarker(frame, guide)
        if (reading == null || reading.step !in CalibrationProtocol.tvSequence.indices) {
            markerStep = null
            markerStableFrames = 0
            statusMessage = "Guide locked • waiting for video marker • captured ${tvMeasurements.size}/8"
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
            autoProgress = "TV ${tvMeasurements.size}/8 • ${patch.label} already captured"
            if (patch == Patch.BLACK && missingTvPatches().isEmpty()) startLedPhase()
            return
        }
        if (patch != Patch.WHITE && !tvMeasurements.containsKey(Patch.WHITE)) {
            statusMessage = "Saw ${patch.label.uppercase()}, but WHITE has not been captured. Continue, then replay once; completed colors are retained."
            return
        }
        captureTvPatch(patch)
    }

    private fun captureTvPatch(patch: Patch) {
        if (busy || tvMeasurements.containsKey(patch)) return
        val camera = sampler ?: return
        val token = generation
        busy = true
        markerStableFrames = 0
        statusMessage = "Measuring TV ${patch.label}…"
        activeJob = executor.submit {
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
                val allCaptured = missingTvPatches().isEmpty()
                val finalBlack = lastObservedStep == CalibrationProtocol.tvSequence.lastIndex
                uiIfCurrent(token) {
                    latestMeasurement = "TV ${patch.label}: %.4f, %.4f, %.4f".format(sample.r, sample.g, sample.b)
                    busy = allCaptured && finalBlack
                    autoProgress = "TV ${tvMeasurements.size}/8"
                    statusMessage = when {
                        allCaptured && finalBlack -> "All TV references captured. Starting raw LED characterization…"
                        allCaptured -> "All TV references captured. Continue to final BLACK."
                        finalBlack -> "Final BLACK reached; missing ${missingTvPatches().joinToString { it.label }}. Replay the video; completed colors are retained."
                        else -> "Captured ${patch.label.uppercase()}. Waiting for next marker."
                    }
                }
                if (allCaptured && finalBlack) {
                    videoArmed = false
                    runFullLedCalibration(camera, token)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                uiIfCurrent(token) {
                    busy = false
                    statusMessage = "TV ${patch.label} capture failed: ${e.message}. The app will retry while the marker remains visible."
                }
            }
        }
    }

    private fun startLedPhase() {
        if (busy || !videoArmed || missingTvPatches().isNotEmpty()) return
        val camera = sampler ?: return
        val token = generation
        busy = true
        videoArmed = false
        stage = Stage.MEASURING
        autoProgress = "Raw LED characterization"
        statusMessage = "TV remains on final BLACK. Measuring unadjusted LED response…"
        activeJob = executor.submit { runFullLedCalibration(camera, token) }
    }

    private fun runFullLedCalibration(camera: CameraSampler, token: Int) {
        var originalTargets: Map<Patch, IntArray>? = null
        var candidateApplied = false
        try {
            ensureCurrent(token)
            uiIfCurrent(token) {
                stage = Stage.MEASURING
                autoProgress = "Raw LED characterization"
                statusMessage = "Static COLOR input intentionally measures the unadjusted LED hardware response."
            }
            measureRawLedSet(camera, token)
            ensureCurrent(token)

            val rect = rawTvRect ?: error("TV rectangle is missing")
            buildRawLedColors(rect)
            val solved = CalibrationEngine.solve(tvMeasurements, rawLedMeasurements)
            val rawError = CalibrationEngine.measuredChromaticError(tvMeasurements, rawLedMeasurements)

            uiIfCurrent(token) {
                autoProgress = "Validating current calibration"
                statusMessage = "Reading the installed ICE anchors so they can be restored after the test…"
            }
            val c = client ?: error("HyperHDR is not connected")
            originalTargets = c.readCalibrationTargets()
            ensureCurrent(token)

            val currentSet = ClosedLoopValidator.measureProcessedSet(camera, c, rect) { step ->
                uiIfCurrent(token) {
                    autoProgress = "Current installed calibration"
                    statusMessage = step
                }
                ensureCurrent(token)
            }
            val currentError = CalibrationEngine.measuredChromaticError(tvMeasurements, currentSet.colors)
            ensureCurrent(token)

            uiIfCurrent(token) {
                autoProgress = "Validating new candidate"
                statusMessage = "Applying the candidate temporarily; it will be restored to your current calibration after measurement."
            }
            c.applyCalibration(solved.targets)
            candidateApplied = true
            Thread.sleep(450)
            ensureCurrent(token)

            val candidateSet = ClosedLoopValidator.measureProcessedSet(camera, c, rect) { step ->
                uiIfCurrent(token) {
                    autoProgress = "New candidate calibration"
                    statusMessage = step
                }
                ensureCurrent(token)
            }
            val candidateError = CalibrationEngine.measuredChromaticError(tvMeasurements, candidateSet.colors)
            ensureCurrent(token)

            runCatching { c.clear() }
            c.applyCalibration(originalTargets)
            candidateApplied = false

            val sameTargets = targetsClose(originalTargets, solved.targets)
            val improvesRaw = candidateError <= 1.5 || candidateError <= rawError * 0.95
            val notRegression = sameTargets || candidateError <= currentError * 1.08 || candidateError <= 1.5
            val passed = improvesRaw && notRegression
            validationPassed = passed
            alreadyInstalled = sameTargets && candidateError <= currentError * 1.08
            solvedResult = solved
            solveError = if (passed) null else "Candidate failed measured closed-loop validation"

            val rawVsCandidate = if (rawError <= 1e-9) 0.0 else 100.0 * (rawError - candidateError) / rawError
            val currentVsCandidate = if (currentError <= 1e-9) 0.0 else 100.0 * (currentError - candidateError) / currentError
            resultText = buildString {
                appendLine("MEASURED CLOSED-LOOP RGB/CMY ERROR")
                appendLine()
                appendLine("Raw LED hardware:       ${"%.2f".format(rawError)}")
                appendLine("Currently installed:    ${"%.2f".format(currentError)}")
                appendLine("New candidate:          ${"%.2f".format(candidateError)}")
                appendLine()
                appendLine("Candidate vs raw:       ${"%+.1f".format(rawVsCandidate)}% improvement")
                appendLine("Candidate vs installed: ${"%+.1f".format(currentVsCandidate)}% improvement")
                appendLine("Solver model predicted: ${"%.2f".format(solved.estimatedErrorAfter)}")
                appendLine()
                appendLine(if (passed) "VALIDATION: PASS" else "VALIDATION: FAIL — candidate will not be offered for commit")
                if (sameTargets) appendLine("Installed anchors already match the candidate within tolerance.")
                appendLine()
                appendLine("Candidate ICE anchors")
                for (patch in Patch.entries) {
                    val value = solved.targets.getValue(patch)
                    appendLine("${patch.label.padEnd(8)} [${value[0]}, ${value[1]}, ${value[2]}]")
                }
                appendLine()
                appendLine("Dedicated W remains [255,255,255]. This wall-referenced method does not claim to calibrate the absolute white point.")
                appendLine("Brightness remains intentionally uncalibrated.")
                solved.warning?.let { appendLine("Warning: $it") }
            }

            uiIfCurrent(token) {
                busy = false
                closeCamera()
                statusMessage = when {
                    passed && alreadyInstalled -> "Closed-loop validation passed. The installed calibration already matches the newly solved candidate."
                    passed -> "Closed-loop validation passed. The candidate physically measured better and can be committed."
                    else -> "Closed-loop validation failed. The existing HyperHDR calibration was restored and the candidate cannot be committed."
                }
                stage = Stage.RESULTS
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            restoreOriginalCalibration(originalTargets, candidateApplied)
        } catch (e: Exception) {
            restoreOriginalCalibration(originalTargets, candidateApplied)
            uiIfCurrent(token) {
                busy = false
                validationPassed = false
                solvedResult = null
                solveError = e.message ?: e.javaClass.simpleName
                resultText = "Closed-loop calibration could not finish\n\n$solveError\n\nThe app attempted to restore the previously installed HyperHDR calibration."
                statusMessage = "Calibration validation failed; no new values were committed."
                closeCamera()
                stage = Stage.RESULTS
            }
        }
    }

    private fun measureRawLedSet(camera: CameraSampler, token: Int) {
        rawLedSpatial.clear()
        rawLedMeasurements.clear()
        rawDiagnostics.clear()
        val c = client ?: error("HyperHDR is not connected")

        uiIfCurrent(token) { statusMessage = "Raw LEDs • WHITE exposure/reference" }
        camera.setLocks(exposureLocked = false, whiteBalanceLocked = true)
        c.setColor(Patch.WHITE.rgb)
        Thread.sleep(CalibrationProtocol.WHITE_EXPOSURE_SETTLE_MS)
        ensureCurrent(token)
        camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
        Thread.sleep(250)
        rawLedSpatial[Patch.WHITE] = camera.measureSpatial(samples = 5, timeoutMs = 7000)

        c.setColor(Patch.BLACK.rgb)
        Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
        val blackStart = camera.measureSpatial(samples = 5, timeoutMs = 7000)

        for ((index, patch) in CalibrationEngine.chromaticPatches.withIndex()) {
            ensureCurrent(token)
            uiIfCurrent(token) {
                autoProgress = "Raw LEDs ${index + 2}/8"
                statusMessage = "Raw LEDs • ${patch.label.uppercase()}"
            }
            c.setColor(patch.rgb)
            Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
            rawLedSpatial[patch] = camera.measureSpatial(samples = 5, timeoutMs = 7000)
            uiIfCurrent(token) { latestMeasurement = "Raw LED ${patch.label} • ${camera.lastMeasurementSummary}" }
        }

        c.setColor(Patch.BLACK.rgb)
        Thread.sleep(CalibrationProtocol.LED_SETTLE_MS)
        val blackEnd = camera.measureSpatial(samples = 5, timeoutMs = 7000)
        rawLedSpatial[Patch.BLACK] = SpatialCalibration.medianCombine(listOf(blackStart, blackEnd))
    }

    private fun buildRawLedColors(rect: NormalizedRect) {
        val black = rawLedSpatial[Patch.BLACK] ?: error("Raw LED black baseline is missing")
        val white = rawLedSpatial[Patch.WHITE] ?: error("Raw LED white reference is missing")
        val model = SpatialCalibration.buildWallReference(white, black, rect)
        rawLedMeasurements[Patch.BLACK] = Rgb(0.0, 0.0, 0.0)
        for (patch in listOf(Patch.WHITE) + CalibrationEngine.chromaticPatches) {
            val result = SpatialCalibration.wallColor(rawLedSpatial.getValue(patch), black, model)
            rawLedMeasurements[patch] = result.rgb
            rawDiagnostics[patch] = result
        }
    }

    private fun restoreOriginalCalibration(original: Map<Patch, IntArray>?, candidateApplied: Boolean) {
        val c = client ?: return
        runCatching { c.clear() }
        if (candidateApplied && original != null) runCatching { c.applyCalibration(original) }
    }

    private fun targetsClose(a: Map<Patch, IntArray>, b: Map<Patch, IntArray>, tolerance: Int = 3): Boolean {
        return Patch.entries.all { patch ->
            val x = a[patch] ?: return@all false
            val y = b[patch] ?: return@all false
            x.size >= 3 && y.size >= 3 && (0..2).all { i -> kotlin.math.abs(x[i] - y[i]) <= tolerance }
        }
    }

    private fun commitCalibration() {
        val solved = solvedResult ?: return
        if (committing || !validationPassed || alreadyInstalled) return
        committing = true
        commitStatus = "Saving the validated ICE anchors and verifying them by reading HyperHDR back…"
        executor.execute {
            try {
                client?.commitCalibration(solved.targets) ?: error("HyperHDR is not connected")
                runOnUiThread {
                    committing = false
                    alreadyInstalled = true
                    commitStatus = "Validated calibration committed and verified successfully on ${selectedTarget?.displayName ?: "HyperHDR"}."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    committing = false
                    commitStatus = "Could not verify the saved calibration: ${e.message}. No success is assumed."
                }
            }
        }
    }

    private fun resetMeasurements() {
        tvMeasurements.clear()
        rawLedMeasurements.clear()
        rawLedSpatial.clear()
        rawDiagnostics.clear()
        rawTvRect = null
        previewTvRect = null
        borderCandidate = null
        borderStableFrames = 0
        borderLocked = false
        borderLightingActive = false
        countdownValue = null
        videoArmed = false
        markerStep = null
        markerStableFrames = 0
        lastObservedStep = null
        latestMeasurement = null
        resultText = ""
        solveError = null
        solvedResult = null
        validationPassed = false
        alreadyInstalled = false
        commitStatus = null
        committing = false
        cameraError = null
    }

    private fun missingTvPatches(): List<Patch> = CalibrationProtocol.tvSequence.filterNot(tvMeasurements::containsKey)
    private fun capturedPatchLabels(): String = if (tvMeasurements.isEmpty()) "none" else tvMeasurements.keys.joinToString { it.label }

    private fun readPreviewFrame(): PreviewFrame? {
        val view = previewView ?: return null
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        val longEdge = CalibrationProtocol.PREVIEW_SAMPLE_LONG_EDGE
        val scale = longEdge / max(view.width, view.height).toDouble()
        val sampleWidth = (view.width * scale).roundToInt().coerceAtLeast(CalibrationProtocol.PREVIEW_SAMPLE_SHORT_EDGE_MIN)
        val sampleHeight = (view.height * scale).roundToInt().coerceAtLeast(CalibrationProtocol.PREVIEW_SAMPLE_SHORT_EDGE_MIN)
        val bitmap = runCatching { view.getBitmap(sampleWidth, sampleHeight) }.getOrNull() ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return PreviewAnalyzer.fromArgb(pixels, sampleWidth, sampleHeight)
    }

    private fun rectClose(a: NormalizedRect, b: NormalizedRect): Boolean =
        abs(a.left - b.left) < 0.030 && abs(a.top - b.top) < 0.030 &&
            abs(a.right - b.right) < 0.030 && abs(a.bottom - b.bottom) < 0.030

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
        }.onFailure { cameraError = "Could not enumerate rear cameras: ${it.message}" }
    }

    private fun selectedCameraChoice(): CameraChoice? = cameraChoices.firstOrNull { it.key == selectedCameraChoiceKey }

    private fun switchRearCamera() {
        if (busy || stage != Stage.FRAMING || cameraChoices.size < 2) return
        val current = cameraChoices.indexOfFirst { it.key == selectedCameraChoiceKey }.coerceAtLeast(0)
        val next = cameraChoices[(current + 1) % cameraChoices.size]
        selectedCameraChoiceKey = next.key
        cameraStatus = "Switching to ${next.displayName()}…"
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
                    if (stage == Stage.FRAMING) statusMessage = "Camera ready. Point at the TV, then press READY — START VIDEO."
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
        activeJob?.cancel(true)
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
private fun Beta93Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
