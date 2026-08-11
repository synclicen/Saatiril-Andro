package com.saatiril.andro.ui.mc

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.andro.data.AdminViewModel

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)

/**
 * MC Mode Select Screen — shown ONLY in MC-Only APK.
 *
 * MC chooses how to connect to Admin:
 *  - BLE Remote (Bluetooth): for Admin APK (HP) — 100% immune WiFi
 *  - WiFi (Socket.io): for Admin Electron (Laptop) — wired/WiFi LAN
 *
 * Both modes are locked down — MC can only PANGGIL/NEXT/RESET,
 * cannot access Admin or Operator panels.
 */
@Composable
fun MCModeSelectScreen(viewModel: AdminViewModel) {
    // Lock screen — prevent accidental exit
    com.saatiril.andro.ui.util.LockScreenHandler {
        // MC-Only APK: no exit — stay in app
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Campaign, contentDescription = null, tint = GOLD, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "SAATIRIL MC",
            style = TextStyle(color = GOLD, fontSize = 22.sp, fontWeight = FontWeight.Black),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Pilih Mode Koneksi",
            style = TextStyle(color = MUTED, fontSize = 13.sp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        // BLE Remote (Client) card — for Admin APK (HP)
        ModeCard(
            icon = Icons.Default.Bluetooth,
            title = "BLE REMOTE",
            subtitle = "Bluetooth — Untuk Admin HP",
            description = "MC = Client, Admin HP = Server\n100% immune interferensi WiFi\nCocok untuk 3000+ orang",
            colors = listOf(Color(0xFF3b82f6), Color(0xFF1d4ed8)),
            borderColor = Color(0xFF3b82f6),
            onClick = { viewModel.selectMcRemoteRole() }
        )
        Spacer(Modifier.height(12.dp))

        // BLE Server card — for Admin Electron (Laptop)
        ModeCard(
            icon = Icons.Default.BluetoothConnected,
            title = "BLE SERVER",
            subtitle = "Bluetooth — Untuk Admin Laptop",
            description = "MC = Server, Electron = Client\n100% immune interferensi WiFi\nCocok untuk Laptop Electron",
            colors = listOf(Color(0xFF8b5cf6), Color(0xFF6d28d9)),
            borderColor = Color(0xFF8b5cf6),
            onClick = { viewModel.selectMcRemoteServerRole() }
        )
        Spacer(Modifier.height(12.dp))

        // WiFi card — for Admin Electron (Laptop) via wired LAN
        ModeCard(
            icon = Icons.Default.Wifi,
            title = "WIFI / LAN",
            subtitle = "WiFi atau Kabel LAN",
            description = "Untuk Admin Electron (Laptop)\nConnect via WiFi atau wired LAN\nCocok untuk wired LAN setup",
            colors = listOf(Color(0xFF06b6d4), Color(0xFF0891b2)),
            borderColor = Color(0xFF06b6d4),
            onClick = { viewModel.selectMcRole() }
        )
        Spacer(Modifier.height(32.dp))

        Text(
            "v${com.saatiril.andro.BuildConfig.VERSION_NAME}",
            style = TextStyle(color = MUTED.copy(alpha = 0.4f), fontSize = 10.sp)
        )
    }
}

@Composable
private fun ModeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    description: String,
    colors: List<Color>,
    borderColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .border(2.dp, borderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(colors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = BG, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyle(color = borderColor, fontSize = 16.sp, fontWeight = FontWeight.Black))
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                Text(description, style = TextStyle(color = MUTED, fontSize = 10.sp))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = borderColor, modifier = Modifier.size(24.dp))
        }
    }
}
