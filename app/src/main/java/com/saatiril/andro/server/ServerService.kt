package com.saatiril.andro.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.saatiril.andro.MainActivity
import com.saatiril.andro.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps [SaatirilServer] alive while the admin is
 * running a live ceremony. Android will kill a background socket server
 * within seconds; a foreground service with a persistent notification is
 * the only reliable way to keep the LAN hub running.
 *
 * The service owns no state itself — [SaatirilServer] is a singleton. The
 * service just:
 *  1. Promotes itself to foreground (with a notification).
 *  2. Keeps the process alive.
 *  3. Updates the notification text when the client count changes.
 *
 * Lifecycle is driven by [com.saatiril.andro.data.AdminViewModel]:
 *   startServer() → ContextCompat.startForegroundService(ServerService)
 *   stopServer()  → ServerService.stopSelf()  (via ACTION_STOP)
 */
class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "saatiril_server"
        private const val NOTIF_ID = 3003

        const val ACTION_START = "com.saatiril.andro.server.START"
        const val ACTION_STOP = "com.saatiril.andro.server.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statsJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "STOP requested")
                SaatirilServer.stop()
                statsJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                Log.i(TAG, "START — promoting to foreground")
                createNotificationChannel()
                startForeground(NOTIF_ID, buildNotification("Saatiril server berjalan…", 0))
                // Observe client count → update notification
                statsJob?.cancel()
                statsJob = scope.launch {
                    SaatirilServer.clients.collect { clients ->
                        val authCount = clients.count { it.authenticated }
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIF_ID, buildNotification("Saatiril server aktif • $authCount klien terhubung", authCount))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        statsJob?.cancel()
        SaatirilServer.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Saatiril Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga server LAN Saatiril tetap berjalan saat upacara"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, clientCount: Int): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ServerService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SAATIRIL")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Hentikan Server", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
