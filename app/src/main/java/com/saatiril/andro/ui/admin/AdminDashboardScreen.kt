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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.PhotoHistoryItem
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import com.saatiril.andro.data.statusLabel
import com.saatiril.andro.server.ClientInfo

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
 * Admin Dashboard — the live operations panel shown while the server runs.
 * Mirrors the Electron `admin-dashboard.tsx`.
 */
@Composable
fun AdminDashboardScreen(viewModel: AdminViewModel) {
    val project by viewModel.project.collectAsState()
    val clients by viewModel.serverClients.collectAsState()
    val stats by viewModel.serverStats.collectAsState()
    val lanIp by viewModel.lanIp.collectAsState()
    val port by viewModel.serverPort.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("all") }

    val proj = project
    val db = proj?.database ?: emptyList()
    val total = db.size
    val done = db.count { it.status == "done" }
    val sent = db.count { it.status == "sent" }
    val active = db.count { isActiveStatus(it.status) }
    val pending = db.count { it.status == "pending" }
    val progress = if (total > 0) done * 100 / total else 0

    val filteredDb = db.filter {
        val q = searchQuery.trim()
        val matchesQuery = q.isEmpty() || it.nama.contains(q, ignoreCase = true) || it.nim.contains(q, ignoreCase = true)
        val matchesStatus = when (filterStatus) {
            "pending" -> it.status == "pending"
            "active" -> isActiveStatus(it.status)
            "sent" -> it.status == "sent"
            "done" -> it.status == "done"
            else -> true
        }
        matchesQuery && matchesStatus
    }
    val recentPhotos = proj?.photoHistory?.filter { it.photos.isNotEmpty() }?.takeLast(18) ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ─── Project header + progress ───
        if (proj != null) {
            Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Dashboard, contentDescription = null, tint = GOLD, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(proj.name.ifBlank { "Proyek Wisuda" }, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp), maxLines = 1)
                            Text("Mode ${proj.config.mode} • Rasio ${proj.config.ratio}", style = TextStyle(color = MUTED, fontSize = 11.sp))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Progress", style = TextStyle(color = MUTED, fontSize = 11.sp))
                            Text("$done / $total ($progress%)", style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                        }
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = GREEN, trackColor = BORDER
                        )
                    }
                }
            }
        }

        // ─── Server info + QR ───
        Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCodeBox(text = "http://${lanIp ?: "0.0.0.0"}:$port", modifier = Modifier.size(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
                        Text("Server aktif", style = TextStyle(color = GREEN, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                    }
                    Text("IP: ${lanIp ?: "-"}:${port}", style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                    Text("${stats.connectedClients} klien • ${stats.totalMessagesRelayed} pesan", style = TextStyle(color = MUTED, fontSize = 11.sp))
                    Text("Scan QR untuk gabung (MC/operator)", style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 10.sp))
                }
            }
        }

        // ─── Stats grid ───
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Total", total, Color.White, Icons.Default.Group, Modifier.weight(1f))
            StatTile("Aktif", active, AMBER, Icons.Default.PhotoCamera, Modifier.weight(1f))
            StatTile("Selesai", done, GREEN, Icons.Default.CheckCircle, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Menunggu", pending, MUTED, Icons.Default.Schedule, Modifier.weight(1f))
            StatTile("Latensi", "${stats.uptimeMs / 1000}s", CYAN, Icons.Default.Bolt, Modifier.weight(1f))
            StatTile("Sisa", total - done, RED, Icons.Default.HourglassEmpty, Modifier.weight(1f))
        }

        // ─── Connected clients ───
        if (clients.isNotEmpty()) {
            Text("Klien Terhubung (${clients.size})", style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp))
            clients.forEach { c -> ClientRow(c) }
        }

        // ─── Photo gallery ───
        if (recentPhotos.isNotEmpty()) {
            Text("Galeri Foto (${proj?.photoHistory?.count { it.photos.isNotEmpty() } ?: 0})", style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
                Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentPhotos.chunked(3).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { item -> Box(Modifier.weight(1f)) { PhotoThumb(item) } }
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // ─── Search + filter ───
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            label = { Text("Cari mahasiswa", color = MUTED, fontSize = 12.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD,
                focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
            ),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(18.dp)) },
            trailingIcon = { if (searchQuery.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = "Hapus", tint = MUTED, modifier = Modifier.size(18.dp).clickable { searchQuery = "" }) },
            textStyle = TextStyle(fontSize = 14.sp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChipRow("Semua", "all", filterStatus) { filterStatus = "all" }
            FilterChipRow("Menunggu", "pending", filterStatus) { filterStatus = "pending" }
            FilterChipRow("Aktif", "active", filterStatus) { filterStatus = "active" }
            FilterChipRow("Dikirim", "sent", filterStatus) { filterStatus = "sent" }
            FilterChipRow("Selesai", "done", filterStatus) { filterStatus = "done" }
        }

        // ─── Student DB ───
        Text("Database Mahasiswa (${filteredDb.size})", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
        Card(colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.5f)), shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NIM", Modifier.weight(0.9f), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                Text("Nama", Modifier.weight(1.6f), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Text("Status", Modifier.weight(1f), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
        }
        filteredDb.take(150).forEach { s -> StudentDbRow(s) { val ch = getActiveChannel(s.status) ?: 1; viewModel.resetStudent(s.id, ch) } }
        if (filteredDb.size > 150) Text("… dan ${filteredDb.size - 150} lainnya", style = TextStyle(color = MUTED, fontSize = 11.sp), modifier = Modifier.padding(8.dp))

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QrCodeBox(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 256, 256)
            val w = bitMatrix.width; val h = bitMatrix.height
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until w) for (y in 0 until h) bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            bmp
        } catch (e: Exception) { null }
    }
    Card(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.White),
        shape = RoundedCornerShape(8.dp)) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR $text", modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.QrCode, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun PhotoThumb(item: PhotoHistoryItem) {
    val firstPhoto = item.photos.firstOrNull() ?: return
    val bitmap = remember(firstPhoto) {
        try {
            val pure = if (firstPhoto.contains(",")) firstPhoto.substringAfter(",") else firstPhoto
            val bytes = Base64.decode(pure, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }
    Card(modifier = Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(8.dp)) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Foto ${item.student.nama}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(PANEL), contentAlignment = Alignment.Center) { Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MUTED, modifier = Modifier.size(20.dp)) }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(item.student.nama.take(14), style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ClientRow(c: ClientInfo) {
    val roleColor = when (c.role) { "operator" -> CYAN; "mc" -> AMBER; "admin" -> GOLD; else -> MUTED }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Person, contentDescription = null, tint = roleColor, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(c.role.replaceFirstChar { it.uppercase() } + " • Ch.${c.channel}", style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                Text("${c.transport} • ${c.sid.take(8)}…", style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
            }
            if (c.authenticated) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GREEN, modifier = Modifier.size(14.dp))
            else Icon(Icons.Default.Pending, contentDescription = null, tint = AMBER, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun StudentDbRow(student: Student, onReset: () -> Unit) {
    val sc = when {
        student.status == "pending" -> MUTED
        isActiveStatus(student.status) -> AMBER
        student.status == "sent" -> CYAN
        student.status == "done" -> GREEN
        else -> MUTED
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onReset),
        colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(student.nim.ifBlank { "-" }, Modifier.weight(0.9f), style = TextStyle(color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
            Text(student.nama.ifBlank { "(tanpa nama)" }, Modifier.weight(1.6f), style = TextStyle(color = Color.White, fontSize = 12.sp), maxLines = 1)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(sc))
                Text(statusLabel(student.status), style = TextStyle(color = sc, fontSize = 10.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Any, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER.copy(alpha = 0.5f))) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(value.toString(), style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp))
            Text(label, style = TextStyle(color = MUTED, fontSize = 10.sp))
        }
    }
}

@Composable
private fun FilterChipRow(label: String, key: String, current: String, onClick: () -> Unit) {
    val sel = current == key
    Card(modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .border(1.dp, if (sel) GOLD else BORDER, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = if (sel) CARD else PANEL), shape = RoundedCornerShape(16.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = TextStyle(color = if (sel) GOLD else MUTED, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp))
    }
}
