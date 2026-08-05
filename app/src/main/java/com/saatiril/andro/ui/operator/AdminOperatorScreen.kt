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
import com.saatiril.andro.camera.Camera2Manager
import com.saatiril.andro.camera.CameraCapture
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
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

    // Camera2 manager — remember it across recompositions
    val cameraManager = remember { Camera2Manager(context) }
    val textureView = remember { TextureView(context) }
    val cameraConnected by cameraManager.isConnected.collectAsState()
    val availableCameras by cameraManager.availableCameras.collectAsState()
    val currentCameraId by cameraManager.currentCameraIdFlow.collectAsState()

    var capturedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Find the current target student (the one MC called to the stage)
    val db = project?.database ?: emptyList()
    val activeStudent = db.firstOrNull { isActiveStatus(it.status) }
    val activeChannel = activeStudent?.let { getActiveChannel(it.status) } ?: 1

    // Init camera on first show (only if permission granted), release on dispose
    DisposableEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraManager.setTextureView(textureView)
            cameraManager.enumerateCameras()
            cameraManager.openCamera()
        }
        onDispose {
            try { cameraManager.closeCamera() } catch (_: Exception) {}
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
            // Camera status overlay (top-left)
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
                Text(
                    if (cameraConnected) "Kamera aktif" else "Kamera mati",
                    style = TextStyle(color = Color.White, fontSize = 9.sp)
                )
            }
            // Camera switch button (top-right) — only if >1 camera
            if (availableCameras.size > 1) {
                IconButton(
                    onClick = {
                        // Switch to the next camera in the list
                        val cams = availableCameras
                        val currentIdx = cams.indexOfFirst { it.first == currentCameraId }
                        val nextIdx = (currentIdx + 1) % cams.size
                        val nextId = cams.getOrNull(nextIdx)?.first
                        if (nextId != null) cameraManager.switchCamera(nextId)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Ganti Kamera", tint = Color.White, modifier = Modifier.size(20.dp))
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

            // Shutter button (big, center)
            Button(
                onClick = {
                    if (isCapturing || activeStudent == null) return@Button
                    isCapturing = true
                    captureError = null
                    try {
                        // Get the preview frame bitmap from the TextureView
                        val bitmap = textureView.bitmap ?: run {
                            captureError = "Preview belum siap, coba lagi."
                            isCapturing = false
                            return@Button
                        }
                        // Apply crop + filter preset
                        val proj = project ?: run {
                            captureError = "Proyek tidak ditemukan."
                            isCapturing = false
                            return@Button
                        }
                        val processed = try {
                            CameraCapture.processFrame(
                                sourceBitmap = bitmap,
                                config = proj.config,
                                frameBitmap = null
                            )
                        } catch (e: Exception) {
                            bitmap // fallback: use raw bitmap if processing fails
                        }
                        capturedBitmap = processed
                        // Convert to base64 data URL
                        val base64 = CameraCapture.bitmapToBase64(processed, 95)
                        // Save to output folder + update project DB + broadcast SYNC_DB
                        viewModel.handleLocalCapture(activeStudent, base64, activeChannel)
                    } catch (e: Exception) {
                        captureError = "Gagal capture: ${e.message}"
                    } finally {
                        isCapturing = false
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeStudent != null && !isCapturing) GOLD else BORDER
                ),
                shape = RoundedCornerShape(14.dp),
                enabled = activeStudent != null && !isCapturing
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BG, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Menyimpan...", color = BG, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(24.dp), tint = BG)
                    Spacer(Modifier.width(6.dp))
                    Text("Tangkap Foto", color = BG, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
