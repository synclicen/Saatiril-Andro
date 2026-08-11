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
import com.saatiril.andro.ble.BLEServerManager
import com.saatiril.andro.ble.BLEProtocol
import com.saatiril.andro.data.AdminViewModel
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
private val BLUE = Color(0xFF3b82f6)

/**
 * MC Remote Server Screen — MC HP acts as BLE GATT Server.
 *
 * Used when Admin = Electron (Laptop). The Electron app connects
 * to this MC HP via Web Bluetooth API (as BLE Client/Central).
 *
 * Flow:
 *  1. MC HP advertises "Saatiril MC" BLE service
 *  2. Electron Admin scans + connects via Web Bluetooth
 *  3. Electron sends queue_data + next_student → MC displays
 *  4. MC presses PANGGIL → BLE notify Electron
 *  5. Electron receives trigger → callStudent → photo → notify MC "done"
 *
 * This is the REVERSED architecture from MCRemoteScreen:
 *  - MCRemoteScreen: MC = Client, Admin APK = Server
 *  - MCRemoteServerScreen: MC = Server, Electron = Client
 */
@Composable
fun MCRemoteServerScreen(adminViewModel: AdminViewModel) {
    val context = LocalContext.current
    val bleServer = remember { BLEServerManager(context) }

    var isAdvertising by remember { mutableStateOf(false) }
    var connectedClientCount by remember { mutableStateOf(0) }
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
    ) { results -> hasBtPermission = results.values.all { it } }

    LaunchedEffect(Unit) {
        if (!hasBtPermission) {
            btPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    // Set up BLE server callbacks
    DisposableEffect(Unit) {
        // When Electron writes to trigger characteristic
        bleServer.onTriggerReceived = { action, studentId ->
            // Electron sends actions to MC — but in this reversed mode,
            // MC is the one sending triggers. So this callback handles
            // status updates from Electron (e.g., "DONE" when photo saved)
            if (action == "STATUS_DONE") {
                statusPhase = BLEProtocol.Phase.DONE
            } else if (action == "STATUS_READY") {
                statusPhase = BLEProtocol.Phase.READY
            }
        }

        onDispose {
            bleServer.stop()
        }
    }

    // Start advertising automatically when permission granted
    LaunchedEffect(hasBtPermission) {
        if (hasBtPermission && !isAdvertising && bleServer.isBluetoothAvailable()) {
            val started = bleServer.start()
            isAdvertising = started
        }
    }

    // Poll connected clients count
    LaunchedEffect(isAdvertising) {
        while (isAdvertising) {
            connectedClientCount = bleServer.getConnectedClientCount()
            kotlinx.coroutines.delay(2000)
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
            val connColor = if (connectedClientCount > 0) GREEN else if (isAdvertising) AMBER else RED
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(connColor))
            Text("MC BLE SERVER", style = TextStyle(color = BLUE, fontSize = 14.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.weight(1f))
            if (connectedClientCount > 0) {
                Text("$connectedClientCount terhubung", style = TextStyle(color = GREEN, fontSize = 10.sp))
            }
            Card(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        bleServer.stop()
                        adminViewModel.backToRoleSelect()
                    },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = RED, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (!hasBtPermission) {
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

        if (!bleServer.isBluetoothAvailable()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.BluetoothDisabled, contentDescription = null, tint = RED, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Bluetooth tidak aktif", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text("Aktifkan Bluetooth di pengaturan HP", style = TextStyle(color = MUTED, fontSize = 12.sp))
            }
            return
        }

        // ── Status card ──
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = if (connectedClientCount > 0) CARD.copy(alpha = 0.7f) else PANEL),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (connectedClientCount > 0) GREEN.copy(alpha = 0.7f) else BORDER)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (connectedClientCount == 0) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = AMBER, modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    Text("Menunggu Electron Admin connect…", style = TextStyle(color = AMBER, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(4.dp))
                    Text("Aktifkan Bluetooth di laptop admin,\nlalu buka Saatiril → scan Bluetooth", style = TextStyle(color = MUTED, fontSize = 10.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = GREEN, modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    Text("✓ Electron Admin Terhubung", style = TextStyle(color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }

        // ── Next Student + Trigger (only when connected) ──
        if (connectedClientCount > 0) {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)
            ) {
                // Student name card
                val studentName = nextStudent?.optString("nama") ?: ""
                val studentNim = nextStudent?.optString("nim") ?: ""

                val phaseColor = when (statusPhase) {
                    BLEProtocol.Phase.READY -> GOLD
                    BLEProtocol.Phase.CAPTURING -> CYAN
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
                        val phaseText = when (statusPhase) {
                            BLEProtocol.Phase.STANDBY -> "Menunggu"
                            BLEProtocol.Phase.READY -> "◆ Sedang Dipanggil"
                            BLEProtocol.Phase.CAPTURING -> "📸 Sedang Foto"
                            BLEProtocol.Phase.DONE -> "✓ Selesai"
                            else -> "Standby"
                        }
                        Text(phaseText, style = TextStyle(color = phaseColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(8.dp))
                        if (studentName.isNotBlank()) {
                            Text(studentName, style = TextStyle(color = if (statusPhase == "standby") Color.White else GOLD, fontSize = 22.sp, fontWeight = FontWeight.Black, lineHeight = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (studentNim.isNotBlank()) {
                                Text(studentNim, style = TextStyle(color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                            }
                        } else {
                            Text("Tidak ada mahasiswa", style = TextStyle(color = MUTED, fontSize = 16.sp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // PANGGIL button
                val canPanggil = statusPhase == "standby" || statusPhase == "done"
                Button(
                    onClick = {
                        // Send trigger to Electron via BLE notification
                        val json = JSONObject().apply {
                            put("action", BLEProtocol.Action.PANGGIL)
                            nextStudent?.let { put("studentId", it.optString("id")) }
                        }.toString()
                        bleServer.updateStatus(json)
                        statusPhase = BLEProtocol.Phase.READY
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (canPanggil) GREEN else BORDER),
                    shape = RoundedCornerShape(12.dp),
                    enabled = canPanggil
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = BG)
                    Spacer(Modifier.width(8.dp))
                    Text("PANGGIL", color = BG, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }

                Spacer(Modifier.height(6.dp))

                // Secondary buttons
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val json = JSONObject().apply {
                                put("action", BLEProtocol.Action.NEXT)
                            }.toString()
                            bleServer.updateStatus(json)
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CYAN),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CYAN)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("NEXT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val json = JSONObject().apply {
                                put("action", BLEProtocol.Action.RESET)
                            }.toString()
                            bleServer.updateStatus(json)
                        },
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

                // Queue stats
                Spacer(Modifier.height(8.dp))
                if (queueData != null) {
                    val total = queueData!!.optInt("total", 0)
                    val pending = queueData!!.optInt("pending", 0)
                    val done = queueData!!.optInt("done", 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Menunggu: $pending", style = TextStyle(color = MUTED, fontSize = 11.sp))
                        Text("Selesai: $done", style = TextStyle(color = GREEN, fontSize = 11.sp))
                        Text("Total: $total", style = TextStyle(color = MUTED, fontSize = 11.sp))
                    }

                    // Queue list
                    Spacer(Modifier.height(8.dp))
                    val students = queueData!!.optJSONArray("students")
                    if (students != null && students.length() > 0) {
                        Text("Antrean Berikutnya:", style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        for (i in 0 until students.length()) {
                            val s = students.optJSONObject(i)
                            val name = s?.optString("nama") ?: ""
                            val status = s?.optString("status") ?: "pending"
                            val isActive = status.startsWith("active")
                            val isDone = status == "done"
                            val rowBg = when { isActive -> CARD.copy(alpha = 0.4f); isDone -> PANEL.copy(alpha = 0.3f); else -> PANEL }
                            val dotColor = when { isActive -> GOLD; isDone -> GREEN.copy(alpha = 0.5f); else -> MUTED.copy(alpha = 0.3f) }

                            Card(colors = CardDefaults.cardColors(containerColor = rowBg), shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) GOLD.copy(alpha = 0.4f) else BORDER.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${i+1}", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(14.dp))
                                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(dotColor))
                                    Text(name, style = TextStyle(color = if (isDone) MUTED.copy(alpha = 0.4f) else Color.White, fontSize = 11.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 8.sp))
                                    if (isDone) Text("✓", style = TextStyle(color = GREEN, fontSize = 9.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
