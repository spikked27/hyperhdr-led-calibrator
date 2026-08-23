package com.spikked27.hyperhdrcalibrator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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

class MainActivity : ComponentActivity() {
    private enum class Stage { DISCOVERY, INTRO, CALIBRATION, ANALYZING, RESULTS }
    private enum class Phase { TV, LED }

    private val executor = Executors.newSingleThreadExecutor()
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
    private var phase by mutableStateOf(Phase.TV)
    private var patchIndex by mutableStateOf(0)
    private var ledIntro by mutableStateOf(false)
    private var ledPatchPrepared by mutableStateOf(false)
    private var tvBlackoutPrepared by mutableStateOf(false)
    private var latestMeasurement by mutableStateOf<String?>(null)
    private var resultText by mutableStateOf("")
    private var solveError by mutableStateOf<String?>(null)
    private var tvDetectionStatus by mutableStateOf("TV not detected yet")

    private var sampler: CameraSampler? = null
    private var previewView: TextureView? = null
    private var client: HyperHdrClient? = null
    private var tvRect: NormalizedRect? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { CalibratorTheme { AppContent() } }
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
                color = MaterialTheme.colorScheme.background
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Choose a HyperHDR instance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Select the exact HyperHDR instance this calibration session should control.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Searching with HyperHDR SSDP…")
                }
            }
            StatusCard(statusMessage)
            if (!scanning && targets.isEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No instances found", style = MaterialTheme.typography.titleMedium)
                        Text("Make sure the phone and HyperHDR are on the same local network and multicast/SSDP is not blocked.")
                    }
                }
            }
            targets.forEach { target ->
                ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(target.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${target.server.host}:${target.server.jsonPort}  •  Instance ${target.instance.instanceId}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                if (target.instance.running) "Running" else "Stopped",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (target.instance.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = target.instance.running && !busy,
                            onClick = { connectToTarget(target) }
                        ) { Text(if (busy) "Connecting…" else "Connect") }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Fixed-camera calibration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            InstructionCard("1", "Mount the phone once", "Frame the entire TV with a useful border of wall visible on all four sides. A tripod or fixed stand is ideal. Do not move the phone after the first TV white measurement.")
            InstructionCard("2", "The app finds the TV", "With the backlights off and the WHITE video patch on screen, the app detects the TV rectangle from the camera data. TV colors are measured only inside that detected rectangle.")
            InstructionCard("3", "Leave the final BLACK patch on", "After the TV sequence reaches BLACK, keep that black image on the TV. The phone stays exactly where it is while the app drives the LEDs through the color sequence.")
            InstructionCard("4", "Gradient is expected", "The app samples many wall regions around the TV, subtracts the per-region black/ambient baseline, normalizes each region by chromaticity, rejects color outliers, and combines them. Bright LED hot spots do not get extra weight just because they are brighter.")
            InstructionCard("5", "RAW when available", "RAW_SENSOR measurements use fixed ISO and shutter for the complete TV sequence and a separate fixed ISO/shutter for the complete LED sequence.")
            Button(modifier = Modifier.fillMaxWidth(), onClick = { beginCalibration() }) { Text("Begin calibration") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun CalibrationScreen() {
        val patch = Patch.entries[patchIndex]
        val isLedIntro = phase == Phase.LED && ledIntro
        val readyForCapture = if (phase == Phase.TV) tvBlackoutPrepared else ledPatchPrepared
        val canChangeCamera = phase == Phase.TV && patchIndex == 0 && tvMeasurements.isEmpty() && !busy && cameraChoices.size > 1

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isLedIntro) {
                Text("TV references complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Do not move the phone. Leave the TV showing the BLACK patch. The app will now measure the colored light on the wall surrounding the already-detected TV rectangle.",
                    style = MaterialTheme.typography.bodyLarge
                )
                StatusCard(statusMessage)
            } else {
                val progress = if (phase == Phase.TV) patchIndex + 1 else Patch.entries.size + patchIndex + 1
                Text(
                    if (phase == Phase.TV) "TV reference • ${patch.label}" else "LED wall • ${patch.label}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Step $progress of ${Patch.entries.size * 2}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                ColorSwatch(patch)
                Text(
                    when {
                        phase == Phase.TV && patch == Patch.WHITE && tvRect == null ->
                            "Show WHITE full-screen. Keep the complete TV and surrounding wall visible. This first capture locks the framing and automatically detects the screen."
                        phase == Phase.TV && tvBlackoutPrepared ->
                            "Backlights are forced OFF. Show ${patch.label.uppercase()} full-screen on the TV without moving the phone, then capture."
                        phase == Phase.TV ->
                            "Waiting for HyperHDR to confirm the backlights are off."
                        patch == Patch.BLACK && busy ->
                            "LEDs are BLACK. The app is capturing the spatial ambient/wall baseline automatically. Keep the TV black and phone fixed."
                        ledPatchPrepared ->
                            "TV stays BLACK. HyperHDR is showing LED ${patch.label.uppercase()}. The app uses the wall halo around the TV and mathematically removes brightness gradient from the color estimate."
                        else ->
                            "The LED color has not been confirmed yet."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                StatusCard(statusMessage)
            }

            CameraPreview(Modifier.weight(1f).fillMaxWidth())
            Text(cameraStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tvRect != null) Text(tvDetectionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            latestMeasurement?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (canChangeCamera) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { switchRearCamera() }) {
                    Text("Switch rear camera")
                }
            }

            if (isLedIntro) {
                Button(modifier = Modifier.fillMaxWidth(), enabled = cameraReady && !busy, onClick = { startLedMeasurements() }) {
                    Text(if (busy) "Setting LED white…" else "Phone fixed + TV black — start LEDs")
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = cameraReady && !busy && readyForCapture,
                    onClick = { captureCurrent() }
                ) {
                    Text(
                        when {
                            busy -> "Measuring…"
                            phase == Phase.TV -> "Capture TV ${patch.label}"
                            else -> "Capture LED ${patch.label}"
                        }
                    )
                }
                if (phase == Phase.TV && !tvBlackoutPrepared) {
                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { prepareTvBlackout() }) {
                        Text("Retry turning backlights off")
                    }
                }
                if (phase == Phase.LED && !ledPatchPrepared && !busy) {
                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { prepareCurrentLedPatch() }) {
                        Text("Retry setting LED ${patch.label}")
                    }
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(modifier: Modifier = Modifier) {
        Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> TextureView(context).also { bindPreviewView(it) } },
                update = { bindPreviewView(it) }
            )
            Box(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.82f).height(190.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            )
            Text(
                if (tvRect == null) "Frame TV + wall inside view" else "TV detected • do not move phone",
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    @Composable
    private fun AnalyzingScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(54.dp), strokeWidth = 5.dp)
            Text("Analyzing calibration", modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(statusMessage, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun ResultsScreen() {
        val failed = solveError != null
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(if (failed) "Calibration needs another pass" else "Calibration complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(
                if (failed) "Normal HyperHDR control was restored, but the measurements could not produce a reliable correction."
                else "The temporary HyperHDR priority has been cleared. Brightness gradient was treated spatially and was not allowed to bias the wall color."
            )
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(resultText, modifier = Modifier.padding(20.dp), fontFamily = FontFamily.Monospace)
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = { restartForSameTarget() }) { Text("Run calibration again") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) { Text("Choose another instance") }
        }
    }

    @Composable
    private fun ColorSwatch(patch: Patch) {
        val c = Color(patch.rgb[0], patch.rgb[1], patch.rgb[2])
        val border = if (patch == Patch.BLACK || patch == Patch.WHITE) MaterialTheme.colorScheme.outline else c
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(c).border(1.dp, border))
            Text("RGB ${patch.rgb[0]}, ${patch.rgb[1]}, ${patch.rgb[2]}", modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium)
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
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        statusMessage = "Connecting and locking to ${target.displayName}…"
        executor.execute {
            val newClient = HyperHdrClient(target.server)
            try {
                newClient.connectTo(target.instance.instanceId)
                runOnUiThread {
                    client?.close()
                    client = newClient
                    selectedTarget = target
                    statusMessage = "Connected to ${target.displayName} at ${target.server.host}:${target.server.jsonPort}."
                    busy = false
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
        tvMeasurements.clear()
        ledMeasurements.clear()
        ledSpatialMeasurements.clear()
        wallDiagnostics.clear()
        tvRect = null
        tvDetectionStatus = "TV not detected yet"
        phase = Phase.TV
        patchIndex = 0
        ledIntro = false
        ledPatchPrepared = false
        tvBlackoutPrepared = false
        latestMeasurement = null
        cameraError = null
        solveError = null
        resultText = ""
        stage = Stage.CALIBRATION

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prepareCameraChoices()
            startCameraIfPossible()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        prepareTvBlackout()
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
            cameraStatus = "Selected: ${chosen.displayName()}${if (choices.size > 1) " • tap Switch rear camera if preview is not the normal 1× lens" else ""}"
        }.onFailure {
            cameraError = "Could not enumerate rear cameras: ${it.message}"
        }
    }

    private fun selectedCameraChoice(): CameraChoice? = cameraChoices.firstOrNull { it.key == selectedCameraChoiceKey }

    private fun switchRearCamera() {
        if (busy || tvMeasurements.isNotEmpty() || cameraChoices.size < 2) return
        val current = cameraChoices.indexOfFirst { it.key == selectedCameraChoiceKey }.coerceAtLeast(0)
        val next = cameraChoices[(current + 1) % cameraChoices.size]
        selectedCameraChoiceKey = next.key
        cameraStatus = "Switching to ${next.displayName()}…"
        closeSamplerOnly()
        startCameraIfPossible()
    }

    private fun prepareTvBlackout() {
        if (busy || phase != Phase.TV || tvBlackoutPrepared) return
        busy = true
        statusMessage = "Turning HyperHDR backlights off for TV calibration…"
        executor.execute {
            try {
                client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR session is not connected")
                Thread.sleep(350)
                runOnUiThread {
                    tvBlackoutPrepared = true
                    busy = false
                    statusMessage = "Backlights are OFF. Show WHITE full-screen and frame the entire TV plus surrounding wall."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvBlackoutPrepared = false
                    busy = false
                    statusMessage = "Could not turn the backlights off: ${e.message}"
                }
            }
        }
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
            }
        )
    }

    private fun captureCurrent() {
        if (busy || !cameraReady) return
        val camera = sampler ?: return
        val currentPhase = phase
        val currentIndex = patchIndex
        val patch = Patch.entries[currentIndex]
        if (currentPhase == Phase.TV && !tvBlackoutPrepared) return
        if (currentPhase == Phase.LED && !ledPatchPrepared) return

        busy = true
        statusMessage = if (currentPhase == Phase.TV) "Measuring TV ${patch.label}…" else "Measuring LED ${patch.label} wall field…"

        executor.execute {
            try {
                if (currentPhase == Phase.TV && currentIndex == 0) {
                    camera.setLocks(exposureLocked = false, whiteBalanceLocked = false)
                    Thread.sleep(1200)
                    camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
                    Thread.sleep(250)
                }

                val frame = camera.measureSpatial(samples = 5, timeoutMs = 7000)
                val label: String
                if (currentPhase == Phase.TV) {
                    val rect = if (currentIndex == 0) {
                        SpatialCalibration.detectTvRect(frame).also { detected ->
                            tvRect = detected
                            val w = (detected.width * 100).toInt()
                            val h = (detected.height * 100).toInt()
                            runOnUiThread { tvDetectionStatus = "TV detected automatically: ~${w}% × ${h}% of sensor frame. Framing is now locked." }
                        }
                    } else {
                        tvRect ?: error("TV rectangle was not detected from the white reference")
                    }
                    val sample = SpatialCalibration.screenColor(frame, rect)
                    tvMeasurements[patch] = sample
                    label = measurementLabel(Phase.TV, patch, sample, camera.lastMeasurementSummary)
                } else {
                    ledSpatialMeasurements[patch] = frame
                    label = "Captured LED ${patch.label} spatial wall field • ${camera.lastMeasurementSummary}"
                }

                if (currentIndex == Patch.entries.lastIndex) {
                    if (currentPhase == Phase.TV) {
                        runOnUiThread {
                            latestMeasurement = label
                            phase = Phase.LED
                            patchIndex = 0
                            ledIntro = true
                            ledPatchPrepared = false
                            busy = false
                            statusMessage = "TV BLACK saved. Keep the TV on this black frame and do not move the phone."
                        }
                    } else {
                        runOnUiThread {
                            latestMeasurement = label
                            statusMessage = "All spatial LED measurements captured. Removing the wall brightness gradient and calculating color…"
                            stage = Stage.ANALYZING
                        }
                        finalizeLedSpatialAndSolve()
                    }
                    return@execute
                }

                val nextIndex = CalibrationFlow.nextPatchIndex(currentIndex) ?: error("No next calibration patch")
                if (currentPhase == Phase.LED && CalibrationFlow.shouldAutoCaptureLedBlack(currentIndex)) {
                    runOnUiThread {
                        patchIndex = nextIndex
                        latestMeasurement = label
                        ledPatchPrepared = false
                        statusMessage = "Turning LEDs BLACK and capturing a spatial ambient baseline. Keep TV black and phone fixed…"
                    }
                    client?.setColor(Patch.BLACK.rgb) ?: error("HyperHDR session is not connected")
                    Thread.sleep(750)
                    val blackFrame = camera.measureSpatial(samples = 5, timeoutMs = 7000)
                    ledSpatialMeasurements[Patch.BLACK] = blackFrame
                    runOnUiThread {
                        latestMeasurement = "Captured LED Black spatial baseline • ${camera.lastMeasurementSummary}"
                        statusMessage = "Removing per-region ambient light and brightness gradient…"
                        stage = Stage.ANALYZING
                    }
                    finalizeLedSpatialAndSolve()
                    return@execute
                }

                if (currentPhase == Phase.LED) {
                    client?.setColor(Patch.entries[nextIndex].rgb) ?: error("HyperHDR session is not connected")
                    Thread.sleep(500)
                }
                runOnUiThread {
                    patchIndex = nextIndex
                    latestMeasurement = label
                    busy = false
                    if (currentPhase == Phase.TV) {
                        statusMessage = "Backlights remain OFF. Show ${Patch.entries[nextIndex].label.uppercase()} full-screen without moving the phone."
                    } else {
                        ledPatchPrepared = true
                        statusMessage = "TV stays BLACK. HyperHDR acknowledged LED ${Patch.entries[nextIndex].label.uppercase()}."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    statusMessage = "Measurement failed: ${e.message}"
                    if (currentPhase == Phase.LED) ledPatchPrepared = false
                }
            }
        }
    }

    private fun finalizeLedSpatialAndSolve() {
        try {
            val rect = tvRect ?: error("TV rectangle is missing")
            val black = ledSpatialMeasurements[Patch.BLACK] ?: error("LED black spatial baseline is missing")
            ledMeasurements.clear()
            wallDiagnostics.clear()
            for (patch in Patch.entries) {
                if (patch == Patch.BLACK) {
                    ledMeasurements[patch] = Rgb(0.0, 0.0, 0.0)
                    continue
                }
                val frame = ledSpatialMeasurements[patch] ?: error("Missing LED ${patch.label} spatial frame")
                val result = SpatialCalibration.wallColor(frame, black, rect)
                ledMeasurements[patch] = result.rgb
                wallDiagnostics[patch] = result
            }
            runCatching { client?.clear() }
            finishSolve()
        } catch (e: Exception) {
            runCatching { client?.clear() }
            val reason = e.message ?: e.javaClass.simpleName
            runOnUiThread {
                solveError = reason
                resultText = "Spatial wall analysis failed\n\n$reason\n\nNormal HyperHDR control has been restored."
                closeCamera()
                busy = false
                stage = Stage.RESULTS
            }
        }
    }

    private fun measurementLabel(measurementPhase: Phase, patch: Patch, sample: Rgb, detail: String): String =
        "Measured ${if (measurementPhase == Phase.TV) "TV" else "LED"} ${patch.label}: %.4f, %.4f, %.4f • %s".format(sample.r, sample.g, sample.b, detail)

    private fun startLedMeasurements() {
        if (busy || !cameraReady || tvRect == null) return
        val camera = sampler ?: return
        busy = true
        statusMessage = "Keep TV BLACK and phone fixed. Setting LEDs to WHITE and establishing LED-wall exposure…"
        executor.execute {
            try {
                camera.setLocks(exposureLocked = false, whiteBalanceLocked = true)
                client?.setColor(Patch.WHITE.rgb) ?: error("HyperHDR session is not connected")
                Thread.sleep(1200)
                camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
                Thread.sleep(250)
                runOnUiThread {
                    patchIndex = 0
                    ledIntro = false
                    ledPatchPrepared = true
                    busy = false
                    statusMessage = "TV stays BLACK. LED WHITE is ready; capture without moving the phone."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    ledPatchPrepared = false
                    statusMessage = "Could not start LED measurements: ${e.message}"
                }
            }
        }
    }

    private fun prepareCurrentLedPatch() {
        if (busy || phase != Phase.LED) return
        busy = true
        val patch = Patch.entries[patchIndex]
        statusMessage = "Setting LED ${patch.label.uppercase()}…"
        executor.execute {
            try {
                client?.setColor(patch.rgb) ?: error("HyperHDR session is not connected")
                Thread.sleep(500)
                runOnUiThread {
                    ledPatchPrepared = true
                    busy = false
                    statusMessage = "TV stays BLACK. LED ${patch.label.uppercase()} is ready."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    ledPatchPrepared = false
                    statusMessage = "Could not set LED color: ${e.message}"
                }
            }
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
                    appendLine("${patch.label.padEnd(8)} ${d.tilesUsed}/${d.availableTiles} tiles • brightness p90/p10 ${"%.1f".format(d.brightnessGradient)}× • chroma spread ${"%.3f".format(d.chromaSpread)}")
                }
                appendLine()
                appendLine("The brightness gradient above is expected and was removed from the color estimate by normalizing each wall tile before combining them.")
                solved.warning?.let { appendLine("Warning: $it") }
            }
            runOnUiThread {
                closeCamera()
                busy = false
                stage = Stage.RESULTS
                statusMessage = "Calibration solved."
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            val diagnostics = measurementDiagnostics()
            runCatching { client?.clear() }
            runOnUiThread {
                solveError = reason
                resultText = buildString {
                    appendLine("Calibration solve failed")
                    appendLine()
                    appendLine(reason)
                    appendLine()
                    appendLine("Measurement diagnostics")
                    append(diagnostics)
                    appendLine()
                    appendLine("Normal HyperHDR control has been restored.")
                }
                closeCamera()
                busy = false
                stage = Stage.RESULTS
                statusMessage = "Calibration measurements need to be repeated."
            }
        }
    }

    private fun measurementDiagnostics(): String = buildString {
        for (patch in Patch.entries) {
            val tv = tvMeasurements[patch]
            val led = ledMeasurements[patch]
            append("${patch.label.padEnd(8)} TV=")
            append(if (tv == null) "missing" else "%.4f,%.4f,%.4f".format(tv.r, tv.g, tv.b))
            append("  LED=")
            append(if (led == null) "missing" else "%.4f,%.4f,%.4f".format(led.r, led.g, led.b))
            appendLine()
        }
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
        tvBlackoutPrepared = false
        solveError = null
        tvRect = null
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
        selectedTarget = null
        tvBlackoutPrepared = false
        solveError = null
        tvRect = null
        stage = Stage.DISCOVERY
        discoverTargets()
    }

    override fun onDestroy() {
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
private fun CalibratorTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
