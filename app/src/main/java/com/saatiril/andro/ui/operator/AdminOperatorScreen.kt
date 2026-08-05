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
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // ── Shutter modes (matches Electron: manual / timer-3 / timer-5 / timer-10) ──
    var shutterMode by remember { mutableStateOf("manual") }  // manual | timer-3 | timer-5 | timer-10
    var timerCountdown by remember { mutableStateOf<Int?>(null) }  // null = no active timer

    // ── Capture phase (from ViewModel) ──
    val capturePhase by viewModel.capturePhase.collectAsState()
    val opCapturedPhotos by viewModel.opCapturedPhotos.collectAsState()

    // Find the current target student (the one MC called to the stage)
    val db = project?.database ?: emptyList()
    val activeStudent = db.firstOrNull { isActiveStatus(it.status) }
    val activeChannel = activeStudent?.let { getActiveChannel(it.status) } ?: 1

    // Reset capture state when the active student changes (new student called)
    val activeStudentId = activeStudent?.id
    LaunchedEffect(activeStudentId) {
        viewModel.resetCaptureState()
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

        // ─── Camera preview ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, BORDER, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                factory = { textureView },
                modifier = Modifier.fillMaxSize()
            )

            // Frame overlay on top of the preview (shows the PNG frame)
            frameBitmap?.let { fb ->
                Image(
                    bitmap = fb.asImageBitmap(),
                    contentDescription = "Frame overlay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // Camera status + source overlay (top-left)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (cameraConnected) GREEN else RED))
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

            // Capture error overlay
            captureError?.let { err ->
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(err, Modifier.padding(8.dp), style = TextStyle(color = Color.White, fontSize = 10.sp))
                }
            }
        }

        // ─── Shutter mode selector (manual / 3s / 5s / 10s) ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("manual" to "Manual", "timer-3" to "3s", "timer-5" to "5s", "timer-10" to "10s").forEach { (mode, label) ->
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

        // Timer countdown overlay (big number in center of preview)
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
        }

        // ─── Captured preview thumbnail + shutter ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Last captured photo thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PANEL)
                    .border(1.dp, BORDER, RoundedCornerShape(6.dp))
            ) {
                capturedBitmap?.let { bmp ->
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "Foto terakhir", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: run {
                    Icon(Icons.Default.Photo, contentDescription = null, tint = MUTED.copy(alpha = 0.3f), modifier = Modifier.size(24.dp).align(Alignment.Center))
                }
            }

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
                            if (bitmap == null) {
                                captureError = "Preview belum siap, coba lagi."
                                isCapturing = false
                                return@doCapture
                            }
                            val proj = project
                            if (proj == null) {
                                captureError = "Proyek tidak ditemukan."
                                isCapturing = false
                                return@doCapture
                            }
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
                            viewModel.handleLocalCapture(activeStudent, base64, activeChannel)
                        } catch (e: Exception) {
                            captureError = "Gagal capture: ${e.message}"
                        } finally {
                            isCapturing = false
                        }
                    }

                    if (timerDuration > 0) {
                        // Countdown timer
                        timerCountdown = timerDuration
                        kotlinx.coroutines.MainScope().launch {
                            for (i in timerDuration downTo 1) {
                                timerCountdown = i
                                kotlinx.coroutines.delay(1000)
                            }
                            timerCountdown = null
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
