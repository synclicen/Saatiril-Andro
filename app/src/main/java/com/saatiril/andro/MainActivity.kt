package com.saatiril.andro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
 */
class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var viewModel: AdminViewModel

    // POST_NOTIFICATIONS permission (Android 13+) — needed for the foreground
    // service notification that keeps the server alive.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Log.i(TAG, "POST_NOTIFICATIONS granted=$granted") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
