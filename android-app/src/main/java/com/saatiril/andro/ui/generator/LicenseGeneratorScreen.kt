package com.saatiril.andro.ui.generator

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.LicenseManager

// ─── Saatiril Theme Colors (match Electron admin/page.tsx) ───
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val EMERALD = Color(0xFF22c55e)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)

/**
 * License Code Generator (Developer Panel).
 *
 * Faithful port of the Electron `src/app/admin/page.tsx` — same layout, same
 * field labels, same result card with verification badge + Display ID + expiry
 * + days remaining + copyable activation code.
 *
 * Reachable from the LicenseGateScreen (small "Developer" link at the bottom)
 * and from the ProjectHubScreen (key icon in the header). Returns to whichever
 * screen makes sense based on the current license state.
 *
 * SECURITY: This screen requires the developer Admin Key
 * (`SHA-256(LICENSE_SECRET + ":admin-api-key")[:16].upper()`) to generate a
 * code. Without it, no code is produced — the same gate as the Electron
 * `/api/generate-license` route.
 */
@Composable
fun LicenseGeneratorScreen(viewModel: AdminViewModel) {
    var machineIdInput by remember { mutableStateOf("") }   // accepts alias OR 64-char hex
    var adminKey by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LicenseManager.GenerateResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copiedCode by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // Address-book dialog state
    var showAddressBook by remember { mutableStateOf(false) }

    // Live resolution feedback as the admin types
    val resolvedId = remember(machineIdInput) {
        if (machineIdInput.isBlank()) null
        else viewModel.resolveMachineIdInput(machineIdInput)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── Header (icon + title + subtitle) ───
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GOLD.copy(alpha = 0.08f))
                        .border(1.dp, GOLD.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = GOLD, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text("Generator Kode Aktivasi", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White))
                Text("Panel Pengembang SAATIRIL", style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = GOLD, letterSpacing = 2.sp))
            }

            // ─── Back link ───
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { viewModel.closeGenerator() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp))
                Text("Kembali ke Aplikasi", style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Medium))
            }

            // ─── Admin Key card ───
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PANEL)
                    .border(1.dp, BORDER, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("ADMIN KEY", style = TextStyle(color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                OutlinedTextField(
                    value = adminKey,
                    onValueChange = { adminKey = it; error = null },
                    placeholder = { Text("Masukkan admin key...", color = MUTED.copy(alpha = 0.4f), fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColors(),
                    textStyle = TextStyle(color = GOLD, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp)) }
                )
                Text(
                    "Admin key diperoleh dari file konfigurasi pengembang. Jika Anda tidak memiliki key ini, Anda tidak dapat membuat kode aktivasi.",
                    style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 10.sp, lineHeight = 14.sp)
                )
            }

            // ─── Machine ID / Alias card ───
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PANEL)
                    .border(1.dp, BORDER, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("NAMA PERANGKAT ATAU MACHINE ID", style = TextStyle(color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                    // Address-book button
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showAddressBook = true }
                            .background(GOLD.copy(alpha = 0.08f))
                            .border(1.dp, GOLD.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = null, tint = GOLD, modifier = Modifier.size(12.dp))
                        Text("Buku Alamat", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    }
                }
                OutlinedTextField(
                    value = machineIdInput,
                    onValueChange = { machineIdInput = it; error = null },
                    placeholder = { Text("mis. fajribowo2026  atau  64-char hex...", color = MUTED.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColors(),
                    textStyle = TextStyle(color = GOLD, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp)) }
                )
                // ── Live resolution feedback ──
                if (machineIdInput.isNotBlank()) {
                    if (resolvedId != null) {
                        val isAliasInput = !machineIdInput.trim().matches(Regex("^[a-fA-F0-9]{64}$"))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(EMERALD.copy(alpha = 0.08f))
                                .border(1.dp, EMERALD.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EMERALD, modifier = Modifier.size(12.dp))
                            Text(
                                if (isAliasInput) "Alias ditemukan → ${LicenseManager.getDisplayMachineIdStatic(resolvedId)}"
                                else "Machine ID valid → ${LicenseManager.getDisplayMachineIdStatic(resolvedId)}",
                                style = TextStyle(color = EMERALD, fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(RED.copy(alpha = 0.06f))
                                .border(1.dp, RED.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = RED, modifier = Modifier.size(12.dp))
                            Text(
                                "Bukan hex 64-char dan tidak ditemukan di Buku Alamat. Tambahkan dulu lewat tombol Buku Alamat.",
                                style = TextStyle(color = RED, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            )
                        }
                    }
                }
                Text(
                    "Cara pakai: ketik NAMA perangkat (mis. fajribowo2026) yang sudah diset di layar Aktivasi " +
                        "perangkat ini ATAU yang terdaftar di Buku Alamat, ATAU paste 64-char Machine ID asli. " +
                        "Jika nama belum dikenali, pastikan sudah diset via \"Beri Nama Perangkat\" di perangkat " +
                        "yang sama, atau tambahkan ke Buku Alamat jika perangkat berbeda.",
                    style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 10.sp, lineHeight = 14.sp)
                )
            }

            // ─── Generate button ───
            Button(
                onClick = {
                    error = null
                    result = null
                    if (machineIdInput.trim().isEmpty()) { error = "Masukkan Nama Perangkat atau Machine ID terlebih dahulu."; return@Button }
                    if (adminKey.trim().isEmpty()) { error = "Masukkan Admin Key terlebih dahulu."; return@Button }
                    if (resolvedId == null) {
                        error = "Input tidak dikenali. Bukan hex 64-char dan tidak ada di Buku Alamat. " +
                            "Tambahkan dulu lewat tombol Buku Alamat."
                        return@Button
                    }
                    val r = viewModel.generateLicenseCode(machineIdInput, adminKey)
                    if (r == null) {
                        error = "Gagal membuat kode aktivasi. Periksa Admin Key dan input identifier."
                    } else {
                        result = r
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(10.dp),
                enabled = machineIdInput.trim().isNotEmpty() && adminKey.trim().isNotEmpty() && resolvedId != null
            ) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp), tint = BG)
                Spacer(Modifier.width(6.dp))
                Text("Buat Kode Aktivasi", color = BG, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // ─── Error ───
            error?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RED.copy(alpha = 0.08f))
                        .border(1.dp, RED.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = RED, modifier = Modifier.size(16.dp))
                    Text(msg, style = TextStyle(color = RED, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }

            // ─── Result card ───
            result?.let { r ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PANEL)
                        .border(
                            1.dp,
                            if (r.verified) EMERALD.copy(alpha = 0.4f) else RED.copy(alpha = 0.4f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Verification badge
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (r.verified) EMERALD.copy(alpha = 0.15f) else RED.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (r.verified) Icons.Default.Verified else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (r.verified) EMERALD else RED,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            if (r.verified) "Kode Terverifikasi" else "Verifikasi Gagal",
                            style = TextStyle(
                                color = if (r.verified) EMERALD else RED,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Detail rows
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailRow("Display ID:", r.displayMachineId, GOLD, mono = true)
                        DetailRow("Tipe Lisensi:", "MONTHLY (1 Bulan)", EMERALD)
                        DetailRow("Berlaku Hingga:", r.expiresAtFormatted, CYAN)
                        DetailRow("Sisa Waktu:", "${r.daysRemaining} hari", EMERALD)
                    }

                    // Activation code box
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("KODE AKTIVASI", style = TextStyle(color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(BG)
                                .border(2.dp, GOLD, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                r.activationCode,
                                modifier = Modifier.weight(1f),
                                style = TextStyle(
                                    color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, textAlign = TextAlign.Center
                                )
                            )
                            // Copy button
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        clipboard.setText(AnnotatedString(r.activationCode))
                                        copiedCode = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    if (copiedCode) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Salin",
                                    tint = if (copiedCode) EMERALD else GOLD,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    if (copiedCode) "Tersalin!" else "Salin",
                                    style = TextStyle(
                                        color = if (copiedCode) EMERALD else GOLD,
                                        fontSize = 10.sp, fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }

                    // Reset copied state after 2s
                    LaunchedEffect(copiedCode) {
                        if (copiedCode) {
                            kotlinx.coroutines.delay(2000)
                            copiedCode = false
                        }
                    }

                    // Instructions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CYAN.copy(alpha = 0.08f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            buildAnnotatedString {
                                append("Cara Menggunakan: ")
                                append("Salin kode di atas, kirimkan ke user (via WhatsApp/email/dll). User memasukkan kode tersebut di layar aktivasi SAATIRIL. Kode berlaku 30 hari sejak dibuat.")
                            },
                            style = TextStyle(color = MUTED, fontSize = 10.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }

            // ─── Footer help ───
            Text(
                "Halaman ini hanya untuk pengembang SAATIRIL.\nJika Anda adalah user, hubungi pengembang untuk mendapatkan kode aktivasi.",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
            )
        }
    }

    // ─── Address Book dialog ───
    if (showAddressBook) {
        AddressBookDialog(
            viewModel = viewModel,
            onDismiss = { showAddressBook = false },
            onPickAlias = { alias ->
                machineIdInput = alias
                showAddressBook = false
            }
        )
    }
}

/**
 * Address Book dialog — manages (alias → machineId) entries. The admin can:
 *  - See all registered devices
 *  - Add a new entry (alias + 64-char machine ID)
 *  - Remove an entry
 *  - Tap an entry to insert its alias into the generator input
 */
@Composable
private fun AddressBookDialog(
    viewModel: AdminViewModel,
    onDismiss: () -> Unit,
    onPickAlias: (String) -> Unit
) {
    val entries by viewModel.addressBook.collectAsState()
    var newAlias by remember { mutableStateOf("") }
    var newMid by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = GOLD,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Contacts, contentDescription = null, tint = GOLD, modifier = Modifier.size(20.dp))
                Text("Buku Alamat Perangkat", color = GOLD, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ─── Add new entry ───
                Text("Tambah Perangkat Baru", color = CYAN, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = newAlias,
                    onValueChange = { newAlias = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }; localError = null },
                    placeholder = { Text("mis. fajribowo2026", color = MUTED.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    label = { Text("Nama Perangkat", color = MUTED, fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColors(),
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp)) }
                )
                OutlinedTextField(
                    value = newMid,
                    onValueChange = { newMid = it.filter { c -> c.isLetterOrDigit() }; localError = null },
                    placeholder = { Text("64-char hex dari user...", color = MUTED.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                    label = { Text("Machine ID Asli", color = MUTED, fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColors(),
                    textStyle = TextStyle(color = GOLD, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp)) }
                )
                localError?.let { Text(it, color = RED, fontSize = 11.sp) }
                Button(
                    onClick = {
                        val err = viewModel.addAddressBookEntry(newAlias, newMid)
                        if (err != null) {
                            localError = err
                        } else {
                            newAlias = ""
                            newMid = ""
                            localError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                    shape = RoundedCornerShape(8.dp),
                    enabled = newAlias.isNotBlank() && newMid.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = BG)
                    Spacer(Modifier.width(4.dp))
                    Text("Tambah / Update", color = BG, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                HorizontalDivider(color = BORDER, thickness = 1.dp)

                // ─── List existing entries ───
                Text("Tersimpan (${entries.size})", color = CYAN, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (entries.isEmpty()) {
                    Text(
                        "Belum ada perangkat terdaftar. Tambahkan sekali untuk setiap perangkat pelanggan — setelah itu, ketik namanya saja di Generator untuk membuat kode aktivasi.",
                        color = MUTED.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        entries.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BG)
                                    .border(1.dp, BORDER.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Label, contentDescription = null, tint = GREEN, modifier = Modifier.size(11.dp))
                                        Text(e.alias, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                    Text(
                                        "ID: ${e.displayMachineId}",
                                        color = MUTED.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                                // Pick
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onPickAlias(e.alias) }
                                        .background(GOLD.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(Icons.Default.Input, contentDescription = "Pilih", tint = GOLD, modifier = Modifier.size(10.dp))
                                    Text("Pilih", color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                // Delete
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { viewModel.removeAddressBookEntry(e.alias) }
                                        .background(RED.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = RED, modifier = Modifier.size(10.dp))
                                    Text("Hapus", color = RED, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MUTED),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Tutup", fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = TextStyle(color = MUTED, fontSize = 11.sp))
        Text(
            value,
            style = TextStyle(
                color = valueColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            )
        )
    }
}

@Composable
private fun tfColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = GOLD,
    unfocusedBorderColor = BORDER,
    cursorColor = GOLD,
    focusedContainerColor = BG,
    unfocusedContainerColor = BG
)
