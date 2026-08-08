package com.saatiril.andro.ui.role

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
private val CYAN = Color(0xFF06b6d4)

/**
 * Role Selection Screen — shown after license activation (or if license active).
 *
 * The user picks one of two roles:
 *  - ADMIN SERVER: the phone becomes the LAN hub. Creates project, runs server,
 *    acts as MC + Ch.1 camera. This is the existing flow (HUB → SETUP → MAIN).
 *  - OPERATOR KAMERA: the phone connects to an admin's server as a camera
 *    operator on a specific channel (Ch.1 or Ch.2). Photos are sent via socket
 *    to the admin for saving. No license needed.
 */
@Composable
fun RoleSelectionScreen(viewModel: AdminViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo / Title
        Icon(Icons.Default.School, contentDescription = null, tint = GOLD, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "SAATIRIL ANDRO",
            style = TextStyle(color = GOLD, fontSize = 24.sp, fontWeight = FontWeight.Black),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Pilih Peran Perangkat Ini",
            style = TextStyle(color = MUTED, fontSize = 13.sp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        // Admin Server card
        RoleCard(
            icon = Icons.Default.Hub,
            title = "ADMIN SERVER",
            subtitle = "Hub LAN + MC + Kamera Ch.1",
            description = "Buat proyek, jalankan server, panggil mahasiswa,\ndan foto sendiri sebagai Ch.1",
            colors = listOf(GOLD, Color(0xFFb8860b)),
            borderColor = GOLD,
            onClick = { viewModel.selectAdminRole() }
        )
        Spacer(Modifier.height(16.dp))

        // Operator card
        RoleCard(
            icon = Icons.Default.PhotoCamera,
            title = "OPERATOR KAMERA",
            subtitle = "Connect ke Server Admin",
            description = "Hubungkan ke server admin via WiFi,\nfoto mahasiswa di channel yang dipilih",
            colors = listOf(CYAN, Color(0xFF0891b2)),
            borderColor = CYAN,
            onClick = { viewModel.selectOperatorRole() }
        )
        Spacer(Modifier.height(32.dp))

        // Back to license / generator
        Text(
            "v${com.saatiril.andro.BuildConfig.VERSION_NAME}",
            style = TextStyle(color = MUTED.copy(alpha = 0.4f), fontSize = 10.sp)
        )
    }
}

@Composable
private fun RoleCard(
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
            // Icon with gradient background
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
                Text(description, style = TextStyle(color = MUTED, fontSize = 10.sp), maxLines = 3)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = borderColor, modifier = Modifier.size(24.dp))
        }
    }
}
