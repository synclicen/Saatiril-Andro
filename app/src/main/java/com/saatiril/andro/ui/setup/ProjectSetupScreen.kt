package com.saatiril.andro.ui.setup

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val AMBER = Color(0xFFfbbf24)

/**
 * Project Setup — configure a new or existing project.
 * Fields: name, camera mode, aspect ratio, filter preset, Excel student import,
 * output folder (SAF), session password. The "Start" button saves the project,
 * starts the Socket.io server, and moves to MAIN.
 * Mirrors the Electron `project-setup.tsx`.
 */
@Composable
fun ProjectSetupScreen(viewModel: AdminViewModel) {
    val name by viewModel.setupName.collectAsState()
    val mode by viewModel.setupMode.collectAsState()
    val ratio by viewModel.setupRatio.collectAsState()
    val preset by viewModel.setupPreset.collectAsState()
    val password by viewModel.setupPassword.collectAsState()
    val students by viewModel.setupStudents.collectAsState()
    val ch0Students by viewModel.setupChannel0Students.collectAsState()
    val ch0FileName by viewModel.setupChannel0FileName.collectAsState()
    val ch1Students by viewModel.setupChannel1Students.collectAsState()
    val ch1FileName by viewModel.setupChannel1FileName.collectAsState()
    val importingChannel by viewModel.importingChannel.collectAsState()
    val folderUri by viewModel.setupOutputFolderUri.collectAsState()
    val frameData by viewModel.setupFrame.collectAsState()
    val frameFileName by viewModel.setupFrameFileName.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()
    val startupError by viewModel.startupError.collectAsState()
    val starting by viewModel.starting.collectAsState()
    var parsingFrame by remember { mutableStateOf(false) }

    // Excel file picker — for channel 0 (single/photoshoot) or channel 1 (dual)
    val excelLauncherCh0 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importExcelChannel(it, 0) } }

    val excelLauncherCh1 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importExcelChannel(it, 1) } }

    // Folder picker (SAF tree)
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.pickOutputFolder(it) } }

    val isDual = mode == CameraModes.DUAL
    val canStart = students.isNotEmpty() && folderUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .statusBarsPadding()
    ) {
        // ─── Header (fixed at top, not scrolling) ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(onClick = { viewModel.openHub() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = GOLD)
            }
            Column(Modifier.weight(1f)) {
                Text("Setup Proyek", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White))
                Text("Konfigurasi wisuda dan mulai server", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
        }

        // ─── Scrollable form content (takes remaining space) ───
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Project name
            SectionCard(title = "Nama Proyek", icon = Icons.Default.Edit) {
                OutlinedTextField(
                    value = name, onValueChange = { viewModel.setSetupName(it) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = tfColors(), textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    placeholder = { Text("Wisuda 2026", color = MUTED.copy(alpha = 0.5f), fontSize = 14.sp) }
                )
            }

            // Camera mode
            SectionCard(title = "Mode Kamera", icon = Icons.Default.CameraAlt) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip("Single", CameraModes.SINGLE, mode) { viewModel.setSetupMode(CameraModes.SINGLE) }
                    ModeChip("Dual", CameraModes.DUAL, mode) { viewModel.setSetupMode(CameraModes.DUAL) }
                    ModeChip("Single PS", CameraModes.SINGLE_PHOTOSHOOT, mode) { viewModel.setSetupMode(CameraModes.SINGLE_PHOTOSHOOT) }
                    ModeChip("Dual PS", CameraModes.DUAL_PHOTOSHOOT, mode) { viewModel.setSetupMode(CameraModes.DUAL_PHOTOSHOOT) }
                }
                Text("Photoshoot = 1 foto/mahasiswa. Standar = Toga + Ijazah.", style = TextStyle(color = MUTED, fontSize = 10.sp))
            }

            // Ratio + Preset
            SectionCard(title = "Rasio & Preset", icon = Icons.Default.AspectRatio) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("4:3", "3:4", "16:9", "1:1").forEach { r ->
                        ModeChip(r, r, ratio) { viewModel.setSetupRatio(r) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 9 preset filters with labels + descriptions (matches Electron PRESET_OPTIONS)
                Text("Filter Preset:", style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PRESET_OPTIONS.forEach { (value, label, desc) ->
                        PresetRow(value, label, desc, preset == value) { viewModel.setSetupPreset(value) }
                    }
                }
            }

            // Excel import — dual mode shows 2 upload zones (JALUR KIRI + KANAN)
            SectionCard(title = "Upload Data Peserta", icon = Icons.Default.TableChart) {
                // Mode badges (JALUR KIRI/KANAN or Photoshoot)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (mode) {
                        CameraModes.SINGLE -> {
                            Badge(text = "Single Channel — Wisuda", color = GOLD)
                        }
                        CameraModes.DUAL -> {
                            Badge(text = "JALUR KIRI", color = GOLD)
                            Badge(text = "JALUR KANAN", color = CYAN)
                        }
                        CameraModes.SINGLE_PHOTOSHOOT -> {
                            Badge(text = "Photoshoot — 1 Foto/Peserta", color = GREEN)
                        }
                        CameraModes.DUAL_PHOTOSHOOT -> {
                            Badge(text = "Photoshoot — 1 Foto/Peserta", color = GREEN)
                            Badge(text = "2 Kamera", color = CYAN)
                        }
                    }
                }

                if (isDual) {
                    // ── DUAL: 2 upload zones (kiri + kanan) ──
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        UploadZone(
                            label = "Data JALUR KIRI (Channel 1)",
                            accentColor = GOLD,
                            students = ch0Students,
                            fileName = ch0FileName,
                            isLoading = importingChannel == 0,
                            onPick = { excelLauncherCh0.launch(excelMimeTypes()) },
                            onClear = { viewModel.clearChannelExcel(0) }
                        )
                        UploadZone(
                            label = "Data JALUR KANAN (Channel 2)",
                            accentColor = CYAN,
                            students = ch1Students,
                            fileName = ch1FileName,
                            isLoading = importingChannel == 1,
                            onPick = { excelLauncherCh1.launch(excelMimeTypes()) },
                            onClear = { viewModel.clearChannelExcel(1) }
                        )
                    }
                } else {
                    // ── SINGLE / PHOTOSHOOT: 1 upload zone ──
                    UploadZone(
                        label = if (CameraModes.isPhotoshootMode(mode)) "Data Peserta Photoshoot" else "Data Peserta",
                        accentColor = if (CameraModes.isPhotoshootMode(mode)) GREEN else GOLD,
                        students = ch0Students,
                        fileName = ch0FileName,
                        isLoading = importingChannel == 0,
                        onPick = { excelLauncherCh0.launch(excelMimeTypes()) },
                        onClear = { viewModel.clearChannelExcel(0) }
                    )
                }

                Text(
                    "Kolom harus mengandung kata \"nim\"/\"nis\"/\"id\" untuk NIM dan \"nama\"/\"name\" untuk Nama.",
                    style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 10.sp)
                )
                if (CameraModes.isPhotoshootMode(mode)) {
                    Text(
                        "Mode Photoshoot: hanya 1 foto per peserta, urutan bebas.",
                        style = TextStyle(color = GREEN.copy(alpha = 0.7f), fontSize = 10.sp)
                    )
                }
                Text(
                    "Total: ${students.size} mahasiswa",
                    style = TextStyle(color = if (students.isEmpty()) MUTED else GREEN, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )
            }

            // Output folder
            SectionCard(title = "Folder Output Foto", icon = Icons.Default.FolderOpen) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { folderLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = CARD),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp), tint = GOLD)
                        Spacer(Modifier.width(6.dp))
                        Text("Pilih Folder", color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (folderUri != null) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GREEN, modifier = Modifier.size(18.dp))
                        Text("Folder dipilih", style = TextStyle(color = GREEN, fontSize = 11.sp))
                    }
                }
                Text("Foto dari operator akan otomatis tersimpan ke folder ini.", style = TextStyle(color = MUTED, fontSize = 10.sp))
            }

            // ─── Frame overlay (PNG transparan) — matches Electron setup ───
            SectionCard(title = "Frame Overlay (PNG Transparan)", icon = Icons.Default.Crop) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Frame file picker (PNG only)
                val frameLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { u ->
                        parsingFrame = true
                        // Read PNG as base64 data URL (off main thread)
                        Thread {
                            try {
                                val inputStream = ctx.contentResolver.openInputStream(u)
                                val bytes = inputStream?.readBytes() ?: ByteArray(0)
                                inputStream?.close()
                                val fileName = u.lastPathSegment?.substringAfterLast('/') ?: "frame.png"
                                val dataUrl = "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                viewModel.setSetupFrame(dataUrl, fileName)
                            } catch (e: Exception) {
                                Log.e("Setup", "Frame read failed: ${e.message}", e)
                            } finally {
                                parsingFrame = false
                            }
                        }.start()
                    }
                }

                if (parsingFrame) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GOLD, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Memuat frame...", style = TextStyle(color = MUTED, fontSize = 12.sp))
                    }
                } else if (frameData != null) {
                    // Frame loaded — show preview + remove button
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Preview thumbnail
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, BORDER, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Image, contentDescription = "Frame", tint = GOLD, modifier = Modifier.size(24.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(frameFileName.ifBlank { "frame.png" }, style = TextStyle(color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Text("Frame aktif — akan di-overlay pada setiap foto.", style = TextStyle(color = MUTED, fontSize = 10.sp))
                        }
                        IconButton(onClick = { viewModel.setSetupFrame(null, "") }) {
                            Icon(Icons.Default.Close, contentDescription = "Hapus Frame", tint = RED, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    // No frame — show upload button
                    Button(
                        onClick = { frameLauncher.launch(arrayOf("image/png")) },
                        colors = ButtonDefaults.buttonColors(containerColor = CARD),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp), tint = GOLD)
                        Spacer(Modifier.width(6.dp))
                        Text("Upload Frame Overlay PNG", color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Frame PNG transparan akan di-overlay pada foto hasil tangkapan (opsional).", style = TextStyle(color = MUTED, fontSize = 10.sp))
                }
            }

            // Session password (optional)
            SectionCard(title = "Password Sesi (opsional)", icon = Icons.Default.Lock) {
                OutlinedTextField(
                    value = password ?: "", onValueChange = { viewModel.setSetupPassword(it) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = tfColors(), textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    placeholder = { Text("Kosongkan jika tidak ada", color = MUTED.copy(alpha = 0.5f), fontSize = 13.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = { Icon(Icons.Default.Password, contentDescription = null, tint = MUTED, modifier = Modifier.size(18.dp)) }
                )
                Text("MC/operator harus memasukkan password ini untuk bergabung.", style = TextStyle(color = MUTED, fontSize = 10.sp))
            }

            Spacer(Modifier.height(8.dp))
        }

        // ─── Sticky bottom bar (always visible — Start button + error) ───
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL.copy(alpha = 0.3f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding()
        ) {
            // Startup error (shown if the server fails to start)
            startupError?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = RED, modifier = Modifier.size(18.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Server gagal dimulai", style = TextStyle(color = RED, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                            Text(err, style = TextStyle(color = RED.copy(alpha = 0.85f), fontSize = 10.sp))
                        }
                    }
                }
            }

            // Help text if not ready
            if (!canStart && !starting) {
                Text(
                    if (students.isEmpty()) "⚠ Impor database mahasiswa dulu." else "⚠ Pilih folder output dulu.",
                    style = TextStyle(color = AMBER, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // Start button — ALWAYS VISIBLE at the bottom
            Button(
                onClick = { viewModel.createAndStartProject() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (canStart && !starting) GOLD else BORDER),
                shape = RoundedCornerShape(14.dp),
                enabled = canStart && !starting
            ) {
                if (starting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BG, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Memulai server...", color = BG, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp), tint = BG)
                    Spacer(Modifier.width(8.dp))
                    Text("Mulai Server", color = BG, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = GOLD, modifier = Modifier.size(16.dp))
                Text(title, style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp))
            }
            content()
        }
    }
}

@Composable
private fun ModeChip(label: String, value: String, current: String, onClick: () -> Unit) {
    val selected = current == value
    Card(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .border(1.dp, if (selected) GOLD else BORDER, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = if (selected) CARD else PANEL),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = TextStyle(color = if (selected) GOLD else MUTED, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp))
    }
}

@Composable
private fun tfColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD,
    focusedContainerColor = CARD, unfocusedContainerColor = CARD
)

// ─── 9 Filter Presets (matches Electron PRESET_OPTIONS) ─────────────────
private val PRESET_OPTIONS: List<Triple<String, String, String>> = listOf(
    Triple("original", "Original Sensor", "Tanpa Filter"),
    Triple("studio", "Studio Bright", "Cahaya Studio Hangat"),
    Triple("cinematic", "Cinematic Gold", "Tone Sinematik Emas"),
    Triple("pro", "Preset Pro", "High Contrast + Sharpening"),
    Triple("vivid", "Vivid", "Warna Cerah & Kontras Tinggi"),
    Triple("softPortrait", "Soft Portrait", "Kulit Lembut & Hangat"),
    Triple("classicFilm", "Classic Film", "Nuansa Film Vintage"),
    Triple("dramaticBW", "Dramatic B&W", "Hitam Putih Dramatis"),
    Triple("warmSunset", "Warm Sunset", "Tone Emas Sore Hari")
)

@Composable
private fun PresetRow(value: String, label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .border(1.dp, if (selected) GOLD else BORDER, RoundedCornerShape(8.dp))
            .background(if (selected) CARD else PANEL)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp))
        else Box(Modifier.size(14.dp))
        Column {
            Text(label, style = TextStyle(color = if (selected) GOLD else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            Text(desc, style = TextStyle(color = MUTED.copy(alpha = 0.7f), fontSize = 9.sp))
        }
    }
}

// ─── Mode Badge ──────────────────────────────────────────────────────────
@Composable
private fun Badge(text: String, color: Color) {
    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = TextStyle(color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        )
    }
}

// ─── Excel Upload Zone (matches Electron renderUploadZone) ───────────────
@Composable
private fun UploadZone(
    label: String,
    accentColor: Color,
    students: List<com.saatiril.andro.data.Student>,
    fileName: String,
    isLoading: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (students.isNotEmpty()) accentColor.copy(alpha = 0.5f) else BORDER, RoundedCornerShape(10.dp))
            .background(if (students.isNotEmpty()) accentColor.copy(alpha = 0.05f) else PANEL.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = TextStyle(color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = accentColor, strokeWidth = 2.dp)
                Text("Membaca file...", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
        } else if (students.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TableChart, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Data siap: ${students.size} Peserta", style = TextStyle(color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    Text(fileName, style = TextStyle(color = MUTED, fontSize = 10.sp), maxLines = 1)
                }
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Hapus", tint = RED, modifier = Modifier.size(14.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.UploadFile, contentDescription = null, tint = MUTED.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                Text("Tap untuk pilih file Excel", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
        }
        Button(
            onClick = onPick,
            colors = ButtonDefaults.buttonColors(containerColor = CARD),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            enabled = !isLoading
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp), tint = accentColor)
            Spacer(Modifier.width(4.dp))
            Text("Pilih File Excel/CSV", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(".xlsx / .xls / .csv", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 9.sp))
    }
}

private fun excelMimeTypes(): Array<String> = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "text/csv",
    "text/comma-separated-values"
)
