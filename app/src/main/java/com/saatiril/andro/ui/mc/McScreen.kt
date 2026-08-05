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
 * MC panel — matches the Electron mc-panel.tsx behavior for all 4 modes.
 *
 * NO "Selesai" button — completion is always event-driven:
 *  - single/dual: operator captures 2 photos (Toga+Ijazah) → emits STUDENT_DONE → auto-done
 *  - photoshoot: operator captures 1 photo → emits PHOTOS_SAVED → auto-done
 *
 * Mode behaviors:
 *  - single: sequential queue, "PANGGIL SEKARANG" sets status active_1
 *  - dual: sequential queue per channel, ch1 students → ch1 operator, ch2 → ch2 (INDEPENDENT)
 *  - single-photoshoot: search-first (urutan bebas), "KIRIM KE OPERATOR" sets status sent
 *  - dual-photoshoot: search-first, "KIRIM KE 2 KAMERA" sends to BOTH channels (cooperative)
 */
@Composable
fun McScreen(viewModel: AdminViewModel, modifier: Modifier = Modifier) {
    val project by viewModel.project.collectAsState()
    val myChannel by viewModel.myChannel.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedStudent by remember { mutableStateOf<Student?>(null) }

    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
    val isDual = CameraModes.isDualMode(mode)
    val isDualPhotoshoot = mode == CameraModes.DUAL_PHOTOSHOOT

    // Channel filtering (matches Electron mc-panel.tsx:100-107):
    // - single/dual: filter by assignedChannel === myChannel
    // - photoshoot: all students (no channel filter)
    val channelStudents = if (isPhotoshoot) db else db.filter { it.assignedChannel == myChannel }

    val q = searchQuery.trim().lowercase()
    val searchResults = if (isPhotoshoot && q.isNotEmpty()) {
        channelStudents.filter {
            it.nama.lowercase().contains(q) || it.nim.lowercase().contains(q)
        }
    } else emptyList()

    val pending = channelStudents.filter { it.status == "pending" }.filter {
        q.isEmpty() || it.nama.contains(q, ignoreCase = true) || it.nim.contains(q, ignoreCase = true)
    }
    val active = if (isPhotoshoot) emptyList() else channelStudents.filter { isActiveStatus(it.status) }
    val sent = channelStudents.filter { it.status == "sent" }
    val done = channelStudents.filter { it.status == "done" }
    val total = channelStudents.size

    // Find the next pending student for "PANGGIL SEKARANG" button (non-photoshoot)
    val nextPending = if (!isPhotoshoot) channelStudents.firstOrNull { it.status == "pending" } else null
    val hasActive = active.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ─── Channel selector (dual modes only) ───
        if (isDual) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Jalur:", style = TextStyle(color = MUTED, fontSize = 11.sp))
                ChannelPill(1, myChannel == 1) { viewModel.setMyChannel(1) }
                ChannelPill(2, myChannel == 2) { viewModel.setMyChannel(2) }
            }
        }

        // ─── Active target card (non-photoshoot: shows who's being photographed) ───
        if (!isPhotoshoot && hasActive) {
            active.forEach { student ->
                val ch = getActiveChannel(student.status) ?: myChannel
                ActiveTargetCard(student, ch, onReset = { viewModel.resetStudent(student.id, ch) })
            }
        }

        // ─── Stats ───
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatCard("Menunggu", pending.size, MUTED, Icons.Default.Schedule, Modifier.weight(1f))
            if (isPhotoshoot) {
                StatCard("Dikirim", sent.size, CYAN, Icons.Default.Send, Modifier.weight(1f))
            }
            StatCard("Selesai", done.size, GREEN, Icons.Default.CheckCircle, Modifier.weight(1f))
        }

        // ─── Photoshoot mode: search-first UI ───
        if (isPhotoshoot) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text("Cari Peserta — Urutan Bebas", color = MUTED, fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = GREEN, unfocusedBorderColor = BORDER, cursorColor = GREEN,
                    focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp)) },
                trailingIcon = { if (searchQuery.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = "Hapus", tint = MUTED, modifier = Modifier.size(16.dp).clickable { searchQuery = "" }) },
                textStyle = TextStyle(fontSize = 13.sp)
            )

            // Search results (photoshoot: select student to send)
            if (searchResults.isNotEmpty()) {
                Text("Hasil Pencarian (${searchResults.size})", style = TextStyle(color = GREEN, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                searchResults.take(10).forEach { s ->
                    val isDone = s.status == "done"
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selectedStudent = s },
                        colors = CardDefaults.cardColors(containerColor = if (selectedStudent?.id == s.id) CARD else PANEL),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedStudent?.id == s.id) GREEN else BORDER.copy(alpha = 0.5f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (isDone) Icons.Default.CheckCircle else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (isDone) GREEN else MUTED, modifier = Modifier.size(16.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(s.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                                Text(s.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                            }
                            Text(
                                if (isDone) "Selesai" else if (s.status == "sent") "Dikirim" else "Menunggu",
                                style = TextStyle(color = if (isDone) GREEN else if (s.status == "sent") CYAN else MUTED, fontSize = 9.sp)
                            )
                        }
                    }
                }
            }

            // Send button (photoshoot)
            val sendLabel = if (isDualPhotoshoot) "KIRIM KE 2 KAMERA" else "KIRIM KE OPERATOR"
            val selStudent = selectedStudent
            if (selStudent != null) {
                if (selStudent.status == "done") {
                    // Retake button
                    Button(
                        onClick = {
                            viewModel.resetStudent(selStudent.id, selStudent.assignedChannel)
                            selectedStudent = null
                            searchQuery = ""
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AMBER),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = BG)
                        Spacer(Modifier.width(6.dp))
                        Text("RESET & KIRIM ULANG", color = BG, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            if (isDualPhotoshoot) {
                                viewModel.sendToOperator(selStudent, listOf(1, 2))
                            } else {
                                viewModel.sendToOperator(selStudent, listOf(myChannel))
                            }
                            selectedStudent = null
                            searchQuery = ""
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GREEN),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = BG)
                        Spacer(Modifier.width(6.dp))
                        Text(sendLabel, color = BG, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        } else {
            // ─── Non-photoshoot: sequential queue + "PANGGIL SEKARANG" ───
            // Big call button at top (matches Electron renderCallButton)
            Button(
                onClick = {
                    nextPending?.let { viewModel.callStudent(it, myChannel) }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        hasActive -> BORDER  // disabled-looking while active
                        nextPending == null -> BORDER
                        else -> GOLD
                    }
                ),
                shape = RoundedCornerShape(10.dp),
                enabled = !hasActive && nextPending != null
            ) {
                if (hasActive) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GOLD, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("TUNGGU KAMERA…", color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else if (nextPending != null) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = BG)
                    Spacer(Modifier.width(6.dp))
                    Text("PANGGIL SEKARANG", color = BG, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("ANTREAN HABIS", color = MUTED, fontSize = 12.sp)
                }
            }

            // Search box for the queue
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text("Cari Nama / NIM", color = MUTED, fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD,
                    focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp)) },
                trailingIcon = { if (searchQuery.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = "Hapus", tint = MUTED, modifier = Modifier.size(16.dp).clickable { searchQuery = "" }) },
                textStyle = TextStyle(fontSize = 13.sp)
            )

            // Queue list
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Antrian (${pending.size})", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                Text("Ch.$myChannel", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }

            if (pending.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(8.dp)) {
                    Text(if (searchQuery.isNotEmpty()) "Tidak ada mahasiswa cocok" else "Antrean habis",
                        Modifier.padding(12.dp), style = TextStyle(color = MUTED, fontSize = 12.sp))
                }
            } else {
                pending.take(50).forEach { s ->
                    QueueRow(s) { viewModel.callStudent(s, myChannel) }
                }
                if (pending.size > 50) Text("… dan ${pending.size - 50} lainnya", style = TextStyle(color = MUTED, fontSize = 10.sp), modifier = Modifier.padding(4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ActiveTargetCard(student: Student, channel: Int, onReset: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.6f)), shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GOLD.copy(alpha = 0.6f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp), maxLines = 1)
                    Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                }
                Card(colors = CardDefaults.cardColors(containerColor = GOLD), shape = RoundedCornerShape(4.dp)) {
                    Text("Ch.$channel", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = TextStyle(color = BG, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                }
            }
            // NO "Selesai" button — completion is automatic when operator finishes.
            // Only a Reset button for retakes.
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, AMBER)) {
                Icon(Icons.Default.Undo, contentDescription = null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Reset (Ulang)", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun QueueRow(student: Student, onCall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
            }
            Button(onClick = onCall, colors = ButtonDefaults.buttonColors(containerColor = GOLD), shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Panggil", color = BG, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChannelPill(channel: Int, isSelected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .border(1.dp, if (isSelected) GOLD else BORDER, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL), shape = RoundedCornerShape(16.dp)) {
        Text("Jalur $channel", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = TextStyle(color = if (isSelected) GOLD else MUTED, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp))
    }
}

@Composable
private fun StatCard(label: String, count: Int, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.4f))) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(count.toString(), style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            Text(label, style = TextStyle(color = MUTED, fontSize = 9.sp))
        }
    }
}
