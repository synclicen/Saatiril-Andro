package com.saatiril.andro.ui.operator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.saatiril.andro.camera.CameraCapture
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.CapturePhase
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Admin Operator panel — a simplified camera panel for the admin's own phone.
 *
 * In the Electron app, the admin sees MC + Operator side-by-side. The Operator
 * panel shows the admin's webcam preview and a shutter button. On Android, this
 * uses Camera2 to show the phone's built-in camera as a BACKUP camera (the
 * primary photography is done by operators on separate phones with the
 * `android-operator` APK + DSLR + USB capture card).
 *
 * When the admin captures a photo here:
 *  1. The frame is cropped to the project's aspect ratio + filter preset applied.
 *  2. Saved to the output folder via [com.saatiril.andro.util.PhotoSaver].
 *  3. The current target student (set by MC) is marked "done".
 *  4. SYNC_DB is broadcast to all connected MC/operator clients.
 */
@Composable
fun AdminOperatorScreen(viewModel: AdminViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()

    // Camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Request camera permission on first entry
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── 3-camera support: use ViewModel's managers (UVC + Camera2) ──
    // The ViewModel exposes cameraUVCManager (USB capture cards via USB Host)
    // and camera2Manager (built-in front/back). Both are the SAME hardened
    // managers from the android-operator APK — ported verbatim.
    val textureView = remember { TextureView(context) }
    val availableCameras by viewModel.availableCameras.collectAsState()
    val activeEngine by viewModel.activeCameraEngine.collectAsState()
    val cameraSource by viewModel.cameraSource.collectAsState()
    val currentCameraId by viewModel.currentCameraId.collectAsState()
    val uvcConnected by viewModel.cameraUVCManager.isConnected.collectAsState()
    val c2Connected by viewModel.camera2Manager.isConnected.collectAsState()
    val frameBitmap by viewModel.frameBitmap.collectAsState()
    var showCameraPicker by remember { mutableStateOf(false) }

    val cameraConnected = if (activeEngine == "uvc") uvcConnected else c2Connected

    var capturedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // ── Shutter modes (matches Electron: manual / timer-3 / timer-5 / timer-10 / hand) ──
    var shutterMode by remember { mutableStateOf("manual") }
    var timerCountdown by remember { mutableStateOf<Int?>(null) }
    var timerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ── Hand trigger (matches Electron palmTriggerEnabled) ──
    var handTriggerEnabled by remember { mutableStateOf(false) }

    // ── Gridline overlay (matches Electron operator-panel.tsx:263-266) ──
    var gridlineEnabled by remember { mutableStateOf(true) }
    var gridlineType by remember { mutableStateOf("thirds") }  // thirds | quarters | crosshair | diagonal
    var gridlineThickness by remember { mutableStateOf(1) }  // 1=thin, 2=medium, 3=thick
    var gridlineColor by remember { mutableStateOf("white") }  // white | yellow | red | cyan | green

    // ── Flash overlay on capture ──
    var showFlash by remember { mutableStateOf(false) }

    // ── Capture phase (from ViewModel) ──
    val capturePhase by viewModel.capturePhase.collectAsState()
    val opCapturedPhotos by viewModel.opCapturedPhotos.collectAsState()

    // Find the current target student (the one MC called to the stage)
    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
    val isDual = CameraModes.isDualMode(mode)
    val myChannel by viewModel.myChannel.collectAsState()

    // Operator queue: in photoshoot mode, operator can pick from 'sent' students
    // (matches Electron operator-panel.tsx renderOpSearch + renderQueueList).
    // In non-photoshoot, the active student is auto-assigned by MC.
    var opSearchQuery by remember { mutableStateOf("") }
    var opSelectedStudent by remember { mutableStateOf<Student?>(null) }

    // Photoshoot: list of 'sent' students (operator's queue)
    val opSentQueue = if (isPhotoshoot) db.filter { it.status == "sent" } else emptyList()
    val opSearchResults = if (isPhotoshoot && opSearchQuery.trim().isNotEmpty()) {
        val q = opSearchQuery.trim().lowercase()
        opSentQueue.filter { it.nama.lowercase().contains(q) || it.nim.lowercase().contains(q) }
    } else opSentQueue

    // Active target:
    // - Non-photoshoot: auto from MC_CALL (active_N on our channel)
    // - Photoshoot: operator-selected, OR first 'sent' student if none selected
    val activeStudent = if (isPhotoshoot) {
        opSelectedStudent ?: opSentQueue.firstOrNull()
    } else {
        db.firstOrNull { isActiveStatus(it.status) && getActiveChannel(it.status) == myChannel }
    }
    val activeChannel = activeStudent?.let {
        if (isPhotoshoot) it.assignedChannel else (getActiveChannel(it.status) ?: myChannel)
    } ?: myChannel

    // Reset capture state when the active student changes (new student called)
    val activeStudentId = activeStudent?.id
    LaunchedEffect(activeStudentId) {
        viewModel.resetCaptureState()
    }

    // Clear operator selection when the selected student becomes 'done'
    LaunchedEffect(opSelectedStudent?.status) {
        if (opSelectedStudent?.status == "done") {
            opSelectedStudent = null
        }
    }

    // Init camera on first show (only if permission granted), release on dispose
    DisposableEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            // Bind TextureView to both managers + enumerate + open default (back camera)
            viewModel.camera2Manager.setTextureView(textureView)
            viewModel.cameraUVCManager.setTextureView(textureView)
            viewModel.camera2Manager.enumerateCameras()
            viewModel.cameraUVCManager.initCamera()
            viewModel.refreshAvailableCameras()
            viewModel.camera2Manager.openCamera()
        }
        onDispose {
            try { viewModel.camera2Manager.closeCamera() } catch (_: Exception) {}
            try { viewModel.cameraUVCManager.hidePreview() } catch (_: Exception) {}
        }
    }

    // If no camera permission, show a request screen
    if (!hasCameraPermission) {
        Column(
            modifier = Modifier.fillMaxSize().background(BG).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = GOLD, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Izin Kamera Diperlukan", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            Text(
                "Panel Operator membutuhkan akses kamera untuk pratinjau dan tangkap foto. Klik tombol di bawah untuk berikan izin.",
                style = TextStyle(color = MUTED, fontSize = 12.sp), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Berikan Izin Kamera", color = BG, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ─── Current target card ───
        activeStudent?.let { student ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GOLD.copy(alpha = 0.5f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
                        Text(student.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = GOLD), shape = RoundedCornerShape(4.dp)) {
                        Text("Ch.$activeChannel", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = TextStyle(color = BG, fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    }
                }
            }
        } ?: run {
            Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("MC belum memanggil mahasiswa. Buka tab MC untuk memanggil.", style = TextStyle(color = MUTED, fontSize = 11.sp))
                }
            }
        }

        // ─── Camera preview — aspect-ratio-locked to project ratio ───
        // The outer Box fills available space (black background for letterbox bars).
        // The inner Box is aspect-ratio-locked to the project's selected ratio.
        // TextureView + gridline + frame overlay go INSIDE the inner box.
        // Camera switch button + status overlay go in the OUTER box (always visible).
        val ratio = project?.config?.ratio ?: "4:3"
        val aspectRatio = when (ratio) {
            "4:3" -> 4f / 3f
            "3:4" -> 3f / 4f
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "2:3" -> 2f / 3f
            "4:6" -> 4f / 6f
            "1:1" -> 1f
            else -> 4f / 3f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(1.dp, BORDER, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Inner Box: aspect-ratio-locked container for camera + overlays
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { textureView },
                    modifier = Modifier.fillMaxSize()
                )

                // Gridline overlay (inside ratio box so it matches the preview)
                if (gridlineEnabled) {
                    GridlineCanvas(
                        type = gridlineType,
                        thickness = gridlineThickness,
                        colorName = gridlineColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Frame overlay on top of the preview — MATCHES ELECTRON exactly.
                // Electron: <img src={frameData} className="absolute inset-0 w-full h-full object-fill" style={{ zIndex: 5 }} />
                // The frame PNG is transparent in the center (where camera shows through)
                // and opaque at the borders (decorative frame with text/logos).
                // object-fill = ContentScale.FillBounds in Compose (stretches to fill container).
                frameBitmap?.let { fb ->
                    Image(
                        bitmap = fb.asImageBitmap(),
                        contentDescription = "Frame overlay",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }

                // Capture error overlay (inside ratio box)
                captureError?.let { err ->
                    Card(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp),
                        colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(err, Modifier.padding(4.dp), style = TextStyle(color = Color.White, fontSize = 8.sp))
                    }
                }
            } // end inner aspect-ratio Box

            // Camera status + source overlay (OUTER box — always visible, not clipped by ratio)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.dp)).background(if (cameraConnected) GREEN else RED))
                val srcLabel = when (cameraSource) { "uvc" -> "USB"; "builtin" -> "HP"; else -> "-" }
                Text(
                    "Ch.$activeChannel • $srcLabel ${if (cameraConnected) "●" else "○"}",
                    style = TextStyle(color = Color.White, fontSize = 9.sp)
                )
            }

            // Camera picker dropdown button (top-right) — 3 cameras: USB / Belakang / Depan
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(
                    onClick = { showCameraPicker = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Pilih Kamera", tint = GOLD, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showCameraPicker,
                    onDismissRequest = { showCameraPicker = false },
                    modifier = Modifier.background(PANEL)
                ) {
                    // Header
                    Text("Pilih Kamera", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = TextStyle(color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)

                    if (availableCameras.isNotEmpty()) {
                        availableCameras.forEach { (cameraId, displayName) ->
                            val isCurrentlySelected = cameraId == currentCameraId
                            val isUsb = displayName.contains("USB", ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(
                                            when {
                                                isUsb -> Icons.Default.Usb
                                                displayName.contains("Depan", ignoreCase = true) -> Icons.Default.CameraFront
                                                else -> Icons.Default.CameraAlt
                                            },
                                            contentDescription = null,
                                            tint = if (isCurrentlySelected) GREEN else MUTED,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(displayName, style = TextStyle(
                                            color = if (isCurrentlySelected) GREEN else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrentlySelected) FontWeight.Bold else FontWeight.Normal
                                        ))
                                        if (isCurrentlySelected) Text("✓", style = TextStyle(color = GREEN, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                    }
                                },
                                onClick = {
                                    viewModel.switchToCameraById(cameraId)
                                    showCameraPicker = false
                                }
                            )
                        }
                    } else {
                        Text("Memuat kamera...", modifier = Modifier.padding(12.dp),
                            style = TextStyle(color = MUTED, fontSize = 12.sp))
                    }

                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                    // Force rescan USB
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = CYAN, modifier = Modifier.size(14.dp))
                                Text("Pindai Ulang USB", style = TextStyle(color = CYAN, fontSize = 11.sp))
                            }
                        },
                        onClick = {
                            viewModel.forceRescanUsbCamera()
                            showCameraPicker = false
                        }
                    )
                }
            }
        } // end camera preview Box

        // ─── Operator queue + search (ALL modes — matches Electron renderQueueList) ───
        // Photoshoot: show 'sent' students (operator picks who to photograph)
        // Non-photoshoot: show pending+active queue for this channel
        if (isPhotoshoot && opSentQueue.isNotEmpty()) {
            // Photoshoot: search box + sent queue
            OutlinedTextField(
                value = opSearchQuery, onValueChange = { opSearchQuery = it },
                label = { Text("Cari peserta dikirim…", color = MUTED, fontSize = 10.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = GREEN, unfocusedBorderColor = BORDER, cursorColor = GREEN,
                    focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp)) },
                trailingIcon = { if (opSearchQuery.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = "Hapus", tint = MUTED, modifier = Modifier.size(14.dp).clickable { opSearchQuery = "" }) },
                textStyle = TextStyle(fontSize = 11.sp)
            )
            // Queue count
            Text("Antrean: ${opSentQueue.size} dikirim", style = TextStyle(color = CYAN, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            // Queue list (scrollable, max 4 items visible)
            Column(modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                opSearchResults.take(20).forEach { s ->
                    val isSelected = activeStudent?.id == s.id
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clickable {
                            opSelectedStudent = s
                        },
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) GOLD else BORDER.copy(alpha = 0.3f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = if (isSelected) GOLD else MUTED, modifier = Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = Color.White, fontSize = 10.sp), maxLines = 1)
                                Text(s.nim.ifBlank { "-" }, style = TextStyle(color = MUTED, fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                            }
                            if (isSelected) Text("✓", style = TextStyle(color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        } else if (isPhotoshoot && opSentQueue.isEmpty()) {
            Text("Menunggu MC mengirim peserta…", style = TextStyle(color = MUTED, fontSize = 10.sp), modifier = Modifier.padding(4.dp))
        } else if (!isPhotoshoot) {
            // Non-photoshoot: show channel queue (pending + active students)
            val opQueue = db.filter {
                it.assignedChannel == myChannel && (it.status == "pending" || isActiveStatus(it.status))
            }
            if (opQueue.isNotEmpty()) {
                Text("Antrean Ch.$myChannel (${opQueue.size})", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Column(modifier = Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState())) {
                    opQueue.take(15).forEachIndexed { idx, s ->
                        val isActive = isActiveStatus(s.status)
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isActive) CARD else PANEL),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) GOLD.copy(alpha = 0.5f) else BORDER.copy(alpha = 0.2f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${idx + 1}", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(14.dp))
                                Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (isActive) GOLD else MUTED.copy(alpha = 0.3f)))
                                Text(s.nama.ifBlank { s.nim }.take(25), style = TextStyle(color = if (isActive) GOLD else Color.White, fontSize = 9.sp), modifier = Modifier.weight(1f), maxLines = 1)
                                if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 8.sp))
                            }
                        }
                    }
                }
            }
        }

        // ─── Shutter mode selector (manual / 3s / 5s / 10s / hand) ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("manual" to "Manual", "timer-3" to "3s", "timer-5" to "5s", "timer-10" to "10s", "hand" to "Tangan").forEach { (mode, label) ->
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { shutterMode = mode }
                        .border(1.dp, if (shutterMode == mode) GOLD else BORDER, RoundedCornerShape(6.dp)),
                    colors = CardDefaults.cardColors(containerColor = if (shutterMode == mode) CARD else PANEL),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(label, modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp).fillMaxWidth(),
                        style = TextStyle(color = if (shutterMode == mode) GOLD else MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                }
            }
        }

        // ─── Capture phase indicator (Toga / Ijazah / Sending) ───
        val phaseLabel = when (capturePhase) {
            CapturePhase.STANDBY -> "Standby"
            CapturePhase.READY_1 -> "Pose 1 — Toga"
            CapturePhase.READY_2 -> "Pose 2 — Ijazah"
            CapturePhase.SENDING -> "Mengirim..."
        }
        val photosCaptured = opCapturedPhotos.size
        Text(
            "Fase: $phaseLabel  •  $photosCaptured foto tersimpan",
            style = TextStyle(color = if (capturePhase == CapturePhase.SENDING) GREEN else GOLD, fontSize = 10.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.fillMaxWidth()
        )

        // Timer countdown overlay (big number in center of preview) + cancel button
        if (timerCountdown != null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "${timerCountdown}",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = TextStyle(color = GOLD, fontSize = 36.sp, fontWeight = FontWeight.Black)
                    )
                }
            }
            // Cancel timer button (matches Electron Esc key / BATAL button)
            Button(
                onClick = {
                    timerJob?.cancel()
                    timerJob = null
                    timerCountdown = null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RED),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("BATAL", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Flash overlay (white flash on capture — matches Electron operator-panel.tsx:1738-1747)
        if (showFlash) {
            Box(Modifier.fillMaxWidth().height(100.dp).background(Color.White.copy(alpha = 0.6f)))
        }

        // Gridline toggle button (matches Electron Grid toggle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Grid toggle
            Card(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { gridlineEnabled = !gridlineEnabled }
                    .border(1.dp, if (gridlineEnabled) GOLD else BORDER, RoundedCornerShape(6.dp)),
                colors = CardDefaults.cardColors(containerColor = if (gridlineEnabled) CARD else PANEL),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GridView, contentDescription = "Grid", tint = if (gridlineEnabled) GOLD else MUTED, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Grid", style = TextStyle(color = if (gridlineEnabled) GOLD else MUTED, fontSize = 8.sp))
                }
            }
            // Grid type selector (only when enabled)
            if (gridlineEnabled) {
                listOf("thirds" to "⅓", "quarters" to "¼", "crosshair" to "+", "diagonal" to "╳").forEach { (type, label) ->
                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { gridlineType = type }
                            .border(1.dp, if (gridlineType == type) GOLD else BORDER, RoundedCornerShape(4.dp)),
                        colors = CardDefaults.cardColors(containerColor = if (gridlineType == type) CARD else PANEL),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                            style = TextStyle(color = if (gridlineType == type) GOLD else MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                    }
                }
                // Thickness selector (1=thin, 2=medium, 3=thick)
                listOf(1 to "T", 2 to "M", 3 to "K").forEach { (thick, label) ->
                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { gridlineThickness = thick }
                            .border(1.dp, if (gridlineThickness == thick) GOLD else BORDER, RoundedCornerShape(4.dp)),
                        colors = CardDefaults.cardColors(containerColor = if (gridlineThickness == thick) CARD else PANEL),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                            style = TextStyle(color = if (gridlineThickness == thick) GOLD else MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
        // Color selector row (only when gridline enabled)
        if (gridlineEnabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Warna:", style = TextStyle(color = MUTED, fontSize = 8.sp))
                listOf("white" to Color.White, "yellow" to Color(0xFFfacc15), "red" to Color(0xFFef4444), "cyan" to Color(0xFF06b6d4), "green" to Color(0xFF22c55e)).forEach { (name, color) ->
                    Card(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { gridlineColor = name }
                            .border(2.dp, if (gridlineColor == name) GOLD else Color.Transparent, RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = color),
                        shape = RoundedCornerShape(8.dp)
                    ) {}
                }
            }
        }

        // ─── Shutter button (no gallery thumbnail — user request) ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Shutter button — label changes based on capture phase
            val shutterLabel = when {
                isCapturing -> "Menyimpan..."
                capturePhase == CapturePhase.READY_2 -> "Tangkap Ijazah"
                capturePhase == CapturePhase.SENDING -> "Mengirim..."
                else -> "Tangkap ${if (CameraModes.isPhotoshootMode(project?.config?.mode ?: CameraModes.SINGLE)) "Foto" else "Toga"}"
            }

            Button(
                onClick = {
                    if (isCapturing || activeStudent == null || timerCountdown != null) return@Button
                    captureError = null

                    // Timer mode: start countdown, then capture
                    val timerDuration = when (shutterMode) {
                        "timer-3" -> 3
                        "timer-5" -> 5
                        "timer-10" -> 10
                        else -> 0
                    }

                    val doCapture: () -> Unit = {
                        isCapturing = true
                        try {
                            val bitmap = textureView.bitmap
                            val proj = project
                            if (bitmap != null && proj != null) {
                                val processed = try {
                                    CameraCapture.processFrame(
                                        sourceBitmap = bitmap,
                                        config = proj.config,
                                        frameBitmap = frameBitmap
                                    )
                                } catch (e: Exception) {
                                    bitmap
                                }
                                capturedBitmap = processed
                                val base64 = CameraCapture.bitmapToBase64(processed, 95)
                                // Flash overlay effect (matches Electron operator-panel.tsx:1738-1747)
                                showFlash = true
                                kotlinx.coroutines.MainScope().launch {
                                    kotlinx.coroutines.delay(200)
                                    showFlash = false
                                }
                                viewModel.handleLocalCapture(activeStudent, base64, activeChannel)
                            } else {
                                captureError = if (bitmap == null) "Preview belum siap, coba lagi." else "Proyek tidak ditemukan."
                            }
                        } catch (e: Exception) {
                            captureError = "Gagal capture: ${e.message}"
                        } finally {
                            isCapturing = false
                        }
                    }

                    if (timerDuration > 0) {
                        // Countdown timer — store job so it can be cancelled
                        timerCountdown = timerDuration
                        timerJob = kotlinx.coroutines.MainScope().launch {
                            for (i in timerDuration downTo 1) {
                                timerCountdown = i
                                kotlinx.coroutines.delay(1000)
                            }
                            timerCountdown = null
                            timerJob = null
                            doCapture()
                        }
                    } else {
                        doCapture()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeStudent != null && !isCapturing && timerCountdown == null) GOLD else BORDER
                ),
                shape = RoundedCornerShape(14.dp),
                enabled = activeStudent != null && !isCapturing && timerCountdown == null
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BG, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Menyimpan...", color = BG, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else if (timerCountdown != null) {
                    Text("$timerCountdown...", color = BG, fontSize = 16.sp, fontWeight = FontWeight.Black)
                } else {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(24.dp), tint = BG)
                    Spacer(Modifier.width(6.dp))
                    Text(shutterLabel, color = BG, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ─── Gridline Canvas (matches Electron SVG gridlines) ────────
@Composable
private fun GridlineCanvas(type: String, thickness: Int = 1, colorName: String = "white", modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val baseColor = when (colorName) {
            "yellow" -> Color(0xFFfacc15)
            "red" -> Color(0xFFef4444)
            "cyan" -> Color(0xFF06b6d4)
            "green" -> Color(0xFF22c55e)
            else -> Color.White
        }
        val color = baseColor.copy(alpha = 0.35f)
        val stroke = when (thickness) { 1 -> 1f; 2 -> 2f; 3 -> 3f; else -> 1f }

        when (type) {
            "thirds" -> {
                drawLine(color, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(color, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), stroke)
                drawLine(color, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(color, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), stroke)
            }
            "quarters" -> {
                drawLine(color, Offset(w / 4f, 0f), Offset(w / 4f, h), stroke)
                drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                drawLine(color, Offset(3 * w / 4f, 0f), Offset(3 * w / 4f, h), stroke)
                drawLine(color, Offset(0f, h / 4f), Offset(w, h / 4f), stroke)
                drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                drawLine(color, Offset(0f, 3 * h / 4f), Offset(w, 3 * h / 4f), stroke)
            }
            "crosshair" -> {
                drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                drawCircle(color, minOf(w, h) / 6f, center = Offset(w / 2f, h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            "diagonal" -> {
                drawLine(color, Offset(0f, 0f), Offset(w, h), stroke)
                drawLine(color, Offset(w, 0f), Offset(0f, h), stroke)
                val faint = color.copy(alpha = 0.15f)
                drawLine(faint, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(faint, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), stroke)
                drawLine(faint, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(faint, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), stroke)
            }
        }
    }
}
