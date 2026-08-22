package com.spikked27.hyperhdrcalibrator

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.weight
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
    private enum class Stage { DISCOVERY, INTRO, CALIBRATION, RESULTS }
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
    private var phase by mutableStateOf(Phase.TV)
    private var patchIndex by mutableStateOf(0)
    private var ledIntro by mutableStateOf(false)
    private var ledPatchPrepared by mutableStateOf(false)
    private var latestMeasurement by mutableStateOf<String?>(null)
    private var resultText by mutableStateOf("")

    private var sampler: CameraSampler? = null
    private var previewView: TextureView? = null
    private var client: HyperHdrClient? = null
    private val tvMeasurements = linkedMapOf<Patch, Rgb>()
    private val ledMeasurements = linkedMapOf<Patch, Rgb>()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCameraIfPossible()
        else cameraError = "Camera permission is required to measure the TV and LED colors."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CalibratorTheme {
                AppContent()
            }
        }
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
                                if (stage != Stage.DISCOVERY) {
                                    Text(it.displayName, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = MaterialTheme.colorScheme.background
            ) {
                when (stage) {
                    Stage.DISCOVERY -> DiscoveryScreen()
                    Stage.INTRO -> IntroScreen()
                    Stage.CALIBRATION -> CalibrationScreen()
                    Stage.RESULTS -> ResultsScreen()
                }
            }
        }
    }

    @Composable
    private fun DiscoveryScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Choose a HyperHDR instance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Nothing is connected automatically. Select the exact HyperHDR instance you want this calibration session locked to.",
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
                        Text("Make sure the phone and HyperHDR are on the same local network and that multicast/SSDP is not blocked between them.")
                    }
                }
            }

            targets.forEach { target ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors()
                ) {
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
                        ) {
                            Text(if (busy) "Connecting…" else "Connect")
                        }
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
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Ready to calibrate", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard(statusMessage)
            InstructionCard("1", "Darken the room", "Turn off changing room lights and keep the phone in the same camera mode for the whole session.")
            InstructionCard("2", "Measure the TV", "The app will tell you exactly which full-screen color to show. You change the TV image, then tap Capture.")
            InstructionCard("3", "Measure the LED wall", "After the TV references are done, point the phone at a representative wall area. The app will change HyperHDR through the same colors automatically.")
            InstructionCard("4", "Review the correction", "The beta calculates the ICE LED calibration values but still does not write permanent HyperHDR settings automatically.")

            Button(modifier = Modifier.fillMaxWidth(), onClick = { beginCalibration() }) {
                Text("Begin calibration")
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnectAndReturn() }) {
                Text("Choose another instance")
            }
        }
    }

    @Composable
    private fun CalibrationScreen() {
        val patch = Patch.entries[patchIndex]
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (phase == Phase.LED && ledIntro) {
                Text("TV references complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Now point the rear camera at a representative section of the wall illuminated by the backlights. Keep the phone position fixed from here onward.",
                    style = MaterialTheme.typography.bodyLarge
                )
                StatusCard(statusMessage)
                CameraPreview(Modifier.weight(1f).fillMaxWidth())
                Button(modifier = Modifier.fillMaxWidth(), enabled = cameraReady && !busy, onClick = { startLedMeasurements() }) {
                    Text(if (busy) "Setting LED white…" else "I'm aimed at the wall — continue")
                }
                return
            }

            val progress = if (phase == Phase.TV) patchIndex + 1 else Patch.entries.size + patchIndex + 1
            Text(
                if (phase == Phase.TV) "TV reference • ${patch.label}" else "LED wall • ${patch.label}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text("Step $progress of ${Patch.entries.size * 2}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            ColorSwatch(patch)

            Text(
                if (phase == Phase.TV)
                    "Show a full-screen ${patch.label.uppercase()} image on the TV. Point the center guide at the screen, wait a moment, then take the measurement."
                else if (ledPatchPrepared)
                    "HyperHDR acknowledged ${patch.label.uppercase()} at priority ${HyperHdrClient.TEST_PRIORITY}. Keep the camera aimed at the same wall area and take the measurement."
                else
                    "The LED color has not been confirmed yet. Retry setting ${patch.label.uppercase()} before measuring.",
                style = MaterialTheme.typography.bodyLarge
            )

            CameraPreview(Modifier.weight(1f).fillMaxWidth())
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            latestMeasurement?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = cameraReady && !busy && (phase == Phase.TV || ledPatchPrepared),
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
            if (phase == Phase.LED && !ledPatchPrepared) {
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { prepareCurrentLedPatch() }) {
                    Text("Retry setting LED ${patch.label}")
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).also { view ->
                        previewView = view
                        startCameraIfPossible()
                    }
                },
                update = { view ->
                    if (previewView !== view) previewView = view
                    startCameraIfPossible()
                }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.42f)
                    .height(120.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            )
            Text(
                "Measure inside the guide",
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    @Composable
    private fun ResultsScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Calibration complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StatusCard("The temporary HyperHDR test priority has been cleared.")
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(c)
                    .border(1.dp, border)
            )
            Text(
                "RGB ${patch.rgb[0]}, ${patch.rgb[1]}, ${patch.rgb[2]}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }

    @Composable
    private fun StatusCard(text: String) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
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
            val servers = runCatching { SsdpDiscovery(this).discover() }
                .getOrElse {
                    errors += (it.message ?: "SSDP discovery failed")
                    emptyList()
                }
            servers.forEach { server ->
                val probe = HyperHdrClient(server)
                try {
                    probe.discoverInstances().forEach { instance ->
                        foundTargets += HyperHdrTarget(server, instance)
                    }
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
                    statusMessage = "Connected to ${target.displayName} at ${target.server.host}:${target.server.jsonPort}, instance ${target.instance.instanceId}."
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
        phase = Phase.TV
        patchIndex = 0
        ledIntro = false
        ledPatchPrepared = false
        latestMeasurement = null
        cameraError = null
        stage = Stage.CALIBRATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraIfPossible()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraIfPossible() {
        if (sampler != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val view = previewView ?: return
        sampler = CameraSampler(this, view).also { camera ->
            camera.start(
                onReady = { msg ->
                    cameraReady = true
                    cameraError = null
                    statusMessage = msg
                },
                onError = { error ->
                    cameraReady = false
                    cameraError = error
                }
            )
        }
    }

    private fun captureCurrent() {
        if (busy || !cameraReady) return
        val camera = sampler ?: return
        val patch = Patch.entries[patchIndex]
        busy = true
        statusMessage = if (phase == Phase.TV) "Measuring TV ${patch.label}…" else "Measuring LED ${patch.label}…"

        executor.execute {
            try {
                if (phase == Phase.TV && patchIndex == 0) {
                    camera.setLocks(exposureLocked = false, whiteBalanceLocked = false)
                    Thread.sleep(1200)
                    camera.setLocks(exposureLocked = true, whiteBalanceLocked = true)
                    Thread.sleep(250)
                }
                val sample = camera.measure(samples = 15, timeoutMs = 3000)
                if (phase == Phase.TV) tvMeasurements[patch] = sample else ledMeasurements[patch] = sample
                val label = "Measured ${if (phase == Phase.TV) "TV" else "LED"} ${patch.label}: %.3f, %.3f, %.3f".format(sample.r, sample.g, sample.b)

                if (patchIndex == Patch.entries.lastIndex) {
                    if (phase == Phase.TV) {
                        runOnUiThread {
                            latestMeasurement = label
                            phase = Phase.LED
                            patchIndex = 0
                            ledIntro = true
                            ledPatchPrepared = false
                            busy = false
                            statusMessage = "TV references saved. Next we will drive the LEDs automatically."
                        }
                    } else {
                        runCatching { client?.clear() }
                        finishSolve()
                    }
                } else {
                    patchIndex++
                    if (phase == Phase.LED) {
                        ledPatchPrepared = false
                        prepareLedPatchBlocking(Patch.entries[patchIndex])
                    }
                    runOnUiThread {
                        latestMeasurement = label
                        busy = false
                        if (phase == Phase.TV) statusMessage = "Show ${Patch.entries[patchIndex].label.uppercase()} full-screen on the TV, then capture it."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    statusMessage = "Measurement failed: ${e.message}"
                    if (phase == Phase.LED) ledPatchPrepared = false
                }
            }
        }
    }

    private fun startLedMeasurements() {
        if (busy || !cameraReady) return
        val camera = sampler ?: return
        busy = true
        statusMessage = "Setting HyperHDR LEDs to WHITE and locking camera exposure…"
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
                    statusMessage = "HyperHDR acknowledged WHITE. Take the wall measurement."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    ledPatchPrepared = false
                    statusMessage = "Could not set LED white: ${e.message}"
                }
            }
        }
    }

    private fun prepareCurrentLedPatch() {
        if (busy || phase != Phase.LED) return
        busy = true
        executor.execute {
            try {
                prepareLedPatchBlocking(Patch.entries[patchIndex])
                runOnUiThread { busy = false }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    ledPatchPrepared = false
                    statusMessage = "Could not set LED color: ${e.message}"
                }
            }
        }
    }

    private fun prepareLedPatchBlocking(patch: Patch) {
        client?.setColor(patch.rgb) ?: error("HyperHDR session is not connected")
        Thread.sleep(500)
        runOnUiThread {
            ledPatchPrepared = true
            statusMessage = "HyperHDR acknowledged ${patch.label.uppercase()}. Take the wall measurement."
        }
    }

    private fun finishSolve() {
        try {
            val solved = CalibrationEngine.solve(tvMeasurements, ledMeasurements)
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
                solved.warning?.let { appendLine("Warning: $it") }
            }
            runOnUiThread {
                busy = false
                stage = Stage.RESULTS
                statusMessage = "Calibration solved."
            }
        } catch (e: Exception) {
            runOnUiThread {
                busy = false
                statusMessage = "Could not solve calibration: ${e.message}"
            }
        }
    }

    private fun restartForSameTarget() {
        runCatching { client?.clear() }
        sampler?.close()
        sampler = null
        previewView = null
        cameraReady = false
        stage = Stage.INTRO
        statusMessage = selectedTarget?.let { "Still connected to ${it.displayName}." } ?: "Ready."
    }

    private fun disconnectAndReturn() {
        executor.execute {
            runCatching { client?.clear() }
            client?.close()
            client = null
        }
        sampler?.close()
        sampler = null
        previewView = null
        cameraReady = false
        selectedTarget = null
        stage = Stage.DISCOVERY
        discoverTargets()
    }

    override fun onDestroy() {
        val oldClient = client
        Thread {
            runCatching { oldClient?.clear() }
            oldClient?.close()
        }.start()
        sampler?.close()
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
