package com.imamjesly.fliptunes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    /** Bumped whenever we return to the screen, so permission checks re-run. */
    private val refreshTick = mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshTick.intValue++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws apps edge to edge; we add padding for the status bar,
        // camera cut-out and navigation bar ourselves (see safeDrawing below).
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasBluetoothPermissions(): Boolean =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startBridge() =
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))

    private fun stopBridge() = stopService(Intent(this, BridgeService::class.java))

    @Composable
    private fun Screen() {
        val tick = refreshTick.intValue
        val context = LocalContext.current
        val nowPlaying by AppState.nowPlaying.collectAsStateWithLifecycle()
        val link by AppState.link.collectAsStateWithLifecycle()
        val linkDetail by AppState.linkDetail.collectAsStateWithLifecycle()
        val running by AppState.bridgeRunning.collectAsStateWithLifecycle()
        val log by AppState.log.collectAsStateWithLifecycle()
        val beatStatus by AppState.beatStatus.collectAsStateWithLifecycle()
        val hasNotificationAccess = remember(tick) { MediaWatcher.hasAccess(context) }
        val hasBluetooth = remember(tick) { hasBluetoothPermissions() }
        val hasMic = remember(tick) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
        var showPicker by remember { mutableStateOf(false) }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing) // keep clear of camera cut-out and system bars
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("FlipTunes", style = MaterialTheme.typography.headlineSmall)

            // 1. Setup
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    SetupRow("Notification access (to see what's playing)", hasNotificationAccess) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    SetupRow("Bluetooth permission", hasBluetooth) {
                        permissionLauncher.launch(requiredPermissions())
                    }
                    SetupRow("Optional: music beat for LED pulse (microphone)", hasMic) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
            }

            // 2. Bridge + Flipper
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Flipper", style = MaterialTheme.typography.titleMedium)
                    Text("Device: ${Prefs.name(context) ?: "none chosen"}")
                    Text("Link: ${link.name.lowercase()} - $linkDetail")
                    Text("Beat: $beatStatus", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (running) stopBridge() else startBridge() },
                            enabled = hasNotificationAccess && hasBluetooth,
                        ) { Text(if (running) "Stop bridge" else "Start bridge") }
                        OutlinedButton(onClick = { showPicker = true }, enabled = hasBluetooth) {
                            Text("Choose Flipper")
                        }
                    }
                }
            }

            // 3. What's playing
            val artBits by AppState.artBits.collectAsStateWithLifecycle()
            NowPlayingCard(nowPlaying, artBits)

            // 4. Test controls: work without the Flipper, to check the phone side
            Text("Test controls (phone only)", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Prev" to Protocol.Command.PREVIOUS,
                    "Play" to Protocol.Command.PLAY_PAUSE,
                    "Next" to Protocol.Command.NEXT,
                    "Vol-" to Protocol.Command.VOLUME_DOWN,
                    "Vol+" to Protocol.Command.VOLUME_UP,
                ).forEach { (label, command) ->
                    FilledTonalButton(
                        onClick = { Bridge.command(command) },
                        enabled = running,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) { Text(label, maxLines = 1, softWrap = false) }
                }
            }
            if (!running) Text("Start the bridge to use the test controls.", style = MaterialTheme.typography.bodySmall)

            // 5. Log
            Text("Log", style = MaterialTheme.typography.titleMedium)
            log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.height(24.dp))
        }

        if (showPicker) DevicePicker(onDismiss = { showPicker = false })
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (done) "OK   " else "--   ") + label, modifier = Modifier.weight(1f))
        if (!done) TextButton(onClick = onFix) { Text("Allow") }
    }
}

@Composable
private fun NowPlayingCard(np: NowPlaying?, artBits: ByteArray?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Now playing", style = MaterialTheme.typography.titleMedium)
            if (np == null) {
                Text("Nothing playing (or the bridge isn't running)")
            } else {
                if (artBits != null) {
                    // Exactly what the Flipper shows, scaled up with sharp pixels
                    val preview = remember(artBits) { ArtEncoder.preview(artBits).asImageBitmap() }
                    Image(preview, contentDescription = "Flipper album art preview",
                        modifier = Modifier.size(144.dp), filterQuality = FilterQuality.None)
                }
                Text(np.title.ifBlank { "Unknown title" }, style = MaterialTheme.typography.titleLarge)
                Text(np.artist.ifBlank { "Unknown artist" })
                Text("${np.appName} - ${if (np.isPlaying) "playing" else "paused"}",
                    style = MaterialTheme.typography.bodySmall)
                if (np.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${formatTime(np.positionMs)} / ${formatTime(np.durationMs)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Lists paired Flipper devices plus any found by a Bluetooth scan. */
@SuppressLint("MissingPermission")
@Composable
private fun DevicePicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java).adapter }
    val found = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        adapter?.bondedDevices?.forEach { d ->
            val name = d.name ?: return@forEach
            if (name.contains("Flipper", ignoreCase = true)) {
                found[d.address] = name
            }
        }
    }
    DisposableEffect(Unit) {
        val scanner = adapter?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (name.contains("Flipper", ignoreCase = true) && result.device.address !in found) {
                    found[result.device.address] = name
                }
            }
        }
        scanner?.startScan(callback)
        onDispose { scanner?.stopScan(callback) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Choose your Flipper") },
        text = {
            Column {
                if (found.isEmpty()) Text("Searching... make sure Bluetooth is on in the Flipper's settings.")
                found.forEach { (address, name) ->
                    TextButton(onClick = {
                        Prefs.save(context, address, name)
                        Bridge.link?.reconnect()
                        onDismiss()
                    }) { Text("$name\n$address") }
                }
            }
        },
    )
}

