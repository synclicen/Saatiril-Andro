package com.saatiril.andro.ui.admin

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.saatiril.andro.data.Project
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import com.saatiril.andro.data.statusLabel
import com.saatiril.andro.server.ClientInfo
import kotlinx.coroutines.delay

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
    var exportStatus by remember { mutableStateOf<String?>(null) }

    // Ceremony Mode (VPN internet blocker)
    val context = androidx.compose.ui.platform.LocalContext.current
    val ceremonyActive by com.saatiril.andro.vpn.CeremonyModeManager.isActive.observeAsState(false)
    val vpnPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.saatiril.andro.vpn.CeremonyModeManager.onPermissionResult(context, result.resultCode)
    }

    // Google Drive backup folder picker (SAF)
    val driveFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.driveBackupManager.setBackupFolder(uri)
        }
    }
    // Refresh upload stats periodically
    var driveStats by remember { mutableStateOf<com.saatiril.andro.backup.UploadStats?>(null) }
    var driveConnected by remember { mutableStateOf(viewModel.driveBackupManager.hasBackupFolder()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            driveStats = viewModel.driveBackupManager.getStats()
            driveConnected = viewModel.driveBackupManager.hasBackupFolder()
            kotlinx.coroutines.delay(3000)
        }
    }

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

        // ─── Ceremony Mode (VPN internet blocker) ───
        Card(
            colors = CardDefaults.cardColors(containerColor = if (ceremonyActive) GREEN.copy(alpha = 0.1f) else PANEL),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (ceremonyActive) GREEN.copy(alpha = 0.5f) else BORDER
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        if (ceremonyActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        tint = if (ceremonyActive) GREEN else GOLD,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Mode Prosesi", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                        Text(
                            if (ceremonyActive) "AKTIF — internet diblokir" else "Nonaktif — internet normal",
                            style = TextStyle(color = if (ceremonyActive) GREEN else MUTED, fontSize = 10.sp)
                        )
                    }
                    // Toggle switch
                    Switch(
                        checked = ceremonyActive,
                        onCheckedChange = { enable ->
                            if (enable) {
                                com.saatiril.andro.vpn.CeremonyModeManager.enable(context, vpnPermissionLauncher)
                            } else {
                                com.saatiril.andro.vpn.CeremonyModeManager.disable(context)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GREEN,
                            checkedTrackColor = GREEN.copy(alpha = 0.3f),
                            uncheckedThumbColor = MUTED,
                            uncheckedTrackColor = BORDER
                        )
                    )
                }
                // Explanation
                Text(
                    if (ceremonyActive) {
                        "🛡️ VPN aktif: WhatsApp, Instagram, Play Store, dan update OS diblokir di HP ini. " +
                        "Server Saatiril + Google Drive tetap berfungsi.\n\n" +
                        "⚠️ Petugas lain (MC/Operator) perlu aktifkan Mode Prosesi di HP masing-masing."
                    } else {
                        "Aktifkan untuk memblokir internet di HP ini selama prosesi — " +
                        "mencegah notifikasi WhatsApp, Instagram, dan update yang mengganggu. " +
                        "Server LAN tetap berjalan normal."
                    },
                    style = TextStyle(color = MUTED, fontSize = 9.sp)
                )
            }
        }

        // ─── Google Drive Backup ───
        Card(
            colors = CardDefaults.cardColors(containerColor = PANEL),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (driveConnected) CYAN.copy(alpha = 0.4f) else BORDER
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = if (driveConnected) CYAN else GOLD,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Google Drive Backup", style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                        Text(
                            if (driveConnected) "Terhubung — upload otomatis aktif" else "Belum diatur — foto hanya tersimpan lokal",
                            style = TextStyle(color = if (driveConnected) CYAN else MUTED, fontSize = 10.sp)
                        )
                    }
                }

                if (driveConnected && driveStats != null) {
                    val stats = driveStats!!
                    val uploaded = stats.totalUploaded
                    val pending = stats.pending + stats.uploading
                    val failed = stats.failed
                    val totalAll = uploaded + pending + failed
                    val progress = if (totalAll > 0) uploaded.toFloat() / totalAll else 0f

                    // Progress bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Upload progress", style = TextStyle(color = MUTED, fontSize = 10.sp))
                            Text("$uploaded / $totalAll", style = TextStyle(color = CYAN, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = CYAN, trackColor = BORDER
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Antri: ${stats.pending}", style = TextStyle(color = MUTED, fontSize = 9.sp))
                            if (stats.uploading > 0) Text("Upload: ${stats.uploading}", style = TextStyle(color = GOLD, fontSize = 9.sp))
                            if (failed > 0) Text("Gagal: $failed", style = TextStyle(color = RED, fontSize = 9.sp))
                            Text("✓ $uploaded", style = TextStyle(color = GREEN, fontSize = 9.sp))
                        }
                    }

                    if (failed > 0) {
                        OutlinedButton(
                            onClick = { viewModel.driveBackupManager.retryAllFailed() },
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AMBER),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry $failed Gagal", fontSize = 10.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.driveBackupManager.clearBackupFolder(); driveConnected = false },
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RED),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Putuskan Backup", fontSize = 10.sp)
                    }
                } else {
                    // Not connected — show "Pick folder" button + explanation
                    Button(
                        onClick = { driveFolderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CYAN),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = BG)
                        Spacer(Modifier.width(6.dp))
                        Text("Pilih Folder Google Drive", color = BG, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Foto tetap disimpan ke folder lokal HP, lalu di-upload otomatis ke Google Drive di background. " +
                        "Tidak butuh internet saat prosesi — upload antri dan dikirim saat internet tersedia.",
                        style = TextStyle(color = MUTED, fontSize = 9.sp)
                    )
                }
            }
        }

        // ─── Server info + per-role QR codes ───
        Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
                    Text("Server aktif", style = TextStyle(color = GREEN, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                    Spacer(Modifier.weight(1f))
                    Text("IP: ${lanIp ?: "-"}:${port}", style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                }
                Text("${stats.connectedClients} klien • ${stats.totalMessagesRelayed} pesan • uptime ${stats.uptimeMs / 1000}s",
                    style = TextStyle(color = MUTED, fontSize = 10.sp))

                // Per-role QR codes — mode-aware (matches Electron admin-dashboard.tsx:1008-1500)
                // single: MC Ch.1 + Op Ch.1 (2 QRs)
                // dual: MC Ch.1 + MC Ch.2 + Op Ch.1 + Op Ch.2 (4 QRs)
                // single-photoshoot: MC Ch.1 + Op Ch.1 (2 QRs)
                // dual-photoshoot: MC Ch.1 + Op Ch.1 + Op Ch.2 (3 QRs — 1 MC, 2 operators)
                Text("Scan QR untuk gabung:", style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                val baseUrl = "http://${lanIp ?: "0.0.0.0"}:$port"
                val mode = proj?.config?.mode ?: com.saatiril.andro.data.CameraModes.SINGLE
                val isDual = com.saatiril.andro.data.CameraModes.isDualMode(mode)
                val isPhotoshoot = com.saatiril.andro.data.CameraModes.isPhotoshootMode(mode)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isDual && !isPhotoshoot) {
                        // dual: 4 QRs — MC1, MC2, Op1, Op2
                        QrCodeWithLabel(url = "$baseUrl/?role=mc&channel=1", label = "MC Ch.1", modifier = Modifier.weight(1f))
                        QrCodeWithLabel(url = "$baseUrl/?role=mc&channel=2", label = "MC Ch.2", modifier = Modifier.weight(1f))
                        QrCodeWithLabel(url = "$baseUrl/?role=operator&channel=1", label = "Op Ch.1", modifier = Modifier.weight(1f))
                        QrCodeWithLabel(url = "$baseUrl/?role=operator&channel=2", label = "Op Ch.2", modifier = Modifier.weight(1f))
                    } else if (isDual && isPhotoshoot) {
                        // dual-photoshoot: 3 QRs — MC1, Op1, Op2
                        QrCodeWithLabel(url = "$baseUrl/?role=mc&channel=1", label = "MC Ch.1", modifier = Modifier.weight(1f))
                        QrCodeWithLabel(url = "$baseUrl/?role=operator&channel=1", label = "Op Ch.1", modifier = Modifier.weight(1f))
                        QrCodeWithLabel(url = "$baseUrl/?role=operator&channel=2", label = "Op Ch.2", modifier = Modifier.weight(1f))
                    } else {
                        // single / single-photoshoot: 2 QRs — MC1, Op1
                        QrCodeWithLabel(url = "$baseUrl/?role=mc&channel=1", label = "MC Ch.1", modifier = Modifier.weight(1f))
                        QrCodeWithLabel(url = "$baseUrl/?role=operator&channel=1", label = "Op Ch.1", modifier = Modifier.weight(1f))
                    }
                }

                // Session password display (if set)
                val pwd = proj?.config?.sessionPassword
                if (pwd != null && pwd != "__PASSWORD_SET__" && pwd.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = AMBER, modifier = Modifier.size(14.dp))
                        Text("Password sesi: ", style = TextStyle(color = MUTED, fontSize = 11.sp))
                        Text(pwd, style = TextStyle(color = AMBER, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }

        // ─── Export button ───
        Button(
            onClick = {
                val fname = viewModel.exportToExcel()
                exportStatus = if (fname != null) "Tersimpan: $fname" else "Gagal export"
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CARD),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GOLD)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = GOLD)
            Spacer(Modifier.width(6.dp))
            Text("Export Database ke Excel (.xls)", color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        exportStatus?.let {
            Text(it, style = TextStyle(color = if (it.startsWith("Tersimpan")) GREEN else RED, fontSize = 10.sp))
        }

        // ─── LAN distribution instructions (Fix #21) ───
        Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = CYAN, modifier = Modifier.size(16.dp))
                    Text("Cara Distribusi LAN", style = TextStyle(color = GOLD, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                val steps = listOf(
                    "Operator: Download APK Saatiril Andro dari GitHub Releases",
                    "Hubungkan kamera USB/HDMI capture card via OTG",
                    "Pastikan HP dan server di WiFi/LAN yang sama",
                    "Scan QR Code di atas atau ketik IP server: http://IP:3003",
                    "Pilih role (Operator/MC) dan channel (1/2)"
                )
                steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}.", style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                        Text(step, style = TextStyle(color = MUTED, fontSize = 11.sp))
                    }
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
            StatTile("Uptime", "${stats.uptimeMs / 1000}s", CYAN, Icons.Default.Bolt, Modifier.weight(1f))
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
                            rowItems.forEach { item ->
                                Box(Modifier.weight(1f)) {
                                    PhotoThumb(
                                        item = item,
                                        project = proj,
                                        onReset = { viewModel.resetStudent(item.student.id, item.channel) }
                                    )
                                }
                            }
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

// ─── QR Code with Label (per-role) + Salin button (Fix #25) ───
@Composable
private fun QrCodeWithLabel(url: String, label: String, modifier: Modifier = Modifier) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Auto-clear the "Tersalin!" confirmation 2s after copy (Fix #25)
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        QrCodeBox(text = url, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = TextStyle(color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        // Salin (Copy) URL button — copies URL to clipboard, shows "Tersalin!" for 2s
        Text(
            text = if (copied) "Tersalin!" else "Salin",
            style = TextStyle(
                color = if (copied) GREEN else MUTED,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .clickable {
                    clipboardManager.setText(AnnotatedString(url))
                    copied = true
                }
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/**
 * Photo thumbnail with version-aware filename overlay (Fix #19) and
 * a per-thumbnail retake button (Fix #20).
 */
@Composable
private fun PhotoThumb(item: PhotoHistoryItem, project: Project?, onReset: () -> Unit) {
    val firstPhoto = item.photos.firstOrNull() ?: return
    val bitmap = remember(firstPhoto) {
        try {
            val pure = if (firstPhoto.contains(",")) firstPhoto.substringAfter(",") else firstPhoto
            val bytes = Base64.decode(pure, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }
    // Version-aware filename — retakes produce _v2, _v3, ... instead of overwriting (Fix #19)
    val version = project?.captureVersions?.get("${item.student.id}_${item.channel}") ?: 1
    val filename = if (version > 1)
        "${item.student.nim}_${item.student.nama.take(10)}_v${version}.jpg"
    else
        "${item.student.nim}_${item.student.nama.take(10)}.jpg"
    Card(modifier = Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(8.dp)) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Foto ${item.student.nama}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(PANEL), contentAlignment = Alignment.Center) { Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MUTED, modifier = Modifier.size(20.dp)) }
            // Retake button: top-right refresh icon, calls viewModel.resetStudent (Fix #20)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onReset),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Retake", tint = Color.White, modifier = Modifier.size(14.dp))
            }
            // Bottom overlay: student name + version-aware filename (Fix #19)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(item.student.nama.take(14), style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(filename, style = TextStyle(color = MUTED, fontSize = 7.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
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
