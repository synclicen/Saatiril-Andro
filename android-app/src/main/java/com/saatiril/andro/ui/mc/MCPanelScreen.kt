package com.saatiril.andro.ui.mc

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.ConnectionState
import com.saatiril.andro.data.OperatorViewModel
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import kotlinx.coroutines.delay

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
 * MC Panel Screen — the main MC panel when connected to the admin's server.
 *
 * Shows the live queue of students and a PANGGIL button to call the next
 * student to the stage. This is the same UI as the admin's McScreen but
 * connected to a REMOTE server via [OperatorViewModel].
 *
 * The MC can:
 *  - See the current active student (being photographed)
 *  - See the next pending student
 *  - Press PANGGIL to call the next student
 *  - Reset a student if needed
 *  - See status (active/pending/done) for each student
 */
@Composable
fun MCPanelScreen(
    adminViewModel: AdminViewModel,
    opViewModel: OperatorViewModel = viewModel()
) {
    val context = LocalContext.current
    val project by opViewModel.project.collectAsState()
    val connectionState by opViewModel.connectionState.collectAsState()
    val myChannel by opViewModel.myChannel.collectAsState()
    val opQueue by opViewModel.opQueue.collectAsState()
    val currentTarget by opViewModel.currentTarget.collectAsState()
    val scrollState = rememberScrollState()

    // Lock screen — prevent accidental exit via back button
    com.saatiril.andro.ui.util.LockScreenHandler {
        opViewModel.disconnect()
        adminViewModel.backToRoleSelect()
    }

    // Auto-disconnect handling
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            delay(500)
            adminViewModel.mcDisconnected()
        }
    }

    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
    val isDual = CameraModes.isDualMode(mode)

    val channelStudents = if (isPhotoshoot) db else db.filter { it.assignedChannel == myChannel }
    val pending = channelStudents.filter { it.status == "pending" }
    val active = if (isPhotoshoot) emptyList() else channelStudents.filter { isActiveStatus(it.status) }
    val done = channelStudents.filter { it.status == "done" }
    val hasActive = active.isNotEmpty()
    val nextPending = if (!isPhotoshoot) channelStudents.firstOrNull { it.status == "pending" } else null

    // Auto-scroll to active student position — bring to TOP of visible area
    val activeStudentId = active.firstOrNull()?.id
    LaunchedEffect(activeStudentId, done.size) {
        delay(150)
        val activeIdx = channelStudents.indexOfFirst { it.id == activeStudentId }
        if (activeIdx >= 0) {
            // Scroll so active student is at TOP of visible area
            val targetScroll = (activeIdx * 38 - 38).coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll.coerceAtMost(scrollState.maxValue))
        } else {
            scrollState.animateScrollTo(0)
        }
    }

    // ORIGINAL ORDER — no sorting, keep queue numbers
    val queueFilter = channelStudents

    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // ── Top bar: connection + disconnect ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val connColor = when (connectionState) {
                ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> GREEN
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.AUTHENTICATING -> GOLD
                else -> RED
            }
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(connColor))
            Text("MC Ch.$myChannel", style = TextStyle(color = connColor, fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            if (isDual) {
                Card(modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { },
                    colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(10.dp)) {
                    Text("Ch.$myChannel", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
            }
            // Disconnect button
            Card(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        opViewModel.disconnect()
                        adminViewModel.backToRoleSelect()
                    },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Logout, contentDescription = "Disconnect", tint = RED, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── DOMINANT NAME CARD ──
        if (!isPhotoshoot) {
            val displayName = when {
                hasActive -> active.firstOrNull()?.nama?.ifBlank { null } ?: active.firstOrNull()?.nim ?: ""
                nextPending != null -> nextPending.nama.ifBlank { nextPending.nim }
                else -> "Antrean Habis"
            }
            val displayNim = when {
                hasActive -> active.firstOrNull()?.nim ?: ""
                nextPending != null -> nextPending?.nim ?: ""
                else -> ""
            }
            val nameColor = when {
                hasActive -> GOLD
                nextPending != null -> Color.White
                else -> MUTED
            }
            val namePrefix = when {
                hasActive -> "◆ "
                nextPending != null -> "▶ "
                else -> ""
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = if (hasActive) CARD.copy(alpha = 0.7f) else PANEL),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (hasActive) GOLD.copy(alpha = 0.7f) else BORDER.copy(alpha = 0.4f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "$namePrefix$displayName",
                        style = TextStyle(color = nameColor, fontSize = 22.sp, fontWeight = FontWeight.Black, lineHeight = 24.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (displayNim.isNotEmpty()) {
                        Text(displayNim, style = TextStyle(color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    }
                }
            }

            // ── PANGGIL / TUNGGU / RESET button ──
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { nextPending?.let { opViewModel.callStudent(it, myChannel) } },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (!hasActive && nextPending != null) GREEN else BORDER),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !hasActive && nextPending != null
                ) {
                    if (hasActive) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GOLD, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("TUNGGU OPERATOR…", color = GOLD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else if (nextPending != null) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG)
                        Spacer(Modifier.width(8.dp))
                        Text("PANGGIL", color = BG, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    } else {
                        Text("HABIS", color = MUTED, fontSize = 13.sp)
                    }
                }
                if (hasActive) {
                    OutlinedButton(
                        onClick = { active.firstOrNull()?.let { opViewModel.resetStudent(it.id, getActiveChannel(it.status) ?: myChannel) } },
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AMBER)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset", fontSize = 12.sp)
                    }
                }
            }

            // Stats
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Menunggu: ${pending.size}", style = TextStyle(color = MUTED, fontSize = 10.sp))
                Text("Selesai: ${done.size}", style = TextStyle(color = GREEN, fontSize = 10.sp))
                Text("Total: ${channelStudents.size}", style = TextStyle(color = MUTED, fontSize = 10.sp))
            }
        } else {
            // Photoshoot mode — MC just sees stats (no PANGGIL)
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mode Photoshoot", style = TextStyle(color = GOLD, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text("MC tidak memanggil — operator pilih dari daftar", style = TextStyle(color = MUTED, fontSize = 11.sp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Queue list ──
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Antrean Ch.$myChannel (${pending.size} menunggu)", style = TextStyle(color = MUTED, fontWeight = FontWeight.Bold, fontSize = 11.sp))
            if (queueFilter.isEmpty()) {
                Text("Antrean habis", Modifier.padding(8.dp), style = TextStyle(color = MUTED, fontSize = 12.sp))
            } else {
                queueFilter.forEachIndexed { idx, s ->
                    MCQueueRow(idx + 1, s, myChannel)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MCQueueRow(number: Int, student: Student, channel: Int) {
    val isActive = isActiveStatus(student.status)
    val isDone = student.status == "done"
    val statusColor = when {
        isActive -> GOLD
        isDone -> GREEN.copy(alpha = 0.5f)
        else -> MUTED
    }
    val rowBg = when {
        isActive -> CARD.copy(alpha = 0.4f)
        isDone -> PANEL.copy(alpha = 0.3f)
        else -> PANEL
    }
    Card(colors = CardDefaults.cardColors(containerColor = rowBg), shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) GOLD.copy(alpha = 0.4f) else BORDER.copy(alpha = 0.2f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(16.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
            Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = if (isDone) MUTED.copy(alpha = 0.4f) else Color.White, fontSize = 12.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            if (isDone) Text("✓", style = TextStyle(color = GREEN, fontSize = 10.sp))
        }
    }
}
