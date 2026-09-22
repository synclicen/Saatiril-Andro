package com.saatiril.andro.ui.util

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PANEL = Color(0xFF2a164a)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val AMBER = Color(0xFFfbbf24)

/**
 * Lock screen handler for MC and Operator roles.
 *
 * Prevents accidental back-button exit from the MC/Operator panel.
 * When the user presses the hardware back button, a confirmation dialog
 * appears asking "Keluar dari Panel?" with:
 *  - "Tetap di sini" (stay) — dismiss dialog, remain in panel
 *  - "Keluar" (exit) — disconnect + return to role selection
 *
 * This ensures MC/Operator can't accidentally leave their panel and end up
 * in the Admin or other screens during a live ceremony.
 *
 * @param onExit Called when the user confirms they want to exit.
 */
@Composable
fun LockScreenHandler(
    onExit: () -> Unit
) {
    var showExitDialog by remember { mutableStateOf(false) }

    // Intercept hardware back button — ALWAYS enabled, can't be bypassed
    BackHandler(enabled = true) {
        showExitDialog = true
    }

    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AMBER, modifier = Modifier.size(32.dp)) },
            title = {
                Text("Keluar dari Panel?", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            },
            text = {
                Text(
                    "Anda akan keluar dan kembali ke pemilihan peran.\n\n" +
                    "Pastikan prosesi tidak sedang berlangsung.\n" +
                    "Koneksi ke server akan diputus.",
                    style = TextStyle(color = MUTED, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        onExit()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RED),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("Keluar", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GREEN),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GREEN)
                ) {
                    Text("Tetap di sini", color = GREEN, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = PANEL,
            titleContentColor = Color.White,
            textContentColor = MUTED
        )
    }
}
