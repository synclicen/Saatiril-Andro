package com.saatiril.andro.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ═════════════════════════════════════════════════════════════════════════
 * UVCCamera Manager — v18 Hardened (MacroSilicon Black Screen Fix)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * BUG FIXES from v17 → v18:
 *
 * BUG #1 (CRASH): setBandwidthFactor(1.0f) DOES NOT EXIST in org.uvccamera:lib!
 *   → Use setPreviewSize(w, h, minFps, maxFps, format, bandwidthFactor) instead
 *   → This was causing compilation error or NoSuchMethodError at runtime
 *
 * BUG #2 (DEVICE NOT DETECTED): MacroSilicon VID in device_filter.xml was 3141
 *   → Should be 13407 (0x345F in decimal). 3141 = 0xC45 ≠ MacroSilicon
 *   → Fixed in device_filter.xml
 *
 * BUG #3 (DOUBLE SURFACE): Called both setPreviewTexture() and setPreviewDisplay()
 *   → These conflict. Use setPreviewDisplay(Surface) only for TextureView
 *   → Create Surface from SurfaceTexture, pass to setPreviewDisplay()
 *
 * BUG #4 (THREAD VIOLATION): setupPreviewSurface called from background thread
 *   → Surface/UI operations MUST be on main thread
 *   → Use Handler(Looper.getMainLooper()).post() for surface setup
 *
 * BUG #5 (RECOMPOSITION): AndroidView factory re-creates TextureView
 *   → Added remember + key to prevent unnecessary recreation
 *
 * FIX #1: FORCE MJPEG + LOCK 720p (MacroSilicon freezes on YUYV)
 * FIX #2: Bandwidth factor via setPreviewSize overload (1.0f = full)
 * FIX #3: Proper preview surface — setPreviewDisplay(Surface) then startPreview()
 */
class UVCCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UVCCameraManager"

        // ═══════════════════════════════════════════════════════════
        // FIX #1: Resolution, Format, FPS for MacroSilicon
        // MUST use MJPEG, MUST start at 720p, MUST cap at 30fps
        // ═══════════════════════════════════════════════════════════
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val PREVIEW_MIN_FPS = 1
        private const val PREVIEW_MAX_FPS = 30
        private const val PREVIEW_FORMAT = UVCCamera.FRAME_FORMAT_MJPEG  // NEVER YUYV!

        // ═══════════════════════════════════════════════════════════
        // FIX #2: Bandwidth Factor for MacroSilicon
        // Passed as 6th param to setPreviewSize(w, h, minFps, maxFps, format, bandwidth)
        // 1.0f = full bandwidth. If still black, try 0.5f.
        // ═══════════════════════════════════════════════════════════
        private const val BANDWIDTH_FACTOR = 1.0f

        // Known UVC Vendor IDs (decimal values)
        private const val VENDOR_MACROSILICON = 0x345F  // 13407 decimal — JASOZ capture card
        private const val VENDOR_FUSHICAI = 0x0489       // 1161 decimal
        private const val VENDOR_MAGEWELL = 0x0416       // 1046 decimal

        const val ACTION_USB_PERMISSION = "com.saatiril.andro.USB_PERMISSION"
    }

    // ─── State Flows ────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private val _isUSBCamera = MutableStateFlow(false)
    val isUSBCamera: StateFlow<Boolean> = _isUSBCamera.asStateFlow()

    // ─── Negotiated preview size (observable for Matrix re-application) ───
    // The Compose UI observes this StateFlow so the FILL Matrix is re-applied
    // automatically when the camera connects and the real preview size is known.
    // Without this, the Matrix `update` block would only run on initial composition
    // (when negotiatedWidth/Height are still the defaults) and never re-run when
    // the camera actually negotiates a (possibly different) size → letterbox/pillarbox
    // black bars appear inside the PNG frame.
    private val _negotiatedSize = MutableStateFlow(PREVIEW_WIDTH to PREVIEW_HEIGHT)
    val negotiatedSizeFlow: StateFlow<Pair<Int, Int>> = _negotiatedSize.asStateFlow()

    // ─── Project aspect ratio (w/h) ─────────────────────────────
    // Set by AdminOperatorScreen so the UVC negotiation can prefer resolutions
    // matching the project's selected ratio (e.g. 4:3 → try 1280x960, 640x480).
    // This avoids the DSLR/capture-card embedding black bars (e.g. a 4:3 sensor
    // output stretched into a 16:9 HDMI frame with pillarbox bars) which would
    // otherwise show through the PNG frame's transparent center.
    @Volatile private var projectAspectRatio: Float = 4f / 3f

    /** Set the project's aspect ratio so UVC preview negotiation can prefer
     *  matching resolutions. Call this before the camera opens. */
    fun setProjectAspectRatio(ratio: Float) {
        projectAspectRatio = ratio
        Log.i(TAG, "UVC project aspect ratio set: $ratio")
    }

    // ─── UVC Internals ──────────────────────────────────────────
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var isRegistered = false
    private var currentUsbDevice: UsbDevice? = null
    private var currentCtrlBlock: UsbControlBlock? = null

    // ─── Preview Surface ────────────────────────────────────────
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null

    // ─── Background Handler ─────────────────────────────────────
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── Capture ────────────────────────────────────────────────
    private var captureCallback: ((String?) -> Unit)? = null

    /** Actual negotiated preview size — may differ from PREVIEW_WIDTH/HEIGHT
     *  when the 640x480 fallback was used. IFrameCallback NV21 frames MUST be
     *  decoded with these dimensions (using the constants corrupted captures). */
    @Volatile var negotiatedWidth = PREVIEW_WIDTH  // public for Matrix fit calc
    @Volatile var negotiatedHeight = PREVIEW_HEIGHT  // public for Matrix fit calc

    // ─── USB Permission ─────────────────────────────────────────
    private var usbPermissionReceiver: BroadcastReceiver? = null

    // ─── Discovered Devices ─────────────────────────────────────
    private val discoveredDevices = mutableMapOf<String, UsbDevice>()

    // ─── State tracking ─────────────────────────────────────────
    private var pendingSurfaceSetup = false
    private var isInitialized = false
    private var isDestroying = false

    // ─── Open/close serialization ───────────────────────────────
    // cameraLock guards the `uvcCamera` field so closeUVCCamera (which may
    // run on any thread — disconnect callback, watchdog, forceReconnect)
    // can't null uvcCamera while openUVCCamera is mid-flight between
    // "uvcCamera = camera" and "post setupPreviewSurface". Java/Kotlin
    // monitors are reentrant, so closeUVCCamera called from within
    // openUVCCamera (which may hold the lock) is safe.
    private val cameraLock = Any()

    // ─── camera.open() timeout ──────────────────────────────────
    // CRITICAL (preview-not-showing fix): camera.open(ctrlBlock) is a NATIVE
    // USB call that can block indefinitely when the USB endpoint is in a bad
    // state (common after hot-plug races). Previously it ran on the
    // backgroundHandler thread — when it blocked, the ENTIRE background
    // thread was stuck (couldn't process close, reconnect, polling, etc.)
    // → permanent "idle" state where the camera is detected/selected but
    // the preview never appears.
    //
    // Fix: run camera.open() + setPreviewSize() on a FRESH daemon thread per
    // open (via FutureTask), with a hard timeout. futureTask.get(TIMEOUT)
    // bounds the block on the backgroundHandler to 8s max. On timeout, we
    // abort the open, free the background thread, and let the watchdog/user
    // retry. The stuck thread becomes an orphan daemon (cleaned up on process
    // exit). Using a FRESH thread per open (not a shared single-thread
    // executor) ensures a stuck native call doesn't block subsequent retry
    // attempts — each open gets its own thread.
    private val CAMERA_OPEN_TIMEOUT_MS = 8000L

    // Guards forceReconnect against overlapping calls (the watchdog fires
    // every 5s; without this, concurrent forceReconnect calls would each
    // spawn a new background thread while the old one is stuck, piling up
    // threads that all contend for the same USB endpoint).
    @Volatile private var isReconnecting = false

    // ── Android 14+ USB polling fallback ──
    // On Android 14 (API 34), the USBMonitor library's internal registerReceiver()
    // may fail with SecurityException (missing RECEIVER_EXPORTED flag).
    // This polling mechanism detects USB devices as a fallback.
    private var usbPollingRunnable: Runnable? = null
    private val USB_POLLING_INTERVAL_MS = 2000L // Check every 2 seconds

    /**
     * Initialize the UVC camera system.
     * Called once when the Activity is created.
     * Guard against multiple calls.
     */
    fun initCamera() {
        if (isInitialized) {
            Log.i(TAG, "initCamera: already initialized, skipping")
            return
        }
        isInitialized = true
        isDestroying = false

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: v18 UVCCamera Direct (MacroSilicon Fix)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        startBackgroundThread()
        registerUsbMonitor()
    }

    // ═══════════════════════════════════════════════════════════════
    // Background Thread (UVC open/close MUST be on background thread)
    // ═══════════════════════════════════════════════════════════════

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("UVCameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.i(TAG, "Background thread started")
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            // Best-effort join only — a long join on the main thread risks ANR.
            backgroundThread?.join(100)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
        backgroundThread = null
        backgroundHandler = null
    }

    // ═══════════════════════════════════════════════════════════════
    // USB Monitor — Detect UVC device attach/detach
    // ═══════════════════════════════════════════════════════════════

    private fun registerUsbMonitor() {
        if (isRegistered) {
            Log.i(TAG, "USBMonitor already registered, skipping")
            return
        }

        try {
            if (usbMonitor == null) {
                usbMonitor = USBMonitor(context, object : OnDeviceConnectListener {
                    override fun onAttach(device: UsbDevice) {
                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        Log.i(TAG, "USB ATTACHED: ${device.deviceName}, vid=0x${Integer.toHexString(device.vendorId)}, pid=0x${Integer.toHexString(device.productId)}")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        if (isUVCDevice(device)) {
                            Log.i(TAG, "★ UVC VIDEO DEVICE detected! Requesting permission...")
                            discoveredDevices[device.deviceName] = device
                            updateAvailableCameras()
                            requestUsbPermission(device)
                        } else {
                            Log.i(TAG, "Not a UVC device, ignoring")
                        }
                    }

                    override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
                        Log.i(TAG, "USB DISCONNECTED: ${device.deviceName}")
                        handleDeviceDisconnect(device)
                    }

                    override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        Log.i(TAG, "USB CONNECTED (permission granted): ${device.deviceName}")
                        Log.i(TAG, "  createNew=$createNew")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        // Already connected to this exact device? Skip the
                        // open. (Both USBMonitor.onConnect and
                        // tryOpenUVCCameraDirect can fire for the same device
                        // — the "already connected" check deduplicates without
                        // blocking legitimate retries after a failed open.)
                        if (currentUsbDevice?.deviceName == device.deviceName && _isConnected.value) {
                            Log.i(TAG, "onConnect: already connected to ${device.deviceName} — skipping")
                            return
                        }

                        currentUsbDevice = device
                        currentCtrlBlock = ctrlBlock

                        // Open UVC camera on background thread. openUVCCamera
                        // is self-deduplicating (already-connected check +
                        // identity check in setupPreviewSurface), so multiple
                        // posts for the same device are safe — only the
                        // winning open's preview setup takes effect.
                        backgroundHandler?.post {
                            openUVCCamera(device, ctrlBlock)
                        }
                    }

                    // NOTE: onDettach (double-t) is the correct spelling in this library
                    override fun onDettach(device: UsbDevice) {
                        Log.i(TAG, "USB DETACHED: ${device.deviceName}")
                        discoveredDevices.remove(device.deviceName)
                        updateAvailableCameras()
                        handleDeviceDisconnect(device)
                    }

                    override fun onCancel(device: UsbDevice) {
                        Log.i(TAG, "USB permission CANCELLED for: ${device.deviceName}")
                    }
                })
            }

            usbMonitor?.register()
            isRegistered = true
            Log.i(TAG, "USBMonitor registered successfully")

            // Register BroadcastReceiver for USB permission results
            registerUsbPermissionReceiver()

            // Enumerate already-connected USB devices
            enumerateConnectedDevices()

            // ── Android 14+ USB polling fallback ──
            // The USBMonitor library may fail to register its internal
            // BroadcastReceiver on Android 14+ (SecurityException due to
            // missing RECEIVER_EXPORTED flag). This polling mechanism
            // detects USB devices as a fallback by checking UsbManager.deviceList
            // every 2 seconds. It's a no-op if USBMonitor is working correctly
            // (devices already discovered are skipped).
            startUsbPolling()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USBMonitor: ${e.message}", e)
        }
    }

    /**
     * Enumerate USB devices that are already connected when app starts.
     */
    private fun enumerateConnectedDevices() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.e(TAG, "UsbManager not available for enumeration")
                return
            }

            for (device in usbManager.deviceList.values) {
                Log.i(TAG, "Enumerating: ${device.deviceName} vid=0x${Integer.toHexString(device.vendorId)} pid=0x${Integer.toHexString(device.productId)}")
                if (isUVCDevice(device)) {
                    Log.i(TAG, "★ Already-connected UVC device found: ${device.deviceName}")
                    discoveredDevices[device.deviceName] = device
                    if (!usbManager.hasPermission(device)) {
                        requestUsbPermission(device)
                    } else {
                        // Permission already granted — on Android 14+, USBMonitor's
                        // onConnect may not fire, so open camera directly
                        tryOpenUVCCameraDirect(device)
                    }
                }
            }
            updateAvailableCameras()
        } catch (e: Exception) {
            Log.e(TAG, "Device enumeration failed: ${e.message}")
        }
    }

    private fun unregisterUsbMonitor() {
        if (!isRegistered) return
        try {
            usbMonitor?.unregister()
            isRegistered = false
            Log.i(TAG, "USBMonitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister USBMonitor: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Android 14+ USB Polling Fallback
    // ═══════════════════════════════════════════════════════════════
    // On Android 14 (API 34), the USBMonitor library's internal
    // registerReceiver() may throw SecurityException due to missing
    // RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED flag. This causes
    // USB device detection to silently fail — no onAttach callback,
    // no permission dialog, no camera detected.
    //
    // This polling mechanism checks UsbManager.deviceList every 2
    // seconds as a fallback. It's a no-op if USBMonitor is working
    // correctly (devices already discovered are skipped).
    // ═══════════════════════════════════════════════════════════════

    private fun startUsbPolling() {
        stopUsbPolling() // Clear any existing polling
        usbPollingRunnable = object : Runnable {
            override fun run() {
                if (isDestroying) return
                try {
                    pollUsbDevices()
                } catch (e: Exception) {
                    Log.e(TAG, "USB polling error: ${e.message}")
                }
                // Schedule next poll on the BACKGROUND thread (not mainHandler).
                // pollUsbDevices() iterates usbManager.deviceList (IPC to the USB
                // service) and may call requestUsbPermission / tryOpenUVCCameraDirect
                // — none of that belongs on the main thread. Running it on mainHandler
                // contributed to ANR when the USB service was slow to respond during
                // hot-plug.
                usbPollingRunnable?.let {
                    backgroundHandler?.postDelayed(it, USB_POLLING_INTERVAL_MS)
                }
            }
        }
        // Start polling after a short delay on the background thread.
        usbPollingRunnable?.let { backgroundHandler?.postDelayed(it, USB_POLLING_INTERVAL_MS) }
        Log.i(TAG, "USB polling fallback started (every ${USB_POLLING_INTERVAL_MS}ms, on background thread)")
    }

    private fun stopUsbPolling() {
        usbPollingRunnable?.let { backgroundHandler?.removeCallbacks(it); mainHandler.removeCallbacks(it) }
        usbPollingRunnable = null
    }

    /**
     * Poll UsbManager.deviceList for new UVC devices.
     * This is a fallback for Android 14+ where USBMonitor's BroadcastReceiver
     * may fail to register. It's idempotent — skips already-discovered devices.
     */
    private fun pollUsbDevices() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            var newDeviceFound = false

            for (device in usbManager.deviceList.values) {
                if (discoveredDevices.containsKey(device.deviceName)) continue // Already known

                if (isUVCDevice(device)) {
                    Log.i(TAG, "★ Polling found NEW UVC device: ${device.deviceName} vid=0x${Integer.toHexString(device.vendorId)}")
                    discoveredDevices[device.deviceName] = device
                    newDeviceFound = true

                    // Request permission if not already granted
                    if (!usbManager.hasPermission(device)) {
                        requestUsbPermission(device)
                    } else {
                        // Permission already granted — try to open the camera
                        // The USBMonitor's onConnect won't fire if it's broken,
                        // so we need to manually trigger the camera open
                        tryOpenUVCCameraWithPermission(device)
                    }
                }
            }

            // Check for disconnected devices (plugged out since last poll)
            val connectedNames = usbManager.deviceList.keys
            val removedKeys = discoveredDevices.keys.filter { it !in connectedNames }
            for (key in removedKeys) {
                val device = discoveredDevices.remove(key)
                if (device != null) {
                    Log.i(TAG, "Polling detected USB DISCONNECT: ${device.deviceName}")
                    handleDeviceDisconnect(device)
                    newDeviceFound = true
                }
            }

            if (newDeviceFound) {
                updateAvailableCameras()
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB polling failed: ${e.message}")
        }
    }

    /**
     * Try to open a UVC camera when permission is already granted.
     * This is needed when the USBMonitor's onConnect callback doesn't fire
     * (e.g., on Android 14 where the library's BroadcastReceiver fails).
     */
    private fun tryOpenUVCCameraWithPermission(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) return

            // On Android 14+, USBMonitor's onConnect may never fire because
            // the library's internal registerReceiver() throws SecurityException.
            // Try direct open first (bypasses USBMonitor entirely).
            tryOpenUVCCameraDirect(device)
        } catch (e: Exception) {
            Log.e(TAG, "tryOpenUVCCameraWithPermission error: ${e.message}")
        }
    }

    /**
     * Directly open a UVC camera when USB permission is already granted.
     * On Android 14+, USBMonitor's internal BroadcastReceiver may fail due to
     * missing RECEIVER_EXPORTED flag. This method uses USBMonitor.requestPermission()
     * on an already-permitted device, which internally calls processConnect() → onConnect()
     * WITHOUT needing the BroadcastReceiver. This is the key workaround for Android 14.
     */
    private fun tryOpenUVCCameraDirect(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) return

            // Don't open if already connected to this device. (Removed the
            // isOpening hard-guard: it blocked legitimate retries when a
            // previous camera.open() was stuck in native code — the guard
            // stayed true forever and the camera never recovered. Now we
            // allow the retry; openUVCCamera's already-connected check +
            // setupPreviewSurface's identity check handle dedup.)
            if (currentUsbDevice?.deviceName == device.deviceName && _isConnected.value) {
                Log.i(TAG, "tryOpenUVCCameraDirect: already connected to ${device.deviceName}")
                return
            }

            val monitor = usbMonitor
            if (monitor != null) {
                // KEY INSIGHT: USBMonitor.requestPermission() on an already-permitted device
                // calls processConnect() internally, which creates a UsbControlBlock and
                // triggers onConnect(). This works even when the BroadcastReceiver is broken
                // on Android 14 because processConnect() doesn't need the BroadcastReceiver.
                Log.i(TAG, "tryOpenUVCCameraDirect: calling monitor.requestPermission() for already-permitted device")
                backgroundHandler?.post {
                    try {
                        monitor.requestPermission(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "USBMonitor.requestPermission() failed on already-permitted device: ${e.message}")
                        // Last resort: try to force open via UsbManager.openDevice()
                        tryForceOpenViaUsbManager(device)
                    }
                }
            } else {
                Log.e(TAG, "tryOpenUVCCameraDirect: No USBMonitor available")
                tryForceOpenViaUsbManager(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryOpenUVCCameraDirect error: ${e.message}")
        }
    }

    /**
     * Last-resort fallback: try to open camera by registering a fresh USBMonitor
     * and requesting permission. This is for the case where USBMonitor is completely
     * broken on Android 14 MIUI.
     */
    private fun tryForceOpenViaUsbManager(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) return

            // Don't open if already connected to this device
            if (currentUsbDevice?.deviceName == device.deviceName && _isConnected.value) {
                return
            }

            Log.i(TAG, "tryForceOpenViaUsbManager: Attempting re-registration of USBMonitor")
            // Try re-registering the USBMonitor — this might work on second attempt
            try {
                unregisterUsbMonitor()
                isRegistered = false
                registerUsbMonitor()
            } catch (e: Exception) {
                Log.e(TAG, "Re-registration failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryForceOpenViaUsbManager error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // USB Permission — Required for UVC device access
    // ═══════════════════════════════════════════════════════════════

    private fun registerUsbPermissionReceiver() {
        if (usbPermissionReceiver != null) return

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        Log.i(TAG, "USB permission result: device=${device?.deviceName}, granted=$granted")

                        if (granted && device != null) {
                            Log.i(TAG, "USB permission GRANTED for ${device.deviceName}")
                            // BUG FIX: On Android 14+, USBMonitor's onConnect may never fire
                            // because the library's internal registerReceiver() throws
                            // SecurityException. We MUST open the camera directly here.
                            tryOpenUVCCameraDirect(device)
                        } else if (device != null) {
                            Log.w(TAG, "USB permission DENIED for ${device.deviceName}")
                        } else {
                            // No device in intent — try to find device from polling
                            Log.w(TAG, "USB permission result: no device in intent extras")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // ── Android 14+ (API 34) requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED ──
        // Without this flag, registerReceiver() throws SecurityException on Android 14+,
        // which silently prevents USB permission dialogs from working.
        // This receiver is NOT exported (only receives our own PendingIntent broadcasts).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        Log.i(TAG, "USB permission BroadcastReceiver registered")
    }

    private fun unregisterUsbPermissionReceiver() {
        try {
            usbPermissionReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        usbPermissionReceiver = null
    }

    // ═══════════════════════════════════════════════════════════════
    // USB Device Detection — Identify UVC video devices
    // ═══════════════════════════════════════════════════════════════

    private fun isUVCDevice(device: UsbDevice): Boolean {
        // Check by interface class (UVC = Video class)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // USB Video Class: Class 14 (0x0E) or Class 239 (0xEF) Subclass 2
            if (iface.interfaceClass == 14 || iface.interfaceClass == 239) {
                Log.i(TAG, "UVC device detected by interface class: ${iface.interfaceClass}")
                return true
            }
        }

        // Check by known Vendor IDs
        val knownVids = intArrayOf(VENDOR_MACROSILICON, VENDOR_FUSHICAI, VENDOR_MAGEWELL)
        if (knownVids.contains(device.vendorId)) {
            Log.i(TAG, "UVC device detected by known VendorID: 0x${Integer.toHexString(device.vendorId)}")
            return true
        }

        // Heuristic: class 239 subclass 2
        if (device.interfaceCount >= 2 && device.vendorId != 0) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 239 && iface.interfaceSubclass == 2) {
                    Log.i(TAG, "UVC device detected by class/subclass: 239/2")
                    return true
                }
            }
        }

        return false
    }

    private fun updateAvailableCameras() {
        val cameras = mutableListOf<Pair<String, String>>()

        for ((name, device) in discoveredDevices) {
            val vid = device.vendorId
            val label = when (vid) {
                VENDOR_MACROSILICON -> "USB: MacroSilicon Capture (0x${Integer.toHexString(vid)})"
                VENDOR_FUSHICAI -> "USB: Fushicai Capture (0x${Integer.toHexString(vid)})"
                VENDOR_MAGEWELL -> "USB: Magewell Capture (0x${Integer.toHexString(vid)})"
                else -> "USB: UVC Device (0x${Integer.toHexString(vid)})"
            }
            cameras.add(name to label)
        }

        _availableCameras.value = cameras
        Log.i(TAG, "Available cameras updated: ${cameras.size} devices")
    }

    // ═══════════════════════════════════════════════════════════════
    // USB Permission Request
    // ═══════════════════════════════════════════════════════════════

    private fun requestUsbPermission(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.e(TAG, "UsbManager not available!")
                return
            }

            if (usbManager.hasPermission(device)) {
                Log.i(TAG, "USB permission already granted for ${device.deviceName}")
                // On Android 14+, USBMonitor's onConnect may not fire due to
                // registerReceiver() SecurityException. Open camera directly.
                tryOpenUVCCameraDirect(device)
                return
            }

            // FIX: Add FLAG_UPDATE_CURRENT to ensure PendingIntent contains correct
            // UsbDevice extras. Without it, MIUI may cache a stale PendingIntent
            // without the device, causing the permission dialog to not appear.
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, device.deviceName.hashCode(),
                Intent(ACTION_USB_PERMISSION).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                },
                flags
            )

            usbManager.requestPermission(device, pendingIntent)
            Log.i(TAG, "USB permission requested for ${device.deviceName}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ★★★ OPEN UVC CAMERA — WITH ALL MACROSILICON FIXES ★★★
    // ═══════════════════════════════════════════════════════════════

    private fun openUVCCamera(device: UsbDevice, ctrlBlock: UsbControlBlock) {
        if (isDestroying) {
            Log.w(TAG, "openUVCCamera: skipping, manager is destroying")
            return
        }

        // Already connected to this device? Skip. (This deduplicates the
        // common double-fire: USBMonitor.onConnect + tryOpenUVCCameraDirect.)
        if (_isConnected.value && currentUsbDevice?.deviceName == device.deviceName) {
            Log.i(TAG, "openUVCCamera: already connected to ${device.deviceName} — skipping")
            return
        }

        // Close any existing camera before opening the new one.
        closeUVCCamera()

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "openUVCCamera: ${device.deviceName}")
        Log.i(TAG, "  VID=0x${Integer.toHexString(device.vendorId)} PID=0x${Integer.toHexString(device.productId)}")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        val camera = UVCCamera()
        synchronized(cameraLock) {
            uvcCamera = camera
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 1 + 2: Open the camera device + negotiate preview size.
        // Run on a FRESH daemon thread via FutureTask, with a hard timeout.
        // camera.open() is a native USB control transfer that can block
        // indefinitely when the USB endpoint is in a bad state (common after
        // hot-plug). Running it directly on the backgroundHandler would
        // freeze the handler forever → no close, no reconnect, no polling →
        // permanent "idle" state. The FutureTask isolates the block;
        // futureTask.get(TIMEOUT) bounds it to 8s. On timeout we abort +
        // close, freeing the backgroundHandler for retry. Using a FRESH
        // thread per open (not a shared executor) ensures a stuck native
        // call doesn't queue-block subsequent retries.
        // ═══════════════════════════════════════════════════════════════
        val openTask = FutureTask<Boolean> {
            // STEP 1: Open the camera device (native USB — may block)
            camera.open(ctrlBlock)
            Log.i(TAG, "★ UVCCamera opened successfully (native open returned)")

            // STEP 2: Negotiate preview size (also native USB — may block)
            // ASPECT-RATIO-AWARE: prefer resolutions matching the project's
            // aspect ratio (4:3 → 1280x960 / 1024x768 / 640x480; 16:9 →
            // 1280x720 / 1920x1080), then fall back to 640x480.
            val candidateSizes = buildCandidateSizes(projectAspectRatio)
            var previewSet = false
            for ((w, h, label) in candidateSizes) {
                try {
                    Log.i(TAG, "★ Trying setPreviewSize(${w}x${h}, ${PREVIEW_MIN_FPS}-${PREVIEW_MAX_FPS}fps, MJPEG, bw=${BANDWIDTH_FACTOR}) [$label]")
                    camera.setPreviewSize(w, h, PREVIEW_MIN_FPS, PREVIEW_MAX_FPS, PREVIEW_FORMAT, BANDWIDTH_FACTOR)
                    negotiatedWidth = w
                    negotiatedHeight = h
                    _negotiatedSize.value = w to h
                    Log.i(TAG, "★ Preview size set: ${w}x${h} MJPEG bw=${BANDWIDTH_FACTOR} [$label]")
                    previewSet = true
                    break
                } catch (e1: Exception) {
                    Log.w(TAG, "${w}x${h} MJPEG bw failed: ${e1.message}, trying without bw param...")
                    try {
                        camera.setPreviewSize(w, h, PREVIEW_FORMAT)
                        negotiatedWidth = w
                        negotiatedHeight = h
                        _negotiatedSize.value = w to h
                        Log.i(TAG, "★ Preview size set: ${w}x${h} MJPEG (no bw) [$label]")
                        previewSet = true
                        break
                    } catch (e2: Exception) {
                        Log.w(TAG, "${w}x${h} MJPEG failed: ${e2.message}, trying next candidate...")
                    }
                }
            }
            if (!previewSet) {
                // Last-resort fallback: 640x480 (universally supported by UVC).
                camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG)
                negotiatedWidth = 640
                negotiatedHeight = 480
                _negotiatedSize.value = 640 to 480
                Log.i(TAG, "★ Preview size set: 640x480 MJPEG (last-resort fallback)")
            }
            true
        }
        // Start on a fresh daemon thread so a stuck native call doesn't
        // block subsequent retries (each open gets its own thread).
        Thread(openTask, "UVCameraOpen").apply { isDaemon = true }.start()

        try {
            openTask.get(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // camera.open() or setPreviewSize() hung in native code.
            // Abort the open — the backgroundHandler is now free to process
            // the next message (watchdog reconnect, user retry, etc.).
            // The stuck open thread continues as an orphan daemon; it holds
            // the old ctrlBlock but we've nulled uvcCamera, so it can't
            // interfere with a fresh open on a new ctrlBlock + new thread.
            Log.e(TAG, "⚠ camera.open()/setPreviewSize() TIMED OUT after ${CAMERA_OPEN_TIMEOUT_MS}ms — " +
                "USB endpoint stuck. Aborting open (background thread freed for retry).")
            openTask.cancel(true) // best-effort interrupt (native may ignore)
            closeUVCCamera()
            return
        } catch (e: ExecutionException) {
            Log.e(TAG, "Failed to open UVCCamera: ${e.cause?.message ?: e.message}", e)
            closeUVCCamera()
            return
        } catch (e: Exception) {
            Log.e(TAG, "openUVCCamera unexpected error: ${e.message}", e)
            closeUVCCamera()
            return
        }

        // ═════════════════════════════════════════════════════
        // STEP 3: Set the PREVIEW SURFACE + startPreview.
        // Posted to the MAIN thread (surface ops must be on main). The
        // identity check inside setupPreviewSurface ensures that if a 2nd
        // open replaced uvcCamera between this post and execution, this
        // (stale) preview setup bails instead of calling startPreview on
        // a destroyed camera.
        // ═════════════════════════════════════════════════════
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            mainHandler.post {
                setupPreviewSurface(camera, tv)
            }
        } else {
            Log.i(TAG, "TextureView not ready yet — will set up surface when available")
            pendingSurfaceSetup = true
        }

        // Update state
        synchronized(cameraLock) {
            _isConnected.value = true
            _currentCameraId.value = device.deviceName
            _isUSBCamera.value = true
            _cameraType.value = "external"
        }

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "★ UVC CAMERA READY — waiting for surface to start preview")
        Log.i(TAG, "═══════════════════════════════════════════════════")
    }

    /**
     * Build the list of candidate preview sizes to try, ordered by:
     *   1. Resolutions matching the project's aspect ratio (largest first)
     *   2. The legacy 1280x720 default (for backwards compat with capture cards
     *      that only support 16:9)
     *
     * Each entry is (width, height, label) where label is used for logging.
     *
     * Why this matters: a 4:3 DSLR sensor output is often embedded in a 16:9
     * HDMI frame by the camera (with black pillarbox bars on left/right).
     * If we negotiate 16:9 for a 4:3 project, those embedded black bars show
     * through the PNG frame's transparent center. By preferring 4:3 resolutions
     * (1280x960, 1024x768, 640x480) for 4:3 projects, we get a clean 4:3 frame
     * with no embedded black bars. The FILL Matrix in AdminOperatorScreen
     * provides defense-in-depth by cropping any residual bars.
     */
    private fun buildCandidateSizes(targetRatio: Float): List<Triple<Int, Int, String>> {
        val candidates = mutableListOf<Triple<Int, Int, String>>()

        // Resolutions matching the project's aspect ratio (largest first).
        // We include several alternatives because capture cards vary in what
        // they support — if 1280x960 fails, 1024x768 may succeed, etc.
        if (targetRatio >= 1.3f && targetRatio <= 1.4f) {
            // 4:3 landscape (1.333) — HD+ candidates for crisp graduation photos.
            // 1920x1440 = full HD-height 4:3 (2.76 MP). Most UVC capture cards
            // that output 1080p HDMI also accept 1920x1440 MJPEG. If the card
            // rejects it, fall back to 1600x1200 (2MP), then 1280x960, etc.
            candidates.add(Triple(1920, 1440, "4:3 1920x1440 (HD+)"))
            candidates.add(Triple(1600, 1200, "4:3 1600x1200 (2MP)"))
            candidates.add(Triple(1280, 960, "4:3 1280x960"))
            candidates.add(Triple(1024, 768, "4:3 1024x768"))
            candidates.add(Triple(800, 600, "4:3 800x600"))
            candidates.add(Triple(640, 480, "4:3 640x480"))
        } else if (targetRatio >= 1.7f) {
            // 16:9 landscape (1.777)
            candidates.add(Triple(1920, 1080, "16:9 1920x1080"))
            candidates.add(Triple(1280, 720, "16:9 1280x720"))
        } else if (targetRatio <= 0.8f && targetRatio >= 0.7f) {
            // 3:4 portrait (0.75)
            candidates.add(Triple(960, 1280, "3:4 960x1280"))
            candidates.add(Triple(768, 1024, "3:4 768x1024"))
            candidates.add(Triple(600, 800, "3:4 600x800"))
            candidates.add(Triple(480, 640, "3:4 480x640"))
        } else if (targetRatio < 0.7f) {
            // 9:16 portrait (0.5625) or taller
            candidates.add(Triple(720, 1280, "9:16 720x1280"))
            candidates.add(Triple(1080, 1920, "9:16 1080x1920"))
        } else if (targetRatio >= 0.9f && targetRatio <= 1.1f) {
            // 1:1 square
            candidates.add(Triple(1080, 1080, "1:1 1080x1080"))
            candidates.add(Triple(720, 720, "1:1 720x720"))
        } else if (targetRatio >= 0.6f && targetRatio < 0.7f) {
            // 2:3 portrait (0.667)
            candidates.add(Triple(800, 1200, "2:3 800x1200"))
            candidates.add(Triple(720, 1080, "2:3 720x1080"))
        }

        // Always include the legacy 1280x720 (16:9) as a fallback — most
        // capture cards support it, and the FILL Matrix will crop any
        // embedded black bars. This is also the original behavior, so
        // backwards compatibility is preserved.
        if (candidates.none { it.first == 1280 && it.second == 720 }) {
            candidates.add(Triple(1280, 720, "16:9 1280x720 (legacy)"))
        }

        return candidates
    }

    /**
     * Set up the preview surface and start streaming.
     * MUST be called on the MAIN thread (UI thread).
     *
     * This method is called:
     * - After the camera first opens (initial setup)
     * - After the TextureView is re-created (screen re-entry / tab switch)
     * - After the surface is destroyed and re-created (app background/foreground)
     *
     * It calls stopPreview() BEFORE setPreviewDisplay() to ensure a clean
     * restart — without this, a stale surface reference from the previous
     * session can cause setPreviewDisplay/startPreview to fail silently,
     * leaving the preview black.
     */
    private fun setupPreviewSurface(camera: UVCCamera, tv: TextureView) {
        // IDENTITY CHECK (preview-not-showing fix): only set up the preview
        // if THIS camera is still the current one. A 2nd openUVCCamera (from
        // onConnect + tryOpenUVCCameraDirect double-fire, or a watchdog
        // retry) may have called closeUVCCamera() which destroyed this camera
        // and replaced uvcCamera with a new instance. Without this check,
        // this (stale) setup would call setPreviewDisplay/startPreview on a
        // DESTROYED camera → silent fail → no preview. With the check, the
        // stale setup bails and the winning open's setupPreviewSurface runs.
        val currentCamera = uvcCamera
        if (currentCamera !== camera) {
            Log.w(TAG, "setupPreviewSurface: camera was replaced (uvcCamera !== camera) — " +
                "stale preview setup bailing (a newer open will set up the preview)")
            return
        }

        try {
            val surfaceTexture = tv.surfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "SurfaceTexture is null even though TextureView is available!")
                return
            }

            // Stop any existing preview FIRST — ensures a clean restart.
            // Without this, setPreviewDisplay/startPreview may fail silently
            // when called on a camera that was previously previewing to a
            // now-destroyed surface (happens on screen re-entry / tab switch).
            try {
                camera.stopPreview()
                Log.i(TAG, "stopPreview() called before re-setup (clean restart)")
            } catch (e: Exception) {
                Log.w(TAG, "stopPreview() before re-setup failed (non-fatal): ${e.message}")
            }

            // Release any old surface reference
            previewSurface?.release()
            previewSurface = null

            // Create Surface from SurfaceTexture
            val surface = Surface(surfaceTexture)
            previewSurface = surface

            // Use setPreviewDisplay(Surface) — the correct method for TextureView
            // Do NOT also call setPreviewTexture() — they conflict
            camera.setPreviewDisplay(surface)
            Log.i(TAG, "★ setPreviewDisplay(Surface) called")

            // START THE PREVIEW STREAM
            camera.startPreview()
            Log.i(TAG, "★★★ startPreview() called — video stream should now be visible ★★★")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup preview surface: ${e.message}", e)
            // Try alternative: setPreviewTexture instead
            try {
                val surfaceTexture = tv.surfaceTexture
                if (surfaceTexture != null) {
                    camera.setPreviewTexture(surfaceTexture)
                    Log.i(TAG, "★ Fallback: setPreviewTexture() called")
                    camera.startPreview()
                    Log.i(TAG, "★★★ startPreview() called after fallback ★★★")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback setPreviewTexture also failed: ${e2.message}")
            }
        }
    }

    /**
     * Called by TextureView.SurfaceTextureListener when surface becomes available.
     *
     * ALWAYS re-setup the preview surface if the camera is connected — not just
     * when `pendingSurfaceSetup` is true. This handles the screen re-entry case
     * where the old TextureView was destroyed (pendingSurfaceSetup=true) AND
     * the case where `setTextureView` was called with a new TextureView but
     * the camera was already connected (pendingSurfaceSetup may have been
     * reset). Defense in depth: the preview surface is always reconnected to
     * the current TextureView's surface.
     */
    fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
        val camera = uvcCamera
        val tv = textureView
        if (camera != null && tv != null) {
            pendingSurfaceSetup = false
            // BUG #4 FIX: Run on main thread
            mainHandler.post {
                setupPreviewSurface(camera, tv)
            }
        }
    }

    /**
     * Called when the TextureView's surface size changes (delegated from a
     * shared listener when the TextureView is shared with Camera2Manager).
     */
    fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "TextureView size changed: ${width}x${height}")
    }

    /**
     * Called when the TextureView's surface is destroyed.
     */
    fun onSurfaceTextureDestroyed(): Boolean {
        Log.i(TAG, "onSurfaceTextureDestroyed")
        try {
            uvcCamera?.stopPreview()
        } catch (_: Exception) {}
        previewSurface?.release()
        previewSurface = null
        pendingSurfaceSetup = true  // Will need to re-setup when surface returns
        return true  // Return true = we handled it, surface can be destroyed
    }

    private fun closeUVCCamera() {
        // Hot-plug ANR fix: serialize with openUVCCamera so a close can't
        // null uvcCamera while openUVCCamera is mid-flight between
        // closeUVCCamera() and `uvcCamera = camera`. Note: this function is
        // re-entrant from openUVCCamera which already holds the lock in some
        // call paths — those callers wrap it in their own synchronized block.
        // To avoid deadlock from double-locking the same monitor (Kotlin/Java
        // monitors are reentrant, so this is safe), we synchronize here too.
        synchronized(cameraLock) {
            try {
                uvcCamera?.let { cam ->
                    try { cam.stopPreview() } catch (_: Exception) {}
                    try { cam.close() } catch (_: Exception) {}
                    try { cam.destroy() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            uvcCamera = null
            previewSurface?.release()
            previewSurface = null
            _isConnected.value = false
            _cameraType.value = "none"
            pendingSurfaceSetup = false
            // Reset the negotiated size — the next open may negotiate differently
            negotiatedWidth = PREVIEW_WIDTH
            negotiatedHeight = PREVIEW_HEIGHT
            _negotiatedSize.value = PREVIEW_WIDTH to PREVIEW_HEIGHT
        }
    }

    private fun handleDeviceDisconnect(device: UsbDevice) {
        if (currentUsbDevice?.deviceName == device.deviceName) {
            closeUVCCamera()
            currentUsbDevice = null
            currentCtrlBlock = null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TextureView Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Set the TextureView for camera preview.
     * Called from Compose's AndroidView factory.
     */
    fun setTextureView(tv: TextureView) {
        Log.i(TAG, "setTextureView: new TextureView set (available=${tv.isAvailable})")

        // Clean up old surface
        previewSurface?.release()
        previewSurface = null

        this.textureView = tv

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "TextureView.SurfaceTexture available: ${width}x${height}")
                this@UVCCameraManager.onSurfaceTextureAvailable(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                this@UVCCameraManager.onSurfaceTextureSizeChanged(surface, width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView.SurfaceTexture destroyed")
                return this@UVCCameraManager.onSurfaceTextureDestroyed()
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Called on every frame — don't log
            }
        }

        // If camera is already open and surface is available, set it up immediately
        val camera = uvcCamera
        if (camera != null && tv.isAvailable) {
            Log.i(TAG, "Camera already open + TextureView available — setting up preview NOW")
            pendingSurfaceSetup = false
            mainHandler.post {
                setupPreviewSurface(camera, tv)
            }
        }
    }

    /**
     * Remove the TextureView reference.
     */
    fun clearTextureView() {
        textureView = null
        previewSurface?.release()
        previewSurface = null
    }

    // ═══════════════════════════════════════════════════════════════
    // Photo Capture
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a bitmap from the current camera preview (for hand detection).
     * Returns a downscaled bitmap to save CPU, or null if not available.
     */
    fun getPreviewBitmap(): Bitmap? {
        val tv = textureView ?: return null
        return try {
            if (tv.isAvailable && tv.width > 0 && tv.height > 0) {
                // Downscale to 320px width for hand detection (saves CPU)
                val scale = 320f / tv.width
                val w = 320
                val h = (tv.height * scale).toInt()
                tv.getBitmap(w, h)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun capturePhoto(onResult: (String?) -> Unit) {
        val camera = uvcCamera
        if (camera == null) {
            Log.e(TAG, "capturePhoto: No UVCCamera instance")
            onResult(null)
            return
        }

        // Strategy 1: TextureView.getBitmap()
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            try {
                val bitmap = tv.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    if (!isBitmapBlack(bitmap)) {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64"
                        outputStream.close()
                        bitmap.recycle()

                        Log.i(TAG, "Photo captured via TextureView bitmap (${dataUrl.length} chars)")
                        onResult(dataUrl)
                        return
                    } else {
                        Log.w(TAG, "TextureView bitmap is all black, trying IFrameCallback...")
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TextureView bitmap capture failed: ${e.message}")
            }
        }

        // Strategy 2: IFrameCallback
        captureFromFrameCallback(camera, onResult)
    }

    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        try {
            val w = bitmap.width
            val h = bitmap.height
            val stepX = maxOf(1, w / 20)
            val stepY = maxOf(1, h / 20)
            var blackCount = 0
            var totalCount = 0

            for (x in 0 until w step stepX) {
                for (y in 0 until h step stepY) {
                    val pixel = bitmap.getPixel(x, y)
                    totalCount++
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r + g + b < 30) {
                        blackCount++
                    }
                }
            }

            val blackRatio = blackCount.toFloat() / totalCount.toFloat()
            return blackRatio > 0.95f
        } catch (e: Exception) {
            Log.e(TAG, "isBitmapBlack check failed: ${e.message}")
            return false
        }
    }

    private fun captureFromFrameCallback(camera: UVCCamera, callback: (String?) -> Unit) {
        // Single callback slot: fail any still-pending capture first so its
        // caller isn't leaked waiting forever.
        captureCallback?.invoke(null)
        captureCallback = callback

        try {
            camera.setFrameCallback(object : IFrameCallback {
                override fun onFrame(frame: ByteBuffer) {
                    if (!frame.hasRemaining()) {
                        Log.e(TAG, "IFrameCallback: empty frame")
                        val cb = captureCallback
                        captureCallback = null
                        cb?.invoke(null)
                        return
                    }

                    try {
                        val frameBytes = ByteArray(frame.remaining())
                        frame.get(frameBytes)

                        // Use the ACTUAL negotiated size — after the 640x480
                        // fallback, decoding with 1280x720 corrupts the capture.
                        val yuvImage = android.graphics.YuvImage(
                            frameBytes, ImageFormat.NV21,
                            negotiatedWidth, negotiatedHeight, null
                        )
                        val outputStream = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(
                            Rect(0, 0, negotiatedWidth, negotiatedHeight), 100, outputStream
                        )
                        val bytes = outputStream.toByteArray()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64"
                        outputStream.close()

                        Log.i(TAG, "Photo captured via IFrameCallback (${dataUrl.length} chars)")

                        val cb = captureCallback
                        captureCallback = null
                        cb?.invoke(dataUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting IFrameCallback frame: ${e.message}")
                        val cb = captureCallback
                        captureCallback = null
                        cb?.invoke(null)
                    }

                    // Remove callback after one frame
                    try {
                        camera.setFrameCallback(null, 0)
                    } catch (_: Exception) {}
                }
            }, UVCCamera.PIXEL_FORMAT_NV21)

            // Timeout — guarded to the specific callback instance this timeout
            // belongs to, so the timeout of an OLD capture can't kill a NEW
            // capture's callback (captureCallback may have been replaced).
            backgroundHandler?.postDelayed({
                if (captureCallback === callback) {
                    Log.w(TAG, "IFrameCallback timed out")
                    try { camera.setFrameCallback(null, 0) } catch (_: Exception) {}

                    val tv = textureView
                    if (tv != null && tv.isAvailable) {
                        try {
                            val bitmap = tv.bitmap
                            if (bitmap != null && !bitmap.isRecycled) {
                                val outputStream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                                val bytes = outputStream.toByteArray()
                                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                val dataUrl = "data:image/jpeg;base64,$base64"
                                outputStream.close()
                                bitmap.recycle()

                                captureCallback = null
                                callback(dataUrl)
                                return@postDelayed
                            }
                        } catch (_: Exception) {}
                    }

                    captureCallback = null
                    callback(null)
                }
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "IFrameCallback setup failed: ${e.message}")
            captureCallback = null
            callback(null)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Camera Switching / Rescan
    // ═══════════════════════════════════════════════════════════════

    fun switchCamera(deviceId: String) {
        val device = discoveredDevices[deviceId]
        if (device != null) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager?.hasPermission(device) == true) {
                backgroundHandler?.post {
                    closeUVCCamera()
                    usbMonitor?.requestPermission(device)
                }
            } else {
                requestUsbPermission(device)
            }
        }
    }

    fun forceRescan() {
        Log.i(TAG, "forceRescan: Re-registering USBMonitor")
        try {
            stopUsbPolling()
            unregisterUsbMonitor()
            discoveredDevices.clear()
            updateAvailableCameras()
            registerUsbMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "forceRescan failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Compatibility Methods
    // ═══════════════════════════════════════════════════════════════

    fun showPreview() { Log.i(TAG, "showPreview — preview always visible via TextureView") }
    fun hidePreview() { Log.i(TAG, "hidePreview — TextureView stays active for streaming") }

    /**
     * Close the UVC camera entirely on screen exit so that on re-entry, the
     * auto-reconnect (500ms) + watchdog (5s) will re-open a FRESH camera.
     *
     * Called from AdminOperatorScreen.onDispose when the user navigates away
     * from the operator tab. Without this, the camera stays "connected" with
     * a stale surface reference → on re-entry, the new TextureView's surface
     * can't connect to the old camera → permanent black screen.
     *
     * Why close entirely (not just stop preview): the serenegiant UVCCamera
     * library doesn't reliably accept a new surface after the old one is
     * destroyed — setPreviewDisplay/startPreview can fail silently, leaving
     * the preview black. Closing + re-opening guarantees a clean state.
     *
     * The auto-reconnect on screen re-entry calls forceReconnect() which
     * re-initializes the USB monitor and re-opens the camera (1-2s).
     */
    fun releaseForScreenExit() {
        Log.i(TAG, "releaseForScreenExit: closing camera for clean re-entry")
        try {
            closeUVCCamera()
        } catch (e: Exception) {
            Log.w(TAG, "releaseForScreenExit: closeUVCCamera error: ${e.message}")
        }
        // closeUVCCamera already resets _isConnected, _negotiatedSize, etc.
        // but set these explicitly for clarity + defense in depth.
        pendingSurfaceSetup = true
    }

    fun switchToCamera(cameraId: String) = switchCamera(cameraId)
    fun hasCameraPermission(): Boolean = true
    fun refreshCameraList() = forceRescan()
    fun forceSwitchToUSB() = forceRescan()
    fun setFilter(preset: String) { Log.d(TAG, "setFilter: $preset") }
    fun setFrameOverlay(base64Data: String?) { Log.d(TAG, "setFrameOverlay: ${base64Data != null}") }
    fun updateConfig(aspectRatio: Double, filterPreset: String) { setFilter(filterPreset) }
    fun getUSBMonitor(): USBMonitor? = usbMonitor

    fun onUsbPermissionResult(device: UsbDevice, granted: Boolean) {
        Log.i(TAG, "onUsbPermissionResult: ${device.deviceName}, granted=$granted")
        // USBMonitor's onConnect handles the actual camera opening
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle — Destroy
    // ═══════════════════════════════════════════════════════════════

    fun destroy() {
        Log.i(TAG, "destroy: Cleaning up UVCCamera")
        isDestroying = true
        isInitialized = false
        isReconnecting = false
        stopUsbPolling()
        closeUVCCamera()
        unregisterUsbMonitor()
        unregisterUsbPermissionReceiver()
        stopBackgroundThread()
        // Note: camera.open() runs on fresh daemon threads (FutureTask per
        // open). Any stuck native open() calls are daemon threads that die
        // automatically on process exit — nothing to shut down here.
        discoveredDevices.clear()
        _isConnected.value = false
        _cameraType.value = "none"
        _availableCameras.value = emptyList()
        textureView = null
        previewSurface = null
    }

    /**
     * Force reconnect camera — nuclear recovery option for when the camera
     * disconnects mid-ceremony (battery dead, cable unplugged, USB hang).
     *
     * This method:
     * 1. Closes any existing UVC camera connection.
     * 2. Resets isInitialized so initCamera() will re-register USBMonitor.
     * 3. Re-registers USBMonitor + re-scans for USB devices.
     * 4. If a UVC device is found + has permission, opens it immediately.
     *
     * Call this when:
     * - User clicks "Reconnect Camera" button.
     * - AdminOperatorScreen re-enters (returning from Admin tab).
     * - USB device detach is detected (camera unplugged / battery dead).
     */
    fun forceReconnect() {
        // Guard against overlapping calls: the AdminOperatorScreen watchdog
        // fires every 5s, and the user can also tap "Reconnect" manually.
        // Without this guard, concurrent forceReconnect calls would each
        // stop+restart the background thread (spawning orphans if the old
        // thread is stuck in camera.open) and pile up USBMonitor
        // re-registrations — making recovery impossible.
        if (isReconnecting) {
            Log.w(TAG, "forceReconnect: already in progress — skipping (isReconnecting=true)")
            return
        }
        isReconnecting = true
        Log.i(TAG, "forceReconnect: nuclear camera recovery initiated")

        try {
        // Step 1: Close existing connection cleanly
        try { closeUVCCamera() } catch (e: Exception) {
            Log.w(TAG, "forceReconnect: closeUVCCamera error: ${e.message}")
        }

        // Step 2: Reset state so initCamera will re-register everything.
        isInitialized = false
        isDestroying = false

        // Step 3: Unregister old USBMonitor (if any)
        try { unregisterUsbMonitor() } catch (_: Exception) {}
        try { unregisterUsbPermissionReceiver() } catch (_: Exception) {}

        // Step 4: Stop + restart background thread
        try { stopBackgroundThread() } catch (_: Exception) {}
        discoveredDevices.clear()

        // Step 5: Re-initialize (registers USBMonitor + starts scanning)
        initCamera()

        // Step 6: Check for already-connected USB devices + try to open directly
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager != null) {
            val deviceList = usbManager.deviceList
            for ((_, device) in deviceList) {
                Log.i(TAG, "forceReconnect: found USB device: ${device.deviceName} (vid=${device.vendorId}, pid=${device.productId})")
                // Check if it looks like a UVC device (video class 14)
                var isUvc = false
                for (i in 0 until device.interfaceCount) {
                    if (device.getInterface(i).interfaceClass == 14) { isUvc = true; break }
                }
                // Also check known capture card vendor IDs
                val knownVids = setOf(0x534D, 0x1D6C, 0x0C45, 0x046D)
                if (!isUvc && device.vendorId in knownVids) isUvc = true
                // Fallback: try anyway (better to try than not)
                isUvc = true  // Always try — the USBMonitor will filter non-UVC

                if (isUvc) {
                    Log.i(TAG, "forceReconnect: attempting to open: ${device.deviceName}")
                    if (usbManager.hasPermission(device)) {
                        backgroundHandler?.post { tryOpenUVCCameraDirect(device) }
                    } else {
                        val monitor = usbMonitor
                        if (monitor != null) {
                            backgroundHandler?.post { monitor.requestPermission(device) }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "forceReconnect: recovery sequence complete")
        } finally {
            isReconnecting = false
        }
    }

}
