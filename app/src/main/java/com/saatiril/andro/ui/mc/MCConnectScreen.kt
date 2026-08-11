package com.saatiril.andro.ui.mc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.ConnectionState
import com.saatiril.andro.data.OperatorViewModel
import com.saatiril.andro.data.Roles

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val CYAN = Color(0xFF06b6d4)

/**
 * MC Connect Screen — the MC enters the server IP, password, and channel,
 * then connects to the admin's server as MC (Master of Ceremony).
 *
 * No license is needed — the admin (server) has the license. The MC just
 * connects and calls students to the stage.
 *
 * On successful authentication, navigates to [MCPanelScreen].
 */
@Composable
fun MCConnectScreen(
    adminViewModel: AdminViewModel,
    opViewModel: OperatorViewModel = viewModel()
) {
    // Lock screen — prevent accidental exit via back button
    com.saatiril.andro.ui.util.LockScreenHandler {
        adminViewModel.backToRoleSelect()
    }

    val connectionState by opViewModel.connectionState.collectAsState()
    val authError by opViewModel.authError.collectAsState()
    val connectionError by opViewModel.connectionError.collectAsState()
    val passwordRequired by opViewModel.passwordRequired.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var serverIp by remember { mutableStateOf("192.168.1.") }
    var port by remember { mutableStateOf("3003") }
    var password by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableStateOf(1) }

    // Auto-navigate to MC_PANEL when authenticated
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.AUTHENTICATED ||
            connectionState == ConnectionState.WAITING_FOR_DATA
        ) {
            adminViewModel.mcConnected()
        }
    }

    val isConnecting = connectionState == ConnectionState.CONNECTING ||
        connectionState == ConnectionState.RECONNECTING ||
        connectionState == ConnectionState.AUTHENTICATING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(16.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { adminViewModel.backToRoleSelect() },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(10.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = GREEN, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                "MC PANGGILAN",
                style = TextStyle(color = GREEN, fontSize = 18.sp, fontWeight = FontWeight.Black),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(24.dp))

        // Connection status
        val (statusText, statusColor) = when (connectionState) {
            ConnectionState.DISCONNECTED -> "Belum terhubung" to MUTED
            ConnectionState.CONNECTING -> "Menghubungkan…" to GOLD
            ConnectionState.RECONNECTING -> "Menghubungkan ulang…" to GOLD
            ConnectionState.CONNECTED -> "Terhubung — autentikasi…" to CYAN
            ConnectionState.AUTHENTICATING -> "Autentikasi…" to CYAN
            ConnectionState.AUTHENTICATED -> "Terhubung ✓" to GREEN
            ConnectionState.AUTH_FAILED -> "Autentikasi gagal" to RED
            ConnectionState.WAITING_FOR_DATA -> "Menunggu data…" to GREEN
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
                Text(statusText, style = TextStyle(color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }

        // Errors
        connectionError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.1f)), shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(alpha = 0.5f))) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = RED, modifier = Modifier.size(14.dp))
                    Text(err, style = TextStyle(color = RED, fontSize = 11.sp))
                }
            }
        }
        authError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.1f)), shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, RED.copy(alpha = 0.5f))) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = RED, modifier = Modifier.size(14.dp))
                    Text(err, style = TextStyle(color = RED, fontSize = 11.sp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Server IP
        Text("Server IP", style = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(8.dp)) {
                Text("http://", modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp), style = TextStyle(color = MUTED, fontSize = 14.sp, fontFamily = FontFamily.Monospace))
            }
            OutlinedTextField(
                value = serverIp, onValueChange = { serverIp = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("192.168.1.5", style = TextStyle(color = MUTED.copy(alpha = 0.4f), fontSize = 14.sp, fontFamily = FontFamily.Monospace)) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = GREEN, unfocusedBorderColor = BORDER, cursorColor = GREEN, focusedContainerColor = PANEL, unfocusedContainerColor = PANEL),
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                modifier = Modifier.width(80.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = GREEN, unfocusedBorderColor = BORDER, cursorColor = GREEN, focusedContainerColor = PANEL, unfocusedContainerColor = PANEL),
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(Modifier.height(16.dp))

        // Password
        Text("Password Sesi (jika ada)", style = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Kosongkan jika tidak ada", style = TextStyle(color = MUTED.copy(alpha = 0.4f), fontSize = 13.sp)) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = GREEN, unfocusedBorderColor = BORDER, cursorColor = GREEN, focusedContainerColor = PANEL, unfocusedContainerColor = PANEL),
            shape = RoundedCornerShape(8.dp),
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp)) }
        )

        Spacer(Modifier.height(16.dp))

        // Channel
        Text("Jalur MC", style = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McChannelOption(1, "Ch.1", selectedChannel == 1, Modifier.weight(1f)) { selectedChannel = 1 }
            McChannelOption(2, "Ch.2", selectedChannel == 2, Modifier.weight(1f)) { selectedChannel = 2 }
        }

        Spacer(Modifier.height(24.dp))

        // Connect button
        Button(
            onClick = {
                keyboard?.hide()
                val url = "http://${serverIp.trim()}:${port.trim()}"
                opViewModel.connect(url, Roles.MC, selectedChannel, password.ifBlank { null })
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !isConnecting && serverIp.isNotBlank() && port.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = if (isConnecting) BORDER else GREEN),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GREEN, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Menghubungkan…", color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG)
                Spacer(Modifier.width(8.dp))
                Text("CONNECT KE SERVER", color = BG, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Help
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PANEL.copy(alpha = 0.5f)), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = CYAN, modifier = Modifier.size(14.dp))
                    Text("Petunjuk:", style = TextStyle(color = CYAN, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
                Text(
                    "1. Pastikan HP ini dan HP admin terhubung ke WiFi yang sama.\n" +
                    "2. Tanyakan IP server ke admin (lihat di tab Admin).\n" +
                    "3. Pilih jalur MC yang sesuai (Ch.1 atau Ch.2).\n" +
                    "4. Tekan CONNECT — MC bisa lihat antrean dan panggil mahasiswa.",
                    style = TextStyle(color = MUTED, fontSize = 10.sp)
                )
            }
        }
    }
}

@Composable
private fun McChannelOption(channel: Int, label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .border(2.dp, if (isSelected) GREEN else BORDER, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CARD else PANEL),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Campaign, contentDescription = null, tint = if (isSelected) GREEN else MUTED, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = TextStyle(color = if (isSelected) GREEN else MUTED, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
    }
}
