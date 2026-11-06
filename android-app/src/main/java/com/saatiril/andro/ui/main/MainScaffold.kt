package com.saatiril.andro.ui.main

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.ui.admin.AdminDashboardScreen
import com.saatiril.andro.ui.mc.McScreen
import com.saatiril.andro.ui.operator.AdminOperatorScreen

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val CYAN = Color(0xFF06b6d4)

private enum class Tab(val label: String) { PROSESI("Prosesi"), ADMIN("Admin") }

/**
 * Main scaffold shown while the server is running. Two tabs:
 *  - Prosesi: MC + Operator camera combined in a split view (MC on top,
 *    camera preview + shutter on the bottom). This mirrors the Electron
 *    app's unified main-app.tsx where MC and Operator are visible together
 *    so the admin can call a student and immediately photograph them.
 *  - Admin: dashboard (project summary, QR codes, gallery, DB, export).
 *
 * The top bar shows the live server status (LAN IP, clients) and a "Stop"
 * button that tears down the server and returns to the Hub.
 */
@Composable
fun MainScaffold(viewModel: AdminViewModel) {
    val project by viewModel.project.collectAsState()
    val clients by viewModel.serverClients.collectAsState()
    val lanIp by viewModel.lanIp.collectAsState()
    val port by viewModel.serverPort.collectAsState()
    val running by viewModel.serverRunning.collectAsState()
    val isLocked by viewModel.lockTaskActive.collectAsState()

    var selectedTab by remember { mutableStateOf(Tab.PROSESI) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isDeviceOwner by remember { mutableStateOf(false) }
    var showLockHelp by remember { mutableStateOf(false) }

    // #7: Fullscreen immersive mode toggle
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity

    // Check Device Owner status once on entry (used to show the ADB-setup
    // hint in the lock-help dialog).
    LaunchedEffect(Unit) {
        isDeviceOwner = activity?.let { viewModel.checkDeviceOwner(it) } ?: false
    }

    // ── Request BLE permissions (Android 12+) so the BLE server can advertise ──
    // ROOT CAUSE of "MC remote tidak menemukan admin saat scan": the saatiril-
    // andro.apk (admin) runs the BLE server (BleServerManager) but never
    // requested BLUETOOTH_ADVERTISE at runtime → advertising failed silently on
    // Android 12+ → the MC remote scanned but found nothing, even though the
    // admin was active. Request BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT +
    // ACCESS_FINE_LOCATION on launch; after granted, retry the BLE advertising
    // (startBLEServer ran before the permission was granted → advertising
    // failed → retry now that the permission is granted).
    val blePermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[android.Manifest.permission.BLUETOOTH_ADVERTISE] == true) {
            // Permission granted — retry the BLE advertising so the MC remote
            // can discover + connect to the admin.
            viewModel.retryBleAdvertising()
        }
    }
    LaunchedEffect(Unit) {
        val perms = arrayOf(
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val needRequest = perms.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) {
            blePermissionLauncher.launch(perms)
        }
    }
    LaunchedEffect(isFullscreen) {
        activity?.window?.let { window ->
            if (isFullscreen) {
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ── Anti-minimize: intercept the hardware back button ──────────
    // Without this, pressing Back from MainScaffold minimizes the app,
    // which tears down the camera and forces a 5-second reconnect on
    // return. Show a confirmation dialog instead — only stop the server
    // (and return to the Hub) if the user explicitly confirms.
    var showExitConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = true) {
        showExitConfirm = true
    }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            containerColor = PANEL,
            titleContentColor = GOLD,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
                    Text("Hentikan Prosesi?", color = GOLD, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Jika Anda keluar sekarang, server akan dihentikan dan koneksi ke " +
                        "operator/MC akan terputus. Kamera juga akan dilepas dan perlu " +
                        "dihubungkan kembali saat Anda masuk lagi.\n\n" +
                        "Yakin hentikan prosesi?",
                    color = MUTED, fontSize = 12.sp, lineHeight = 16.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        viewModel.stopServer()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RED),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ya, Hentikan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitConfirm = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GREEN),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Lanjutkan Prosesi", fontSize = 12.sp)
                }
            }
        )
    }

    // ── Lock Task Mode help dialog ───────────────────────────────
    if (showLockHelp) {
        AlertDialog(
            onDismissRequest = { showLockHelp = false },
            containerColor = PANEL,
            titleContentColor = GOLD,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
                    Text("Mode Kiosk (Lock Task)", color = GOLD, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDeviceOwner) {
                        // True kiosk mode — Device Owner is set
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(GREEN.copy(alpha = 0.1f))
                                .border(1.dp, GREEN.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
                            Text(
                                "Device Owner aktif. Klik tombol Lock untuk masuk mode kiosk penuh — tombol Home, Back, dan Recents akan diblokir total.",
                                color = GREEN, fontSize = 11.sp, lineHeight = 15.sp
                            )
                        }
                    } else {
                        // Manual pinning mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(GOLD.copy(alpha = 0.08f))
                                .border(1.dp, GOLD.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = GOLD, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Mode Pin Manual (tanpa ADB):",
                                    color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Klik tombol Lock → muncul dialog sistem \"Pin this app?\" → konfirmasi. " +
                                        "Untuk keluar: tahan Back + Recents bersamaan (gestur sistem, tidak bisa diblokir).",
                                    color = MUTED, fontSize = 10.sp, lineHeight = 14.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Untuk kiosk penuh (blokir Home/Back/Recents total, tanpa bisa di-bypass), " +
                                "set perangkat sebagai Device Owner via ADB SEKALI saja:",
                            color = MUTED, fontSize = 11.sp, lineHeight = 15.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        // ADB command box
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(BG)
                                .border(1.dp, BORDER, RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Di laptop (dengan ADB terinstall):", color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "adb shell dpm set-device-owner \\\n  com.saatiril.andro/.admin.SaatirilDeviceAdminReceiver",
                                color = GOLD,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                            Text(
                                "Catatan: tidak boleh ada akun Google di perangkat. Factory reset dulu jika perlu.",
                                color = MUTED.copy(alpha = 0.7f), fontSize = 9.sp, lineHeight = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Saat terkunci: tombol Stop dan Fullscreen tetap bisa dipakai dari dalam app. " +
                            "Klik tombol Lock lagi untuk membuka kunci.",
                        color = MUTED.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLockHelp = false },
                    colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Mengerti", color = BG, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(BG),
        containerColor = BG,
        topBar = {
            TopBar(
                projectName = project?.name ?: "Saatiril",
                mode = project?.config?.mode ?: CameraModes.SINGLE,
                lanIp = lanIp,
                port = port,
                clientCount = clients.count { it.authenticated },
                running = running,
                isFullscreen = isFullscreen,
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                isLocked = isLocked,
                isDeviceOwner = isDeviceOwner,
                onToggleLock = {
                    activity?.let { viewModel.toggleLockTask(it) }
                },
                onShowLockHelp = { showLockHelp = true },
                onStop = { viewModel.stopServer() }
            )
        },
        bottomBar = { BottomBar(selected = selectedTab, onSelect = { selectedTab = it }) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(BG)) {
            when (selectedTab) {
                Tab.PROSESI -> ProsesiScreen(viewModel)
                Tab.ADMIN -> AdminDashboardScreen(viewModel)
            }
        }
    }
}

/**
 * Prosesi tab — MC + Operator camera combined in a vertical split.
 * Top half: MC panel (call students, queue, search for photoshoot).
 * Bottom half: Camera preview + shutter (capture Toga/Ijazah or photoshoot).
 *
 * This matches the Electron app's unified view where MC and Operator are
 * side-by-side so the admin can do the entire ceremony from one screen.
 */
@Composable
private fun ProsesiScreen(viewModel: AdminViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // MC panel (top, 26% — room for dominant name card + PANGGIL button)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.26f)
        ) {
            McScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BORDER)
        )
        // Operator camera (bottom, 74% — camera gets most space)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.74f)
        ) {
            AdminOperatorScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TopBar(
    projectName: String,
    mode: String,
    lanIp: String?,
    port: Int,
    clientCount: Int,
    running: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    isLocked: Boolean,
    isDeviceOwner: Boolean,
    onToggleLock: () -> Unit,
    onShowLockHelp: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = PANEL, tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(projectName, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (running) GREEN else RED))
                    val modeLabel = when (mode) {
                        CameraModes.SINGLE -> "Single"
                        CameraModes.DUAL -> "Dual"
                        CameraModes.SINGLE_PHOTOSHOOT -> "Photoshoot"
                        CameraModes.DUAL_PHOTOSHOOT -> "Dual-PS"
                        else -> mode
                    }
                    Text(
                        "$modeLabel • ${lanIp ?: "-"}:$port • $clientCount klien",
                        style = TextStyle(color = MUTED, fontSize = 10.sp), maxLines = 1
                    )
                }
            }
            // ── Lock Task Mode toggle (kiosk) ──────────────────────────
            // When locked: green Lock icon. When unlocked: gold LockOpen icon.
            // Long-press / help icon shows the setup instructions dialog.
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onToggleLock, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isLocked) "Buka Kunci" else "Kunci Mode Kiosk",
                        tint = if (isLocked) GREEN else GOLD,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // Small badge dot when in true kiosk mode (Device Owner)
                if (isLocked && isDeviceOwner) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(GREEN)
                    )
                }
            }
            // Help / info about kiosk mode (long-press target)
            IconButton(onClick = onShowLockHelp, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Info, contentDescription = "Bantuan Kiosk", tint = MUTED, modifier = Modifier.size(18.dp))
            }
            // #7: Fullscreen toggle button
            IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = GOLD, modifier = Modifier.size(20.dp)
                )
            }
            OutlinedButton(onClick = onStop, shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RED),
                border = androidx.compose.foundation.BorderStroke(1.dp, RED),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stop", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Surface(color = PANEL, tonalElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem(Tab.PROSESI, Icons.Default.PhotoCamera, selected == Tab.PROSESI, onSelect, Modifier.weight(1f))
            TabItem(Tab.ADMIN, Icons.Default.Dashboard, selected == Tab.ADMIN, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(tab: Tab, icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick(tab) },
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else Color.Transparent),
        shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, contentDescription = tab.label, tint = if (isSelected) GOLD else MUTED, modifier = Modifier.size(22.dp))
            Text(tab.label, style = TextStyle(color = if (isSelected) GOLD else MUTED, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp))
        }
    }
}
