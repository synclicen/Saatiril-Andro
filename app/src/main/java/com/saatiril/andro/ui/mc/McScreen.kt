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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.OperatorViewModel
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus

// ─── Saatiril Theme Colors ──────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val RED = Color(0xFFef4444)
private val GREEN = Color(0xFF4ade80)
private val AMBER = Color(0xFFfbbf24)

/**
 * MC (Master of Ceremony) Screen.
 *
 * Mirrors the Electron/web MC panel:
 *  - Searchable queue of pending students
 *  - "Call on Ch.1" / "Call on Ch.2" buttons per student
 *  - Current active target cards (one per channel)
 *  - Sent / Done counters
 *
 * The MC broadcasts MC_CALL over the LAN socket; the Operator on the chosen
 * channel picks the target up automatically.
 */
@Composable
fun McScreen(
    viewModel: OperatorViewModel,
    modifier: Modifier = Modifier
) {
    val project by viewModel.project.collectAsState()
    val myChannel by viewModel.myChannel.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableIntStateOf(myChannel) }

    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isDual = CameraModes.isDualMode(mode)

    val pending = db.filter { it.status == "pending" }
        .filter {
            val q = searchQuery.trim()
            q.isEmpty() || it.nama.contains(q, ignoreCase = true) ||
                    it.nim.contains(q, ignoreCase = true)
        }
    val active = db.filter { isActiveStatus(it.status) }
    val sent = db.filter { it.status == "sent" }
    val done = db.filter { it.status == "done" }
    val total = db.size

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ─── Project header ───
        project?.let { proj ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Event, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            proj.name.ifBlank { "Proyek Wisuda" },
                            style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                            maxLines = 1
                        )
                        Text(
                            "MC • $total mahasiswa • Mode ${proj.config.mode}",
                            style = TextStyle(color = MUTED, fontSize = 11.sp)
                        )
                    }
                }
            }
        } ?: run {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = GOLD, strokeWidth = 2.dp)
                    Text(
                        "Menunggu data proyek dari Admin...",
                        style = TextStyle(color = MUTED, fontSize = 13.sp)
                    )
                }
            }
        }

        // ─── Channel selector (only in dual mode) ───
        if (isDual) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Channel aktif:", style = TextStyle(color = MUTED, fontSize = 13.sp))
                ChannelPill(1, selectedChannel == 1) { selectedChannel = 1 }
                ChannelPill(2, selectedChannel == 2) { selectedChannel = 2 }
            }
        }

        // ─── Active targets ───
        if (active.isNotEmpty()) {
            Text(
                "Sedang Dipanggil",
                style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            )
            active.forEach { student ->
                val ch = getActiveChannel(student.status) ?: selectedChannel
                ActiveTargetCard(
                    student = student,
                    channel = ch,
                    onReset = { viewModel.resetStudent(student.id, ch) },
                    onDone = { viewModel.markStudentDone(student.id, ch) }
                )
            }
        }

        // ─── Stats row ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("Menunggu", pending.size, MUTED, Icons.Default.Schedule, Modifier.weight(1f))
            StatCard("Dikirim", sent.size, CYAN, Icons.Default.Send, Modifier.weight(1f))
            StatCard("Selesai", done.size, GREEN, Icons.Default.CheckCircle, Modifier.weight(1f))
        }

        // ─── Search ───
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Cari Nama / NIM", color = MUTED, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = GOLD,
                unfocusedBorderColor = BORDER,
                cursorColor = GOLD,
                focusedContainerColor = PANEL,
                unfocusedContainerColor = PANEL
            ),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Hapus",
                        tint = MUTED,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            },
            textStyle = TextStyle(fontSize = 14.sp)
        )

        // ─── Pending queue ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Antrian (${pending.size})",
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            )
            Text(
                "Klik ▶ untuk panggil Ch.$selectedChannel",
                style = TextStyle(color = MUTED, fontSize = 11.sp)
            )
        }

        if (pending.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    if (searchQuery.isNotEmpty()) "Tidak ada mahasiswa cocok dengan pencarian"
                    else "Semua mahasiswa sudah dipanggil",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = MUTED, fontSize = 13.sp)
                )
            }
        } else {
            pending.take(200).forEach { student ->
                PendingStudentRow(
                    student = student,
                    isDual = isDual,
                    onCallChannel1 = { viewModel.callStudent(student, 1) },
                    onCallChannel2 = { viewModel.callStudent(student, 2) }
                )
            }
            if (pending.size > 200) {
                Text(
                    "… dan ${pending.size - 200} lainnya (persempit pencarian)",
                    style = TextStyle(color = MUTED, fontSize = 11.sp),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ─── Active Target Card ──────────────────────────────────────
@Composable
private fun ActiveTargetCard(
    student: Student,
    channel: Int,
    onReset: () -> Unit,
    onDone: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GOLD.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        student.nama.ifBlank { "(tanpa nama)" },
                        style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                        maxLines = 1
                    )
                    Text(
                        student.nim.ifBlank { "-" },
                        style = TextStyle(color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    )
                }
                ChannelBadge(channel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AMBER)
                ) {
                    Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", fontSize = 12.sp)
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GREEN),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = BG)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Selesai", color = BG, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Pending Student Row ─────────────────────────────────────
@Composable
private fun PendingStudentRow(
    student: Student,
    isDual: Boolean,
    onCallChannel1: () -> Unit,
    onCallChannel2: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    student.nama.ifBlank { "(tanpa nama)" },
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Text(
                    student.nim.ifBlank { "-" },
                    style = TextStyle(color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                )
            }
            if (isDual) {
                ChannelCallButton("Ch.1", onCallChannel1, compact = true)
                ChannelCallButton("Ch.2", onCallChannel2, compact = true)
            } else {
                ChannelCallButton("Panggil", onCallChannel1, compact = false)
            }
        }
    }
}

@Composable
private fun ChannelCallButton(label: String, onClick: () -> Unit, compact: Boolean) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = GOLD),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = BG)
        Spacer(modifier = Modifier.width(3.dp))
        Text(label, color = BG, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ─── Channel Pill (selector) ─────────────────────────────────
@Composable
private fun ChannelPill(channel: Int, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) GOLD else BORDER,
                shape = RoundedCornerShape(20.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            "Channel $channel",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = TextStyle(
                color = if (isSelected) GOLD else MUTED,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun ChannelBadge(channel: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GOLD),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            "Ch.$channel",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = TextStyle(color = BG, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        )
    }
}

// ─── Stat Card ───────────────────────────────────────────────
@Composable
private fun StatCard(label: String, count: Int, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(
                count.toString(),
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            )
            Text(label, style = TextStyle(color = MUTED, fontSize = 10.sp))
        }
    }
}
