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
import com.saatiril.andro.data.ConnectionState
import com.saatiril.andro.data.OperatorViewModel
import com.saatiril.andro.data.Roles
import com.saatiril.andro.ui.admin.AdminScreen
import com.saatiril.andro.ui.mc.McScreen
import com.saatiril.andro.ui.operator.OperatorScreen

// ─── Saatiril Theme Colors ──────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)

private enum class SaatirilTab(val label: String) {
    OPERATOR("Operator"),
    MC("MC"),
    ADMIN("Admin")
}

private fun defaultTabForRole(role: String): SaatirilTab = when (role) {
    Roles.ADMIN -> SaatirilTab.ADMIN
    Roles.MC -> SaatirilTab.MC
    else -> SaatirilTab.OPERATOR
}

/**
 * Main scaffold shown after a successful socket connection.
 *
 * Provides a top app bar (project name + live connection badge + disconnect)
 * and a bottom navigation bar to switch between the three Saatiril panels:
 * Operator, MC, and Admin. This mirrors the Electron app's unified view
 * where Admin/MC/Operator are all visible side-by-side — on a phone we use
 * tabs instead of a split layout.
 *
 * The default tab is chosen based on the role the user selected at connect
 * time, but the user can freely switch to view the other panels (read-only
 * for roles they did not log in as).
 */
@Composable
fun MainScaffold(
    viewModel: OperatorViewModel,
    hasCameraPermission: Boolean,
    onDisconnect: () -> Unit
) {
    val myRole by viewModel.myRole.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val project by viewModel.project.collectAsState()

    var selectedTab by remember { mutableStateOf(defaultTabForRole(myRole)) }

    // If the role changes (e.g. reconnect as different role), snap to default tab
    LaunchedEffect(myRole) {
        selectedTab = defaultTabForRole(myRole)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        containerColor = BG,
        topBar = {
            SaatirilTopBar(
                projectName = project?.name ?: "Saatiril Andro",
                role = myRole,
                connectionState = connectionState,
                latencyMs = latencyMs,
                onDisconnect = onDisconnect
            )
        },
        bottomBar = {
            SaatirilBottomBar(
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BG)
        ) {
            when (selectedTab) {
                SaatirilTab.OPERATOR -> OperatorScreen(
                    viewModel = viewModel,
                    hasCameraPermission = hasCameraPermission
                )
                SaatirilTab.MC -> McScreen(viewModel = viewModel)
                SaatirilTab.ADMIN -> AdminScreen(viewModel = viewModel)
            }
        }
    }
}

// ─── Top App Bar ─────────────────────────────────────────────
@Composable
private fun SaatirilTopBar(
    projectName: String,
    role: String,
    connectionState: ConnectionState,
    latencyMs: Long,
    onDisconnect: () -> Unit
) {
    Surface(
        color = PANEL,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    projectName,
                    style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val (dotColor, statusText) = when (connectionState) {
                        ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> GREEN to "Terhubung"
                        ConnectionState.CONNECTING -> MUTED to "Menghubungkan…"
                        ConnectionState.RECONNECTING -> MUTED to "Reconnect…"
                        ConnectionState.AUTHENTICATING -> MUTED to "Auth…"
                        ConnectionState.AUTH_FAILED -> RED to "Auth gagal"
                        ConnectionState.CONNECTED -> MUTED to "Terhubung"
                        ConnectionState.DISCONNECTED -> RED to "Terputus"
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(dotColor)
                    )
                    Text(
                        "$statusText • ${role.uppercase()}" +
                                (if (latencyMs >= 0) " • ${latencyMs}ms" else ""),
                        style = TextStyle(color = MUTED, fontSize = 10.sp)
                    )
                }
            }
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.Logout, contentDescription = "Putuskan", tint = RED, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ─── Bottom Navigation Bar ───────────────────────────────────
@Composable
private fun SaatirilBottomBar(
    selected: SaatirilTab,
    onSelect: (SaatirilTab) -> Unit
) {
    Surface(
        color = PANEL,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomTabItem(SaatirilTab.OPERATOR, Icons.Default.CameraAlt, selected == SaatirilTab.OPERATOR, onSelect, Modifier.weight(1f))
            BottomTabItem(SaatirilTab.MC, Icons.Default.Mic, selected == SaatirilTab.MC, onSelect, Modifier.weight(1f))
            BottomTabItem(SaatirilTab.ADMIN, Icons.Default.AdminPanelSettings, selected == SaatirilTab.ADMIN, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BottomTabItem(
    tab: SaatirilTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: (SaatirilTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick(tab) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CARD else Color.Transparent
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                icon,
                contentDescription = tab.label,
                tint = if (isSelected) GOLD else MUTED,
                modifier = Modifier.size(22.dp)
            )
            Text(
                tab.label,
                style = TextStyle(
                    color = if (isSelected) GOLD else MUTED,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp
                )
            )
        }
    }
}
