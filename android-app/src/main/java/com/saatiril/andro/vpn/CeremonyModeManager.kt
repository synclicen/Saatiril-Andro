package com.saatiril.andro.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.MutableLiveData

/**
 * Manager for Ceremony Mode — a VPN-based internet blocker.
 *
 * When enabled, it starts [CeremonyModeVpnService] which blocks all internet
 * traffic on this device except for the Saatiril app itself (which needs LAN
 * for the Socket.io server + optional Google Drive upload).
 *
 * Flow:
 * 1. User toggles "Mode Prosesi" in Admin Dashboard
 * 2. [CeremonyModeManager.enable()] is called
 * 3. If VPN permission not granted, [VpnService.prepare()] shows system dialog
 * 4. On approval, [CeremonyModeVpnService.start()] launches the VPN
 * 5. VPN blocks all apps except Saatiril → no WhatsApp/Instagram/Play Store
 * 6. Saatiril app still has LAN + Google Drive access
 *
 * To disable: [CeremonyModeManager.disable()] stops the VPN.
 */
class CeremonyModeManager {

    companion object {
        private const val TAG = "CeremonyModeMgr"

        /** LiveData of the current ceremony mode state (for UI observation). */
        val isActive = MutableLiveData(false)

        /**
         * Enable Ceremony Mode. If VPN permission hasn't been granted, prepares
         * the VPN (shows system consent dialog) via the provided launcher.
         *
         * @param context Activity/Context context
         * @param permissionLauncher ActivityResultLauncher for VpnService.prepare()
         * @return true if VPN started immediately, false if waiting for permission
         */
        fun enable(context: Context, permissionLauncher: ActivityResultLauncher<Intent>?): Boolean {
            Log.i(TAG, "enable() — requesting Ceremony Mode")

            // Check if we need VPN permission
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                // Permission not granted yet — show system dialog
                Log.i(TAG, "VPN permission needed — launching system dialog")
                if (permissionLauncher != null) {
                    permissionLauncher.launch(prepareIntent)
                    return false
                } else {
                    Log.e(TAG, "No permission launcher provided — cannot request VPN permission")
                    return false
                }
            } else {
                // Already have permission — start VPN directly
                Log.i(TAG, "VPN permission already granted — starting service")
                CeremonyModeVpnService.start(context)
                isActive.postValue(true)
                return true
            }
        }

        /**
         * Called when the VPN permission dialog returns.
         *
         * @param context Context
         * @param result resultCode from the permission dialog (Activity.RESULT_OK = granted)
         */
        fun onPermissionResult(context: Context, result: Int) {
            if (result == android.app.Activity.RESULT_OK) {
                Log.i(TAG, "VPN permission granted — starting service")
                CeremonyModeVpnService.start(context)
                isActive.postValue(true)
            } else {
                Log.w(TAG, "VPN permission denied — Ceremony Mode not enabled")
                isActive.postValue(false)
            }
        }

        /**
         * Disable Ceremony Mode — stop the VPN service.
         */
        fun disable(context: Context) {
            Log.i(TAG, "disable() — stopping Ceremony Mode")
            CeremonyModeVpnService.stop(context)
            isActive.postValue(false)
        }

        /** Toggle ceremony mode on/off. */
        fun toggle(context: Context, permissionLauncher: ActivityResultLauncher<Intent>?): Boolean {
            return if (isActive.value == true) {
                disable(context)
                false
            } else {
                enable(context, permissionLauncher)
            }
        }
    }
}
