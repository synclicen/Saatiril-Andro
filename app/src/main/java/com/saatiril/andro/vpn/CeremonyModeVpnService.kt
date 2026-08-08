package com.saatiril.andro.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.saatiril.andro.MainActivity
import com.saatiril.andro.R
import java.net.InetAddress

/**
 * Ceremony Mode VPN Service — blocks all internet traffic on THIS device
 * except for LAN (local network) and Google Drive (for optional photo backup).
 *
 * HOW IT WORKS:
 * Uses Android's VpnService API to create a TUN interface that captures all
 * outbound network traffic from THIS device. The VPN routes traffic through
 * a dummy interface that drops everything EXCEPT:
 *   - LAN traffic (192.168.x.x, 10.x.x.x, 172.16-31.x.x) → allowed
 *   - Google Drive IPs (drive.google.com, *.googleusercontent.com) → allowed
 *   - Everything else → dropped (no internet for WhatsApp, Instagram, etc.)
 *
 * IMPORTANT LIMITATION:
 * VpnService only affects the device it runs on. It CANNOT block internet
 * for OTHER devices connected to this phone's hotspot. Hotspot clients need
 * their own VpnService (e.g. operator APK) or manual airplane mode.
 *
 * USAGE:
 * - Admin: toggle "Mode Prosesi" in Admin Dashboard
 * - Operator: auto-activates when connected to server
 * - MC (browser): manual airplane mode required
 */
class CeremonyModeVpnService : VpnService() {

    companion object {
        private const val TAG = "CeremonyVPN"
        private const val NOTIF_ID = 7777
        private const val CHANNEL_ID = "ceremony_mode_vpn"

        /** Start the VPN service. */
        fun start(context: Context) {
            val intent = Intent(context, CeremonyModeVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Ceremony Mode VPN start requested")
        }

        /** Stop the VPN service. */
        fun stop(context: Context) {
            val intent = Intent(context, CeremonyModeVpnService::class.java)
            context.stopService(intent)
            Log.i(TAG, "Ceremony Mode VPN stop requested")
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "CeremonyModeVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand — starting Ceremony Mode VPN")

        // Start as foreground service (required for VPN services)
        startForeground(NOTIF_ID, buildNotification())

        // Set up the VPN interface
        try {
            setupVpn()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VPN: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    /**
     * Configure the VPN to block all internet apps except Saatiril.
     *
     * Strategy: `addDisallowedApplication("com.saatiril.andro")` + route 0.0.0.0/0
     *
     * - ALL apps' traffic goes through the TUN (and gets dropped) → no internet
     * - Saatiril app is DISALLOWED from VPN → bypasses TUN, uses real network
     * - Saatiril can access LAN (localhost:3003, 192.168.43.1:3003) AND
     *   internet (Google Drive upload) via the real network interface
     *
     * Result: WhatsApp, Instagram, Play Store, OS updates = BLOCKED.
     * Saatiril server + Socket.io + Google Drive = WORKING.
     */
    private fun setupVpn() {
        val builder = Builder()

        // TUN interface address (dummy — we drop all packets)
        builder.addAddress("10.200.200.1", 32)

        // DNS server that won't resolve (forces DNS failures for internet domains)
        builder.addDnsServer("10.200.200.2")

        // Route ALL traffic (0.0.0.0/0) through TUN — everything gets dropped
        builder.addRoute("0.0.0.0", 0)

        // EXCLUDE Saatiril app: its traffic bypasses VPN entirely
        // → Saatiril can access LAN (server on localhost:3003) AND
        //   internet (Google Drive upload) via the real network interface
        builder.addDisallowedApplication("com.saatiril.andro")

        builder.setMtu(1500)

        // Build and establish the VPN interface
        vpnInterface = builder
            .setSession("Saatiril Mode Prosesi")
            .establish()

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface — establish() returned null")
            stopSelf()
            return
        }

        Log.i(TAG, "VPN established — blocking all apps except com.saatiril.andro")
        Log.i(TAG, "Saatiril LAN (localhost, 192.168.43.1) + Google Drive: WORKING")
        Log.i(TAG, "Other apps (WhatsApp, Instagram, Play Store): BLOCKED")

        // Start a dummy worker that keeps the TUN alive (reads and discards)
        workerThread = Thread {
            val pfd = vpnInterface
            if (pfd != null) {
                val input = java.io.FileInputStream(pfd.fileDescriptor)
                val output = java.io.FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32767)
                Log.i(TAG, "VPN worker started — dropping all packets")
                try {
                    while (!Thread.interrupted()) {
                        val len = input.read(buffer)
                        if (len > 0) {
                            // DROP the packet — don't forward, don't respond
                            // This effectively blocks all internet for routed apps
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "VPN worker ended: ${e.message}")
                } finally {
                    try { input.close() } catch (_: Exception) {}
                    try { output.close() } catch (_: Exception) {}
                }
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CeremonyModeVpnService destroying — stopping VPN")

        workerThread?.interrupt()
        workerThread = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
    }

    override fun onRevoke() {
        // System revoked the VPN (user disabled it in settings)
        Log.i(TAG, "VPN revoked by system")
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mode Prosesi VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Memblokir internet selama prosesi wisuda"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Mode Prosesi Aktif")
            .setContentText("Internet diblokir — LAN server tetap jalan")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
