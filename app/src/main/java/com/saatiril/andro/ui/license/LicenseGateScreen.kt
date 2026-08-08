package com.saatiril.andro.ui.license

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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.LicenseStatus

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val RED = Color(0xFFef4444)
private val GREEN = Color(0xFF4ade80)

/**
 * License activation screen — first-launch gate.
 * Shows the machine ID (for the admin to request a code) and a code input.
 * Matches the Electron `license-gate.tsx`.
 */
@Composable
fun LicenseGateScreen(viewModel: AdminViewModel) {
    val status by viewModel.licenseStatus.collectAsState()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = GOLD, modifier = Modifier.size(64.dp))
            Text("SAATIRIL", style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = GOLD))
            Text("Aktivasi Lisensi", style = TextStyle(fontSize = 16.sp, color = MUTED))

            if (status.active) {
                // Already active (shouldn't normally show, but just in case)
                Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GREEN, modifier = Modifier.size(40.dp))
                        Text("Lisensi aktif", color = GREEN, fontWeight = FontWeight.Bold)
                        Text("${status.daysLeft} hari tersisa", color = MUTED, fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    "Masukkan kode aktivasi 30 hari. Hubungi developer untuk mendapatkan kode.",
                    style = TextStyle(color = MUTED, fontSize = 12.sp), textAlign = TextAlign.Center
                )
            }

            // Machine ID card (copyable)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BORDER)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = GOLD, modifier = Modifier.size(18.dp))
                        Text("Machine ID", style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                    }
                    Text(
                        status.machineId,
                        style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(status.machineId)) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GOLD),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GOLD),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Salin", fontSize = 11.sp)
                        }
                    }
                }
            }

            // Code input
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }; error = null },
                label = { Text("Kode Aktivasi (XXXX-XXXX-XXXX-XXXX)", color = MUTED, fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD,
                    focusedContainerColor = PANEL, unfocusedContainerColor = PANEL
                ),
                textStyle = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = MUTED, modifier = Modifier.size(18.dp)) }
            )

            error?.let {
                Text(it, style = TextStyle(color = RED, fontSize = 12.sp))
            }

            Button(
                onClick = {
                    busy = true
                    error = null
                    val ok = viewModel.activateLicense(code)
                    busy = false
                    if (!ok) error = "Kode tidak valid atau kedaluwarsa. Periksa kembali."
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(12.dp),
                enabled = !busy && code.isNotBlank()
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = BG)
                Spacer(Modifier.width(6.dp))
                Text("Aktifkan", color = BG, fontWeight = FontWeight.Bold)
            }

            Text(
                "Lisensi terikat perangkat ini, berlaku 30 hari, tanpa grace period.",
                style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 10.sp), textAlign = TextAlign.Center
            )

            // ── MC + Operator entry points — no license needed ──
            // MC and operators connect to an admin's server; they don't need their own license.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.selectMcRole() },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GREEN),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp), tint = GREEN)
                    Spacer(Modifier.width(6.dp))
                    Text("Saya MC", color = GREEN, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.selectOperatorRole() },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CYAN),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CYAN.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp), tint = CYAN)
                    Spacer(Modifier.width(6.dp))
                    Text("Saya Operator", color = CYAN, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Text(
                "MC & Operator tidak perlu lisensi — connect ke server admin",
                style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 9.sp), textAlign = TextAlign.Center
            )

            // ── Developer entry point — discrete link to the code generator ──
            // Mirrors the Electron app where /admin is a separate URL.
            // Small, low-emphasis — only developers will look for it.
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { viewModel.openGenerator() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MUTED.copy(alpha = 0.4f), modifier = Modifier.size(11.dp))
                Text(
                    "Panel Pengembang",
                    style = TextStyle(color = MUTED.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}
