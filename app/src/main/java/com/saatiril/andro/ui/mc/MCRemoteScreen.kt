package com.saatiril.andro.ui.mc

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.saatiril.andro.ble.BLEClientManager
import com.saatiril.andro.ble.BLEProtocol
import com.saatiril.andro.data.AdminViewModel
import org.json.JSONArray
import org.json.JSONObject

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val CYAN = Color(0xFF06b6d4)
private val AMBER = Color(0xFFfbbf24)

/**
 * MC Remote Screen — the MC's HP interface for BLE remote trigger.
 *
 * This screen:
 *  1. Scans for the Admin's BLE server
 *  2. Connects via Bluetooth LE
 *  3. Displays the queue + next student
 *  4. Sends trigger commands (PANGGIL, NEXT, RESET)
 *  5. Receives status updates (foto selesai)
 *
 * NO WiFi is used — 100% immune to WiFi interference from 3000+ phones.
 * Photos are saved directly on the Admin's local folder.
 */
@Composable
fun MCRemoteScreen(adminViewModel: AdminViewModel) {
    val context = LocalContext.current

    // Lock screen — prevent accidental exit via back button
    com.saatiril.andro.ui.util.LockScreenHandler {
        adminViewModel.backToRoleSelect()
    }

    // BLE client state
    val bleClient = remember { BLEClientManager(context) }
    var isScanning by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var foundDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    // Data from Admin
    var nextStudent by remember { mutableStateOf<JSONObject?>(null) }
    var statusPhase by remember { mutableStateOf("standby") }
    var queueData by remember { mutableStateOf<JSONObject?>(null) }
    var projectInfo by remember { mutableStateOf<JSONObject?>(null) }

    // Bluetooth permission
    var hasBtPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasBtPermission = results.values.all { it }
    }

    // Request permissions on launch
    LaunchedEffect(Unit) {
        if (!hasBtPermission) {
            btPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    // Set up BLE callbacks
    DisposableEffect(Unit) {
        bleClient.onConnectionStateChanged = { connected ->
            isConnected = connected
            if (!connected) {
                nextStudent = null
                statusPhase = "standby"
            }
        }
        bleClient.onNextStudentReceived = { json ->
            try { nextStudent = JSONObject(json) } catch (_: Exception) {}
        }
        bleClient.onStatusReceived = { json ->
            try {
                val obj = JSONObject(json)
                statusPhase = obj.optString("phase", "standby")
            } catch (_: Exception) {}
        }
        bleClient.onQueueDataReceived = { json ->
            try { queueData = JSONObject(json) } catch (_: Exception) {}
        }
        bleClient.onProjectInfoReceived = { json ->
            try { projectInfo = JSONObject(json) } catch (_: Exception) {}
        }
        bleClient.onDeviceFound = { device ->
            if (foundDevices.none { it.address == device.address }) {
                foundDevices = foundDevices + device
            }
        }

        onDispose {
            bleClient.stopScan()
            bleClient.close()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val connColor = if (isConnected) GREEN else if (isScanning) GOLD else RED
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(connColor))
            Text("MC REMOTE", style = TextStyle(color = GOLD, fontSize = 16.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.weight(1f))
            if (projectInfo != null) {
                Text(
                    projectInfo!!.optString("projectName", "Saatiril"),
                    style = TextStyle(color = MUTED, fontSize = 11.sp)
                )
            }
            // Back button
            Card(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { adminViewModel.backToRoleSelect() },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = RED, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (!hasBtPermission) {
            // Permission request
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = GOLD, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Izin Bluetooth Diperlukan", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        btPermissionLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Berikan Izin Bluetooth", color = BG, fontWeight = FontWeight.Bold) }
            }
            return
        }

        if (!isConnected) {
            // ── Scan & Connect Screen ──
            MCRemoteScanScreen(
                bleClient = bleClient,
                isScanning = isScanning,
                foundDevices = foundDevices,
                onStartScan = {
                    if (bleClient.isBluetoothAvailable()) {
                        foundDevices = emptyList()
                        isScanning = bleClient.startScan()
                    }
                },
                onStopScan = {
                    bleClient.stopScan()
                    isScanning = false
                },
                onConnect = { device ->
                    bleClient.connect(device)
                }
            )
        } else {
            // ── Connected: Show MC Remote Panel ──
            MCRemoteConnectedScreen(
                nextStudent = nextStudent,
                statusPhase = statusPhase,
                queueData = queueData,
                onTrigger = { action ->
                    val studentId = nextStudent?.optString("id")
                    bleClient.sendTrigger(action, studentId)
                }
            )
        }
    }
}

@Composable
private fun MCRemoteScanScreen(
    bleClient: BLEClientManager,
    isScanning: Boolean,
    foundDevices: List<BluetoothDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = GOLD, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Cari Admin Saatiril", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        Text("Pastikan Bluetooth di Admin HP/Laptop aktif", style = TextStyle(color = MUTED, fontSize = 12.sp))

        Spacer(Modifier.height(24.dp))

        if (!bleClient.isBluetoothAvailable()) {
            Card(colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.1f)), shape = RoundedCornerShape(8.dp)) {
                Text(
                    "Bluetooth tidak aktif. Aktifkan Bluetooth di pengaturan HP.",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = RED, fontSize = 12.sp)
                )
            }
        } else {
            Button(
                onClick = { if (isScanning) onStopScan() else onStartScan() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) BORDER else GOLD),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GOLD, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Mencari...", color = GOLD, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG)
                    Spacer(Modifier.width(8.dp))
                    Text("SCAN ADMIN", color = BG, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (foundDevices.isNotEmpty()) {
                Text("Perangkat Ditemukan:", style = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                foundDevices.forEach { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onConnect(device) },
                        colors = CardDefaults.cardColors(containerColor = PANEL),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = GREEN, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name ?: "Unknown Device", style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                                Text(device.address, style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = GOLD, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            } else if (isScanning) {
                Text("Mencari perangkat Admin...", style = TextStyle(color = MUTED, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun MCRemoteConnectedScreen(
    nextStudent: JSONObject?,
    statusPhase: String,
    queueData: JSONObject?,
    onTrigger: (String) -> Unit
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)
    ) {
        // ── Dominant Name Card ──
        val studentName = nextStudent?.optString("nama") ?: ""
        val studentNim = nextStudent?.optString("nim") ?: ""
        val studentId = nextStudent?.optString("id") ?: ""

        val phaseColor = when (statusPhase) {
            BLEProtocol.Phase.READY -> GOLD
            BLEProtocol.Phase.CAPTURING -> CYAN
            BLEProtocol.Phase.SENDING -> CYAN
            BLEProtocol.Phase.DONE -> GREEN
            else -> MUTED
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            colors = CardDefaults.cardColors(containerColor = if (statusPhase != "standby") CARD.copy(alpha = 0.7f) else PANEL),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, phaseColor.copy(alpha = 0.7f))
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                // Phase label
                val phaseText = when (statusPhase) {
                    BLEProtocol.Phase.STANDBY -> "Menunggu"
                    BLEProtocol.Phase.READY -> "◆ Sedang Dipanggil"
                    BLEProtocol.Phase.CAPTURING -> "📸 Sedang Foto"
                    BLEProtocol.Phase.SENDING -> "💾 Menyimpan"
                    BLEProtocol.Phase.DONE -> "✓ Selesai"
                    else -> "Standby"
                }
                Text(phaseText, style = TextStyle(color = phaseColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))

                Spacer(Modifier.height(8.dp))

                if (studentName.isNotBlank()) {
                    Text(
                        studentName,
                        style = TextStyle(
                            color = if (statusPhase == "standby") Color.White else GOLD,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 24.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (studentNim.isNotBlank()) {
                        Text(studentNim, style = TextStyle(color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    }
                } else {
                    Text("Tidak ada mahasiswa", style = TextStyle(color = MUTED, fontSize = 16.sp))
                }
            }
        }

        // ── Trigger Buttons ──
        Spacer(Modifier.height(8.dp))

        // PANGGIL button (primary)
        val canPanggil = statusPhase == "standby" || statusPhase == "done"
        Button(
            onClick = { onTrigger(BLEProtocol.Action.PANGGIL) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canPanggil) GREEN else BORDER
            ),
            shape = RoundedCornerShape(12.dp),
            enabled = canPanggil
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = BG)
            Spacer(Modifier.width(8.dp))
            Text("PANGGIL", color = BG, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }

        Spacer(Modifier.height(6.dp))

        // Secondary buttons row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // NEXT
            OutlinedButton(
                onClick = { onTrigger(BLEProtocol.Action.NEXT) },
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CYAN),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CYAN)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("NEXT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // RESET
            OutlinedButton(
                onClick = { onTrigger(BLEProtocol.Action.RESET) },
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AMBER)
            ) {
                Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("RESET", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Queue Stats ──
        Spacer(Modifier.height(8.dp))
        if (queueData != null) {
            val total = queueData.optInt("total", 0)
            val pending = queueData.optInt("pending", 0)
            val done = queueData.optInt("done", 0)
            val active = queueData.optInt("active", 0)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Menunggu: ${pending}", style = TextStyle(color = MUTED, fontSize = 11.sp))
                Text("Selesai: ${done}", style = TextStyle(color = GREEN, fontSize = 11.sp))
                Text("Total: ${total}", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }

            // Queue list
            Spacer(Modifier.height(8.dp))
            val students = queueData.optJSONArray("students")
            if (students != null && students.length() > 0) {
                Text("Antrean Berikutnya:", style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                for (i in 0 until students.length()) {
                    val s = students.optJSONObject(i)
                    val name = s?.optString("nama") ?: ""
                    val nim = s?.optString("nim") ?: ""
                    val status = s?.optString("status") ?: "pending"
                    val isActive = status.startsWith("active")
                    val isDone = status == "done"

                    val rowBg = when {
                        isActive -> CARD.copy(alpha = 0.4f)
                        isDone -> PANEL.copy(alpha = 0.3f)
                        else -> PANEL
                    }
                    val dotColor = when {
                        isActive -> GOLD
                        isDone -> GREEN.copy(alpha = 0.5f)
                        else -> MUTED.copy(alpha = 0.3f)
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = rowBg),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) GOLD.copy(alpha = 0.4f) else BORDER.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("${i + 1}", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(14.dp))
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(dotColor))
                            Text(
                                name.ifBlank { nim },
                                style = TextStyle(
                                    color = if (isDone) MUTED.copy(alpha = 0.4f) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 8.sp))
                            if (isDone) Text("✓", style = TextStyle(color = GREEN, fontSize = 9.sp))
                        }
                    }
                }
            }
        }
    }
}
