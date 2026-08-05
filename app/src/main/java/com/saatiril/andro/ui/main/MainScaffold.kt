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
import com.saatiril.andro.ui.admin.AdminDashboardScreen
import com.saatiril.andro.ui.mc.McScreen

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val CYAN = Color(0xFF06b6d4)

private enum class Tab(val label: String) { ADMIN("Admin"), MC("MC"), OPERATOR("Operator") }

/**
 * Main scaffold shown while the server is running. Three tabs (matching the
 * Electron app's unified view):
 *  - Admin: dashboard (project summary, server info + QR, clients, gallery, DB)
 *  - MC: call students to the stage
 *  - Operator: backup camera using the admin phone's built-in Camera2
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

    var selectedTab by remember { mutableStateOf(Tab.ADMIN) }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(BG),
        containerColor = BG,
        topBar = {
            TopBar(
                projectName = project?.name ?: "Saatiril",
                lanIp = lanIp,
                port = port,
                clientCount = clients.count { it.authenticated },
                running = running,
                onStop = { viewModel.stopServer() }
            )
        },
        bottomBar = { BottomBar(selected = selectedTab, onSelect = { selectedTab = it }) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(BG)) {
            when (selectedTab) {
                Tab.ADMIN -> AdminDashboardScreen(viewModel)
                Tab.MC -> McScreen(viewModel)
                Tab.OPERATOR -> com.saatiril.andro.ui.operator.AdminOperatorScreen(viewModel)
            }
        }
    }
}

@Composable
private fun TopBar(projectName: String, lanIp: String?, port: Int, clientCount: Int, running: Boolean, onStop: () -> Unit) {
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
                    Text(if (running) "Server aktif • ${lanIp ?: "-"}:$port • $clientCount klien" else "Server berhenti",
                        style = TextStyle(color = MUTED, fontSize = 10.sp), maxLines = 1)
                }
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
            TabItem(Tab.ADMIN, Icons.Default.Dashboard, selected == Tab.ADMIN, onSelect, Modifier.weight(1f))
            TabItem(Tab.MC, Icons.Default.Mic, selected == Tab.MC, onSelect, Modifier.weight(1f))
            TabItem(Tab.OPERATOR, Icons.Default.PhotoCamera, selected == Tab.OPERATOR, onSelect, Modifier.weight(1f))
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
