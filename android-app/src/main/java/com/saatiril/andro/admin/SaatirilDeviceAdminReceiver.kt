package com.saatiril.andro.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Saatiril's DeviceAdminReceiver — required for Device Owner status,
 * which in turn is required for true Lock Task Mode (kiosk mode) without
 * per-launch confirmation dialogs.
 *
 * ## What this is
 * A thin subclass of [DeviceAdminReceiver] that Android's `dpm` tool
 * binds to when granting Device Owner status via:
 *
 * ```
 * adb shell dpm set-device-owner com.saatiril.andro/.admin.SaatirilDeviceAdminReceiver
 * ```
 *
 * After that one-time setup, [com.saatiril.andro.MainActivity] can call
 * `DevicePolicyManager.setLockTaskPackages()` to whitelist our own
 * package, then `startLockTask()` will lock the device into Saatiril
 * WITHOUT showing the "Pin this app?" confirmation dialog AND without
 * the user being able to exit via the long-press Back+Recents gesture.
 *
 * ## What this is NOT
 * This receiver does NOT enforce any device-admin policies (no password
 * rules, no camera disable, no remote wipe). Saatiril is not an MDM
 * solution. The Device Owner status is used purely for the Lock Task
 * Mode whitelist capability.
 *
 * ## Why the class is empty
 * The callbacks (`onEnabled`, `onDisableRequested`, etc.) are overridden
 * only for logging — they have no functional behavior. If you want to
 * surface "Device Owner granted" as a toast or notification, add it in
 * [onEnabled].
 *
 * ## Removing Device Owner status
 * To undo (e.g., to repurpose the device after the ceremony):
 * ```
 * adb shell dpm remove-active-admin com.saatiril.andro/.admin.SaatirilDeviceAdminReceiver
 * ```
 * Or call `DevicePolicyManager.clearDeviceOwnerApp()` from inside the app.
 */
class SaatirilDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "SaatirilDAReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled (intent=$intent)")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device Admin disable requested")
        return "Menonaktifkan Device Admin akan menghilangkan mode kiosk Saatiril."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
    }
}
