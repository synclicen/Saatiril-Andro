package com.saatiril.andro.ui.admin

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.OperatorViewModel
import com.saatiril.andro.data.PhotoHistoryItem
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import com.saatiril.andro.data.statusLabel

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
 * Admin Dashboard Screen (Android).
 *
 * On the Windows Electron side, Admin creates the project, imports the Excel
 * student list, sets the output folder, and owns the socket.io relay. On
 * Android, the Admin role is primarily an OBSERVATION panel: it shows live
 * project state synced via SYNC_DB so an admin walking around with a tablet
 * can monitor progress, view the photo gallery, and see the student DB.
 *
 * The Android Admin CAN reset / mark-done students (same socket events MC
 * uses), but cannot create projects or pick folders — those require the
 * Windows Electron app.
 */
@Composable
fun AdminScreen(
    viewModel: OperatorViewModel,
    modifier: Modifier = Modifier
) {
    val project by viewModel.project.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val myChannel by viewModel.myChannel.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("all") } // all|pending|active|sent|done

    val proj = project
    val db = proj?.database ?: emptyList()
    val total = db.size
    val done = db.count { it.status == "done" }
    val sent = db.count { it.status == "sent" }
    val active = db.count { isActiveStatus(it.status) }
    val pending = db.count { it.status == "pending" }
    val progress = if (total > 0) (done * 100 / total) else 0

    val filteredDb = db.filter {
        val q = searchQuery.trim()
        val matchesQuery = q.isEmpty() || it.nama.contains(q, ignoreCase = true) ||
                it.nim.contains(q, ignoreCase = true)
        val matchesStatus = when (filterStatus) {
            "pending" -> it.status == "pending"
            "active" -> isActiveStatus(it.status)
            "sent" -> it.status == "sent"
            "done" -> it.status == "done"
            else -> true
        }
        matchesQuery && matchesStatus
    }

    val recentPhotos = proj?.photoHistory
        ?.filter { it.photos.isNotEmpty() }
        ?.sortedByDescending { it.channel }
        ?.take(30) ?: emptyList()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ─── Project header + progress ───
        if (proj != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Dashboard, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                proj.name.ifBlank { "Proyek Wisuda" },
                                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                                maxLines = 1
                            )
                            Text(
                                "Mode ${proj.config.mode}  •  Rasio ${proj.config.ratio}  •  Ch $myChannel",
                                style = TextStyle(color = MUTED, fontSize = 11.sp)
                            )
                        }
                    }
                    // Progress bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Progress", style = TextStyle(color = MUTED, fontSize = 11.sp))
                            Text(
                                "$done / $total  ($progress%)",
                                style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = GREEN,
                            trackColor = BORDER
                        )
                    }
                }
            }
        } else {
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
                        "Menunggu data proyek dari server...",
                        style = TextStyle(color = MUTED, fontSize = 13.sp)
                    )
                }
            }
        }

        // ─── Stats grid ───
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Total", total, Color.White, Icons.Default.Group, Modifier.weight(1f))
            StatTile("Aktif", active, AMBER, Icons.Default.PhotoCamera, Modifier.weight(1f))
            StatTile("Selesai", done, GREEN, Icons.Default.CheckCircle, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Menunggu", pending, MUTED, Icons.Default.Schedule, Modifier.weight(1f))
            StatTile("Latensi", if (latencyMs >= 0) "${latencyMs}ms" else "-", CYAN, Icons.Default.Bolt, Modifier.weight(1f))
            StatTile("Sisa", total - done, RED, Icons.Default.HourglassEmpty, Modifier.weight(1f))
        }

        // ─── Photo gallery (recent) ───
        if (recentPhotos.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Galeri Foto (${proj?.photoHistory?.count { it.photos.isNotEmpty() } ?: 0})",
                    style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                )
                Text("Terbaru", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recentPhotos.take(18).chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { item ->
                                Box(modifier = Modifier.weight(1f)) {
                                    PhotoThumb(item)
                                }
                            }
                            // Fill empty slots to keep 3 columns
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ─── Search + filter ───
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Cari mahasiswa", color = MUTED, fontSize = 12.sp) },
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

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChipRow("Semua", "all", filterStatus) { filterStatus = "all" }
            FilterChipRow("Menunggu", "pending", filterStatus) { filterStatus = "pending" }
            FilterChipRow("Aktif", "active", filterStatus) { filterStatus = "active" }
            FilterChipRow("Dikirim", "sent", filterStatus) { filterStatus = "sent" }
            FilterChipRow("Selesai", "done", filterStatus) { filterStatus = "done" }
        }

        // ─── Student DB table ───
        Text(
            "Database Mahasiswa (${filteredDb.size})",
            style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        )

        if (filteredDb.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "Tidak ada mahasiswa",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = MUTED, fontSize = 13.sp)
                )
            }
        } else {
            // Header row
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("NIM", modifier = Modifier.weight(0.9f), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                    Text("Nama", modifier = Modifier.weight(1.6f), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Text("Status", modifier = Modifier.weight(1f), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
            }
            filteredDb.take(150).forEach { student ->
                StudentDbRow(student) {
                    val ch = getActiveChannel(student.status) ?: myChannel
                    viewModel.resetStudent(student.id, ch)
                }
            }
            if (filteredDb.size > 150) {
                Text(
                    "… dan ${filteredDb.size - 150} lainnya (persempit pencarian)",
                    style = TextStyle(color = MUTED, fontSize = 11.sp),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ─── Photo Thumbnail ─────────────────────────────────────────
@Composable
private fun PhotoThumb(item: PhotoHistoryItem) {
    val firstPhoto = item.photos.firstOrNull() ?: return
    val bitmap = remember(firstPhoto) {
        try {
            val pure = if (firstPhoto.contains(",")) firstPhoto.substringAfter(",") else firstPhoto
            val bytes = Base64.decode(pure, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
    Card(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = CARD),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Foto ${item.student.nama}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PANEL),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MUTED, modifier = Modifier.size(20.dp))
                }
            }
            // Name label overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    item.student.nama.take(14),
                    style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
            }
        }
    }
}

// ─── Student DB Row ──────────────────────────────────────────
@Composable
private fun StudentDbRow(student: Student, onReset: () -> Unit) {
    val statusColor = when {
        student.status == "pending" -> MUTED
        isActiveStatus(student.status) -> AMBER
        student.status == "sent" -> CYAN
        student.status == "done" -> GREEN
        else -> MUTED
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReset),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                student.nim.ifBlank { "-" },
                modifier = Modifier.weight(0.9f),
                style = TextStyle(color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            )
            Text(
                student.nama.ifBlank { "(tanpa nama)" },
                modifier = Modifier.weight(1.6f),
                style = TextStyle(color = Color.White, fontSize = 12.sp),
                maxLines = 1
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor)
                )
                Text(
                    statusLabel(student.status),
                    style = TextStyle(color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

// ─── Stat Tile ───────────────────────────────────────────────
@Composable
private fun StatTile(label: String, value: Any, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
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
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(
                value.toString(),
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            )
            Text(label, style = TextStyle(color = MUTED, fontSize = 10.sp))
        }
    }
}

// ─── Filter Chip ─────────────────────────────────────────────
@Composable
private fun FilterChipRow(label: String, key: String, current: String, onClick: () -> Unit) {
    val isSelected = current == key
    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) GOLD else BORDER,
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = TextStyle(
                color = if (isSelected) GOLD else MUTED,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 11.sp
            )
        )
    }
}
