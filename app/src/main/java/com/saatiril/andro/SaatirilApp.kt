package com.saatiril.andro

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Application class with global crash protection.
 *
 * CRITICAL: The uncaught exception handler prevents silent crashes.
 * Many Android crashes (NoSuchMethodError, NoClassDefFoundError, etc.)
 * can be caught here and turned into user-visible error messages
 * instead of killing the app process.
 */
class SaatirilApp : Application() {

    companion object {
        private const val TAG = "SaatirilApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Set up global uncaught exception handler to prevent silent crashes.
        // For non-fatal errors on background threads (coroutine exceptions from
        // ktor, etc.), we log but DON'T kill the app — only main-thread errors
        // are passed to the default handler (which shows the crash dialog).
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===")
            Log.e(TAG, "Type: ${throwable.javaClass.simpleName}")
            Log.e(TAG, "Message: ${throwable.message}")
            Log.e(TAG, "Full stacktrace:", throwable)

            val isMainThread = thread == Looper.getMainLooper().thread

            if (isMainThread) {
                // Main thread errors are fatal — show toast + pass to default handler
                try {
                    Toast.makeText(
                        this,
                        "Saatiril error: ${throwable.javaClass.simpleName}: ${throwable.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) { }
                defaultHandler?.uncaughtException(thread, throwable)
            } else {
                // Background thread (ktor coroutine, IO, etc.) — log and swallow.
                // Killing the app from a background thread exception is worse than
                // continuing (the server might still work, the user can retry).
                Log.w(TAG, "Background exception swallowed (app kept alive): ${throwable.message}")
            }
        }
    }
}
