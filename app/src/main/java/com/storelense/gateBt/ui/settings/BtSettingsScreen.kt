package com.storelense.gateBt.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storelense.gateBt.rfid.RfidBluetoothDevice

private val TealPrimary = Color(0xFF0F766E)
private val TealAccent  = Color(0xFF14B8A6)
private val SubText     = Color(0xFF64748B)
private val DarkText    = Color(0xFF1E293B)
private val BgPage      = Color(0xFFF5F7FA)

/** Permissions required to scan for and connect to BT devices. */
private val BT_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtSettingsScreen(
    onBack: () -> Unit,
    vm: BtSettingsViewModel = hiltViewModel()
) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var permDenied by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permDenied = false
            vm.startScan()
        } else {
            permDenied = true
        }
    }

    fun launchScan() {
        val allGranted = BT_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permDenied = false
            vm.startScan()
        } else {
            permLauncher.launch(BT_PERMISSIONS)
        }
    }

    state.savedMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BT Reader Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                }
            )
        },
        containerColor = BgPage,
        snackbarHost = {
            state.savedMessage?.let { msg ->
                Snackbar(modifier = Modifier.padding(16.dp)) {
                    Text(msg, fontSize = 13.sp)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier        = Modifier.fillMaxSize().padding(padding),
            contentPadding  = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Current device ───────────────────────────────────────────────
            item {
                Text("Current Reader", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SubText)
                Spacer(Modifier.height(6.dp))
                Card(
                    colors    = CardDefaults.cardColors(containerColor = Color.White),
                    shape     = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    val s = state.currentSettings
                    if (s.bluetoothAddress != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bluetooth, null, Modifier.size(28.dp), tint = TealPrimary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.bluetoothName ?: "Unknown device",
                                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DarkText
                                )
                                Text(s.bluetoothAddress, fontSize = 12.sp, color = SubText)
                                Text("TX Power: ${s.txPowerDbm} dBm", fontSize = 12.sp, color = SubText)
                            }
                            IconButton(onClick = { vm.clearDevice() }) {
                                Icon(Icons.Default.Delete, "Remove device", tint = Color(0xFFDC2626))
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.BluetoothDisabled, null, Modifier.size(28.dp), tint = SubText)
                            Spacer(Modifier.width(12.dp))
                            Text("No reader configured", fontSize = 14.sp, color = SubText)
                        }
                    }
                }
            }

            // ── TX Power slider ──────────────────────────────────────────────
            if (state.currentSettings.bluetoothAddress != null) {
                item {
                    Text("TX Power (dBm)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SubText)
                    Spacer(Modifier.height(6.dp))
                    Card(
                        colors    = CardDefaults.cardColors(containerColor = Color.White),
                        shape     = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            var txPower by remember(state.currentSettings.txPowerDbm) {
                                mutableStateOf(state.currentSettings.txPowerDbm.toFloat())
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${txPower.toInt()} dBm", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TealPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("(range: 5–30 dBm)", fontSize = 12.sp, color = SubText)
                            }
                            Slider(
                                value                = txPower,
                                onValueChange        = { txPower = it },
                                onValueChangeFinished = { vm.saveTxPower(txPower.toInt()) },
                                valueRange           = 5f..30f,
                                steps                = 24,
                                colors               = SliderDefaults.colors(
                                    activeTrackColor = TealPrimary,
                                    thumbColor       = TealPrimary
                                )
                            )
                        }
                    }
                }
            }

            // ── Scan button ──────────────────────────────────────────────────
            item {
                Text("Discover Devices", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SubText)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick  = if (state.isScanning) vm::stopScan else ::launchScan,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (state.isScanning) Color(0xFF6B7280) else TealPrimary
                    )
                ) {
                    if (state.isScanning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Scanning… (tap to stop)")
                    } else {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan for Devices")
                    }
                }

                // Permission denied warning
                if (permDenied) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                        shape  = RoundedCornerShape(8.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = Color(0xFFDC2626))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Bluetooth permission denied. Enable it in System Settings → Apps → StoreLense Gate BT → Permissions.",
                                fontSize = 12.sp, color = Color(0xFFDC2626)
                            )
                        }
                    }
                }
            }

            // ── Discovered device list ───────────────────────────────────────
            if (state.discoveredDevices.isNotEmpty()) {
                item {
                    Text(
                        "${state.discoveredDevices.size} device${if (state.discoveredDevices.size != 1) "s" else ""} found — tap to select",
                        fontSize = 12.sp, color = SubText
                    )
                }
                items(state.discoveredDevices, key = { it.address }) { device ->
                    DiscoveredDeviceRow(
                        device    = device,
                        isCurrent = device.address == state.currentSettings.bluetoothAddress,
                        onSelect  = { vm.selectDevice(device) }
                    )
                }
            }

            // ── Tips ─────────────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                    shape  = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = Color(0xFF2563EB))
                            Spacer(Modifier.width(8.dp))
                            Text("Tips", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1E40AF))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "• Pair the MUBR01 in Android Bluetooth settings first — it appears instantly here without scanning.\n" +
                            "• Tap Scan to discover unpaired devices (allows ~12 seconds for Classic BT + BLE).\n" +
                            "• Higher TX power = longer read range but faster battery drain.",
                            fontSize = 12.sp, color = Color(0xFF1E40AF), lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device:    RfidBluetoothDevice,
    isCurrent: Boolean,
    onSelect:  () -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors    = CardDefaults.cardColors(
            containerColor = if (isCurrent) TealPrimary.copy(alpha = 0.08f) else Color.White
        ),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(if (isCurrent) 0.dp else 1.dp),
        border    = if (isCurrent)
            androidx.compose.foundation.BorderStroke(2.dp, TealAccent)
        else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth, null, Modifier.size(22.dp),
                tint = if (isCurrent) TealPrimary else SubText
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    color = if (isCurrent) TealPrimary else DarkText
                )
                Text(device.address, fontSize = 12.sp, color = SubText)
            }
            if (isCurrent) {
                Icon(Icons.Default.CheckCircle, "Selected", Modifier.size(20.dp), tint = TealPrimary)
            } else {
                Text("Select", fontSize = 12.sp, color = TealPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
