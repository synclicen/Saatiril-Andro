package com.saatiril.andro

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.ui.generator.LicenseGeneratorScreen
import com.saatiril.andro.ui.hub.ProjectHubScreen
import com.saatiril.andro.ui.license.LicenseGateScreen
import com.saatiril.andro.ui.main.MainScaffold
import com.saatiril.andro.ui.mc.MCConnectScreen
import com.saatiril.andro.ui.mc.MCModeSelectScreen
import com.saatiril.andro.ui.mc.MCPanelScreen
import com.saatiril.andro.ui.mc.MCRemoteScreen
import com.saatiril.andro.ui.mc.MCRemoteServerScreen
import com.saatiril.andro.ui.operator.OperatorCameraScreen
import com.saatiril.andro.ui.operator.OperatorConnectScreen
import com.saatiril.andro.ui.role.RoleSelectionScreen
import com.saatiril.andro.ui.setup.ProjectSetupScreen

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Saatiril Andro — the Android version of the Saatiril Electron Admin app.
 * ═════════════════════════════════════════════════════════════════════════
 *
 * The phone IS the LAN hub: it runs the Socket.io server (port 3003), creates
 * the project, imports the student Excel list, picks the output folder, and
 * saves photos as they arrive from operator phones. This mirrors the Electron
 * desktop app's role — but on Android, so the admin can run a ceremony from a
 * phone alone, with no laptop.
 *
 * Flow: LICENSE → HUB → SETUP → MAIN (server running).
 *
 * Anti-minimize / anti-sleep policy (event-grade reliability):
 *  - FLAG_KEEP_SCREEN_ON + manifest keepScreenOn → screen never sleeps while
 *    the activity is in the foreground.
 *  - setShowWhenLocked + setTurnScreenOn → app shows over the lockscreen and
 *    turns the screen back on if the device locks (e.g. power button pressed).
 *  - onUserLeaveHint → posts a high-priority "return to SAATIRIL" notification
 *    the moment the user presses Home/Recents, so they can re-enter with one
 *    tap (Android does not allow fully blocking the Home button without kiosk
 *    mode; this is the strongest non-kiosk mitigation).
 *  - onResume → fires a "foregrounded" event that the camera managers can
 *    observe to trigger immediate reconnect (defeats the 5-second black-screen
 *    that would otherwise happen when returning from background).
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RETURN_NOTIF_ID = 4242
        private const val RETURN_NOTIF_CHANNEL = "saatiril_return"
    }

    private lateinit var viewModel: AdminViewModel

    // POST_NOTIFICATIONS permission (Android 13+) — needed for the foreground
    // service notification that keeps the server alive.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Log.i(TAG, "POST_NOTIFICATIONS granted=$granted") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Anti screen-sleep ─────────────────────────────────────────────
        // FLAG_KEEP_SCREEN_ON: keeps the screen on while the window is visible.
        // Combined with android:keepScreenOn="true" in the manifest, this is
        // the standard, permission-free way to prevent screen sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ── Show over lockscreen + turn screen on ─────────────────────────
        // API 27+. Allows the app to show even when the device is locked
        // (e.g. user pressed power button) and turns the screen back on if
        // it was off. This is the strongest non-kiosk guarantee that the
        // ceremony panel stays visible. No permission required.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // Pre-API 27 fallback: use the deprecated window flags.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        viewModel = ViewModelProvider(this)[AdminViewModel::class.java]

        // Ask for notification permission (Android 13+) so the foreground
        // service notification can show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            SaatirilAppRoot(viewModel)
        }
    }

    /**
     * Called when the user presses Home or Recents. We cannot truly block
     * this (the system always backgrounds the app), but we CAN:
     *  - Post a high-priority "return to SAATIRIL" notification with a
     *    full-screen intent so the user can re-enter with one tap.
     *  - Mark the app as "left mid-ceremony" so the UI can show a warning
     *    when the user returns.
     *
     * The back button does NOT trigger onUserLeaveHint — it is handled
     * separately by BackHandler in MainScaffold.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Only post the return notification if a ceremony is actually running
        // (don't nag the user during license activation / project setup).
        if (viewModel.isServerRunning()) {
            postReturnToAppNotification()
            Log.w(TAG, "User left SAATIRIL mid-ceremony (Home/Recents pressed)")
        }
    }

    /**
     * Called every time the activity comes to the foreground (initial launch
     * AND return from background). Triggers a "foregrounded" event that the
     * camera managers observe to force an immediate reconnect — defeating the
     * 5-second black-screen that would otherwise happen if Android tore down
     * the camera during sleep.
     */
    override fun onResume() {
        super.onResume()
        viewModel.onAppForegrounded()
        // Cancel any outstanding "return to app" notification
        cancelReturnToAppNotification()
    }

    /**
     * Post a high-priority notification that brings the user back to the app
     * with one tap. Uses a full-screen intent (same category as an alarm /
     * incoming call) so on most devices it pops the app to the foreground
     * immediately, or at least shows a heads-up notification.
     */
    private fun postReturnToAppNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure the channel exists (channel creation is idempotent).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                RETURN_NOTIF_CHANNEL,
                "Kembali ke SAATIRIL",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pengingat untuk kembali ke aplikasi saat prosesi berlangsung"
                setBypassDnd(true)
            }
            nm.createNotificationChannel(channel)
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, RETURN_NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("SAATIRIL berjalan di latar")
            .setContentText("Prosesi sedang berlangsung. Ketuk untuk kembali.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            nm.notify(RETURN_NOTIF_ID, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post return notification: ${e.message}")
        }
    }

    private fun cancelReturnToAppNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(RETURN_NOTIF_ID)
        } catch (_: Exception) { }
    }
}

@Composable
private fun SaatirilAppRoot(viewModel: AdminViewModel) {
    val screen by viewModel.screen.collectAsState()
    when (screen) {
        AdminViewModel.Screen.LOADING -> {
            // Splash/loading screen — prevents license flash
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1a0b2e)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFFd4af37),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        AdminViewModel.Screen.LICENSE -> LicenseGateScreen(viewModel)
        AdminViewModel.Screen.ROLE_SELECT -> RoleSelectionScreen(viewModel)
        AdminViewModel.Screen.HUB -> ProjectHubScreen(viewModel)
        AdminViewModel.Screen.SETUP -> ProjectSetupScreen(viewModel)
        AdminViewModel.Screen.MAIN -> MainScaffold(viewModel)
        AdminViewModel.Screen.GENERATOR -> LicenseGeneratorScreen(viewModel)
        AdminViewModel.Screen.OPERATOR_CONNECT -> OperatorConnectScreen(viewModel)
        AdminViewModel.Screen.OPERATOR_CAMERA -> OperatorCameraScreen(viewModel)
        AdminViewModel.Screen.MC_CONNECT -> MCConnectScreen(viewModel)
        AdminViewModel.Screen.MC_PANEL -> MCPanelScreen(viewModel)
        AdminViewModel.Screen.MC_REMOTE -> MCRemoteScreen(viewModel)
        AdminViewModel.Screen.MC_MODE_SELECT -> MCModeSelectScreen(viewModel)
        AdminViewModel.Screen.MC_REMOTE_SERVER -> MCRemoteServerScreen(viewModel)
    }
}
