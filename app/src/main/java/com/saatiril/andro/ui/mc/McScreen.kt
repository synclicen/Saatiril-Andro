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
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val GREEN = Color(0xFF4ade80)
private val AMBER = Color(0xFFfbbf24)
private val RED = Color(0xFFef4444)

/**
 * MC panel — matches Electron mc-panel.tsx for all 4 modes.
 *
 * NO "Selesai" button — completion is event-driven.
 * Shows OP_PROGRESS live so MC knows operator status.
 * Queue list shows ALL channel students with status badges (not just pending).
 * Photoshoot: full participant list + sent students panel with per-channel completion.
 */
@Composable
fun McScreen(viewModel: AdminViewModel, modifier: Modifier = Modifier) {
    val project by viewModel.project.collectAsState()
    val myChannel by viewModel.myChannel.collectAsState()
    val opProgress by viewModel.opProgress.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedStudent by remember { mutableStateOf<Student?>(null) }

    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
    val isDual = CameraModes.isDualMode(mode)
    val isDualPhotoshoot = mode == CameraModes.DUAL_PHOTOSHOOT
    val photoHistory = project?.photoHistory ?: emptyList()

    // Channel filtering
    val channelStudents = if (isPhotoshoot) db else db.filter { it.assignedChannel == myChannel }

    val q = searchQuery.trim().lowercase()
    val searchResults = if (isPhotoshoot && q.isNotEmpty()) {
        channelStudents.filter {
            it.nama.lowercase().contains(q) || it.nim.lowercase().contains(q)
        }
    } else emptyList()

    val pending = channelStudents.filter { it.status == "pending" }
    val active = if (isPhotoshoot) emptyList() else channelStudents.filter { isActiveStatus(it.status) }
    val sent = channelStudents.filter { it.status == "sent" }
    val done = channelStudents.filter { it.status == "done" }
    val total = channelStudents.size

    val nextPending = if (!isPhotoshoot) channelStudents.firstOrNull { it.status == "pending" } else null
    val hasActive = active.isNotEmpty()

    // OP_PROGRESS text for current channel
    val opProgressText = opProgress[myChannel] ?: opProgress.values.firstOrNull() ?: ""

    val scrollState = rememberScrollState()

    // Auto-scroll to TOP when active student changes — active student is sorted
    // to position #1 so MC sees: active (being photographed) + next pending students below.
    val activeStudentId = active.firstOrNull()?.id
    val doneCount = done.size
    LaunchedEffect(activeStudentId, doneCount) {
        kotlinx.coroutines.delay(100)
        scrollState.animateScrollTo(0)
    }

    // ── STICKY HEADER — 2 sections side by side: LEFT=PANGGIL, RIGHT=Operator Status ──
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // Sticky top section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Channel selector (dual modes)
            if (isDual) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Jalur:", style = TextStyle(color = MUTED, fontSize = 9.sp))
                    ChannelPill(1, myChannel == 1) { viewModel.setMyChannel(1) }
                    ChannelPill(2, myChannel == 2) { viewModel.setMyChannel(2) }
                }
            }

            // ── #1: TWO SECTIONS SIDE BY SIDE ──
            // LEFT: PANGGIL button + next student name
            // RIGHT: Operator status (live OP_PROGRESS)
            if (!isPhotoshoot) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // LEFT: Call section
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Next student name
                        if (nextPending != null && !hasActive) {
                            val shortName = nextPending.nama.take(20).ifBlank { nextPending.nim.take(20) }
                            Text("→ $shortName", style = TextStyle(color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        } else if (hasActive) {
                            val act = active.firstOrNull()
                            Text("◆ ${act?.nama?.take(20) ?: ""}", style = TextStyle(color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        }
                        // PANGGIL button
                        Button(
                            onClick = { nextPending?.let { viewModel.callStudent(it, myChannel) } },
                            modifier = Modifier.fillMaxWidth().height(30.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (!hasActive && nextPending != null) GOLD else BORDER),
                            shape = RoundedCornerShape(6.dp),
                            enabled = !hasActive && nextPending != null,
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            if (hasActive) {
                                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = GOLD, strokeWidth = 2.dp)
                                Spacer(Modifier.width(3.dp))
                                Text("TUNGGU…", color = GOLD, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            } else if (nextPending != null) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(10.dp), tint = BG)
                                Spacer(Modifier.width(3.dp))
                                Text("PANGGIL", color = BG, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("HABIS", color = MUTED, fontSize = 8.sp)
                            }
                        }
                    }
                    // RIGHT: Operator status (live)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.5f))) {
                            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = CYAN, modifier = Modifier.size(10.dp))
                                    Text("Operator", style = TextStyle(color = MUTED, fontSize = 8.sp))
                                }
                                Text(
                                    if (opProgressText.isNotEmpty()) opProgressText else if (hasActive) "Memproses…" else "Standby",
                                    style = TextStyle(color = if (opProgressText.contains("Selesai")) GREEN else CYAN, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                        }
                        // Reset button (compact, only when active)
                        if (hasActive) {
                            OutlinedButton(
                                onClick = { active.firstOrNull()?.let { viewModel.resetStudent(it.id, getActiveChannel(it.status) ?: myChannel) } },
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AMBER),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Reset", fontSize = 8.sp)
                            }
                        }
                    }
                }
            }

            // Photoshoot: search + send (same as before but compact)
            if (isPhotoshoot) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    label = { Text("Cari Peserta", color = MUTED, fontSize = 9.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = GREEN, unfocusedBorderColor = BORDER, cursorColor = GREEN,
                        focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
                    ),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(12.dp)) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = "Hapus", tint = MUTED, modifier = Modifier.size(12.dp).clickable { searchQuery = "" }) },
                    textStyle = TextStyle(fontSize = 10.sp)
                )
                val selStudent = selectedStudent
                if (selStudent != null) {
                    if (selStudent.status == "done") {
                        Button(
                            onClick = { viewModel.resetStudent(selStudent.id, selStudent.assignedChannel); selectedStudent = null; searchQuery = "" },
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AMBER), shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(10.dp), tint = BG); Spacer(Modifier.width(2.dp)); Text("RESET & KIRIM ULANG", color = BG, fontWeight = FontWeight.Bold, fontSize = 8.sp) }
                    } else {
                        Button(
                            onClick = { if (isDualPhotoshoot) viewModel.sendToOperator(selStudent, listOf(1, 2)) else viewModel.sendToOperator(selStudent, listOf(myChannel)); selectedStudent = null; searchQuery = "" },
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GREEN), shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(10.dp), tint = BG); Spacer(Modifier.width(2.dp)); Text(if (isDualPhotoshoot) "KIRIM KE 2 KAMERA" else "KIRIM KE OPERATOR", color = BG, fontWeight = FontWeight.Bold, fontSize = 8.sp) }
                    }
                }
            }

            // #2: Compact stats (tiny inline, single row)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Menunggu: ${pending.size}", style = TextStyle(color = MUTED, fontSize = 8.sp))
                if (isPhotoshoot) Text("Dikirim: ${sent.size}", style = TextStyle(color = CYAN, fontSize = 8.sp))
                Text("Selesai: ${done.size}", style = TextStyle(color = GREEN, fontSize = 8.sp))
            }
        }

        // ── SCROLLABLE QUEUE LIST — sorted: active #1, then pending, then done ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (isPhotoshoot) {
                // Photoshoot: search results + sent list + full participant list
                if (searchResults.isNotEmpty()) {
                    Text("Hasil Pencarian (${searchResults.size})", style = TextStyle(color = GREEN, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    searchResults.take(10).forEach { s ->
                        StudentSearchRow(s, isSelected = selectedStudent?.id == s.id, onSelect = { selectedStudent = s })
                    }
                }
                if (sent.isNotEmpty()) {
                    Text("Dikirim (${sent.size})", style = TextStyle(color = CYAN, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    sent.take(10).forEach { s ->
                        SentStudentRow(s, photoHistory, isDualPhotoshoot) { viewModel.resetStudent(s.id, s.assignedChannel) }
                    }
                }
                if (q.isEmpty()) {
                    Text("Semua Peserta ($total)", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    channelStudents.take(50).forEach { s ->
                        StudentListRow(s, isSelected = selectedStudent?.id == s.id) { selectedStudent = s }
                    }
                    if (channelStudents.size > 50) Text("… dan ${channelStudents.size - 50} lainnya", style = TextStyle(color = MUTED, fontSize = 8.sp), modifier = Modifier.padding(2.dp))
                }
            } else {
                // Non-photoshoot: queue SORTED — active student at #1, then pending, then done
                // so MC sees: active (being photographed) + next students below.
                val rawFilter = if (q.isEmpty()) channelStudents else channelStudents.filter {
                    it.nama.contains(q, ignoreCase = true) || it.nim.contains(q, ignoreCase = true)
                }
                val queueFilter = rawFilter.sortedWith(compareBy { s ->
                    when {
                        isActiveStatus(s.status) -> 0
                        s.status == "pending" -> 1
                        s.status == "sent" -> 2
                        s.status == "done" -> 3
                        else -> 4
                    }
                })
                if (q.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        label = { Text("Cari Nama / NIM", color = MUTED, fontSize = 9.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD,
                            focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
                        ),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(12.dp)) },
                        textStyle = TextStyle(fontSize = 10.sp)
                    )
                }
                Text("Antrean Ch.$myChannel (${pending.size} menunggu)", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                if (queueFilter.isEmpty()) {
                    Text(if (q.isNotEmpty()) "Tidak ada cocok" else "Antrean habis",
                        Modifier.padding(6.dp), style = TextStyle(color = MUTED, fontSize = 10.sp))
                } else {
                    queueFilter.take(60).forEachIndexed { idx, s ->
                        QueueRowWithStatus(idx + 1, s, myChannel) { viewModel.callStudent(s, myChannel) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── Active Target Card with OP_PROGRESS ─────────────────────
@Composable
private fun ActiveTargetCard(student: Student, channel: Int, opProgressText: String, onReset: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.6f)), shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GOLD.copy(alpha = 0.6f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp), maxLines = 1)
                    Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                }
                Card(colors = CardDefaults.cardColors(containerColor = GOLD), shape = RoundedCornerShape(4.dp)) {
                    Text("Ch.$channel", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = TextStyle(color = BG, fontWeight = FontWeight.Bold, fontSize = 9.sp))
                }
            }
            // OP_PROGRESS text (live operator status)
            if (opProgressText.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = CYAN, modifier = Modifier.size(12.dp))
                    Text("Operator: $opProgressText", style = TextStyle(color = CYAN, fontSize = 10.sp), maxLines = 1)
                }
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, AMBER),
                contentPadding = PaddingValues(vertical = 4.dp)) {
                Icon(Icons.Default.Undo, contentDescription = null, Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)); Text("Reset (Ulang)", fontSize = 10.sp)
            }
        }
    }
}

// ─── Queue Row with Status Badge (matches Electron renderStatusBadge) ───
@Composable
private fun QueueRowWithStatus(number: Int, student: Student, channel: Int, onCall: () -> Unit) {
    val isActive = isActiveStatus(student.status)
    val isDone = student.status == "done"
    val isSent = student.status == "sent"
    val statusColor = when {
        isActive -> GOLD
        isSent -> CYAN
        isDone -> GREEN.copy(alpha = 0.5f)
        else -> MUTED
    }
    val rowBg = when {
        isActive -> CARD.copy(alpha = 0.4f)
        isDone -> PANEL.copy(alpha = 0.3f)
        else -> PANEL
    }
    // No per-row call button — matches Electron mc-panel.tsx which uses a single
    // PANGGIL SEKARANG button at the top. Queue rows are display-only with status dots.
    Card(colors = CardDefaults.cardColors(containerColor = rowBg), shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) GOLD.copy(alpha = 0.4f) else BORDER.copy(alpha = 0.3f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("$number", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(14.dp))
            // Status dot (matches Electron green/purple dots)
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
            Column(Modifier.weight(1f)) {
                Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = if (isDone) MUTED.copy(alpha = 0.5f) else Color.White, fontSize = 10.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium), maxLines = 1)
            }
            // Status badge text only (no button)
            val statusLabel = when {
                isActive -> "◆"
                isSent -> "Dikirim"
                isDone -> "✓"
                else -> ""
            }
            if (statusLabel.isNotEmpty()) {
                Text(statusLabel, style = TextStyle(color = statusColor, fontSize = 8.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ─── Student Search Row (photoshoot) ──────────────────────────
@Composable
private fun StudentSearchRow(student: Student, isSelected: Boolean, onSelect: () -> Unit) {
    val isDone = student.status == "done"
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) GREEN else BORDER.copy(alpha = 0.4f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (isDone) Icons.Default.CheckCircle else Icons.Default.Person, contentDescription = null,
                tint = if (isDone) GREEN else MUTED, modifier = Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            }
            Text(
                if (isDone) "Selesai" else if (student.status == "sent") "Dikirim" else "Menunggu",
                style = TextStyle(color = if (isDone) GREEN else if (student.status == "sent") CYAN else MUTED, fontSize = 8.sp)
            )
        }
    }
}

// ─── Sent Student Row with per-channel completion (photoshoot) ──
@Composable
private fun SentStudentRow(student: Student, photoHistory: List<com.saatiril.andro.data.PhotoHistoryItem>, isDualPhotoshoot: Boolean, onReset: () -> Unit) {
    val ch1Done = photoHistory.any { it.student.id == student.id && it.channel == 1 && it.photos.isNotEmpty() }
    val ch2Done = photoHistory.any { it.student.id == student.id && it.channel == 2 && it.photos.isNotEmpty() }
    Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CYAN.copy(alpha = 0.3f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            }
            // Per-channel completion indicators
            if (isDualPhotoshoot) {
                ChannelCompletionBadge("Ch1", ch1Done)
                ChannelCompletionBadge("Ch2", ch2Done)
            }
            IconButton(onClick = onReset, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = AMBER, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ChannelCompletionBadge(label: String, done: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = if (done) GREEN.copy(alpha = 0.2f) else BORDER.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (done) GREEN else BORDER)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(if (done) Icons.Default.Check else Icons.Default.Close, contentDescription = null, tint = if (done) GREEN else MUTED, modifier = Modifier.size(8.dp))
            Text(label, style = TextStyle(color = if (done) GREEN else MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold))
        }
    }
}

// ─── Student List Row (photoshoot full list) ──────────────────
@Composable
private fun StudentListRow(student: Student, isSelected: Boolean, onSelect: () -> Unit) {
    val isDone = student.status == "done"
    val isSent = student.status == "sent"
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(
                when { isDone -> GREEN; isSent -> CYAN; else -> MUTED.copy(alpha = 0.3f) }
            ))
            Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = if (isDone) MUTED.copy(alpha = 0.5f) else Color.White, fontSize = 10.sp), modifier = Modifier.weight(1f), maxLines = 1)
            Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun ChannelPill(channel: Int, isSelected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .border(1.dp, if (isSelected) GOLD else BORDER, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL), shape = RoundedCornerShape(14.dp)) {
        Text("Jalur $channel", modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = TextStyle(color = if (isSelected) GOLD else MUTED, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 10.sp))
    }
}

@Composable
private fun StatCard(label: String, count: Int, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.3f))) {
        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(count.toString(), style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp))
            Text(label, style = TextStyle(color = MUTED, fontSize = 8.sp))
        }
    }
}
