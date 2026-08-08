package com.saatiril.andro.ui.main

import androidx.compose.foundation.background
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

    var selectedTab by remember { mutableStateOf(Tab.PROSESI) }
    var isFullscreen by remember { mutableStateOf(false) }

    // #7: Fullscreen immersive mode toggle
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
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
        // MC panel (top, 35% of screen — compact so camera gets more space)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
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
        // Operator camera (bottom, 65% of screen — more space for preview)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f)
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
