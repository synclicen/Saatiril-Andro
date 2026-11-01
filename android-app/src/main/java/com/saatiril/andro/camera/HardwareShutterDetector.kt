package com.saatiril.andro.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import kotlin.math.abs

/**
 * Visual hardware shutter detector — adapted from ARAY (github.com/synclicen/ARAY).
 *
 * Detects DSLR shutter press via USB capture card by analyzing brightness +
 * pixel change between consecutive frames. When the DSLR shutter fires:
 * 1. The mirror flips → ENTIRE frame changes (massive pixel change)
 * 2. The viewfinder briefly goes dark → brightness drops
 *
 * This is the SAME approach ARAY uses (Booth.tsx hardware shutter detection),
 * ported from TypeScript/React to Kotlin/Compose for Saatiril Andro.
 *
 * Sensitivity modes (matching ARAY):
 * - "auto"   = smart detection — combines brightness dip + pixel change spike.
 *              Works with AF (ripples), auto exposure (gradual changes), auto shutter.
 * - "high"   = 30% brightness drop, trigger immediately
 * - "medium" = 40% drop, wait for recovery
 * - "low"    = 60% drop, only long blackouts
 *
 * Usage:
 *   val detector = HardwareShutterDetector(sensitivity = "auto")
 *   detector.onShutterDetected = { -> triggerCapture() }
 *   detector.start(textureView)
 *   // ... polling loop calls detector.processFrame(bitmap) ...
 *   detector.stop()
 */
class HardwareShutterDetector(
    private val sensitivity: String = "auto"
) {
    companion object {
        private const val TAG = "HWShutterDetector"
        private const val MIN_AVG_BRIGHTNESS = 20f
        // Cooldown setelah shutter terdeteksi — cukup untuk mencegah double-
        // trigger dari mirror flip + recovery (~200-400ms). ARAY pakai 3000ms
        // tapi Saatiril butuh faster turnaround untuk wisuda 2-shoot (toga→ijazah
        // berurutan). 800ms cukup: mirror flip ~100ms + blackout ~200ms +
        // buffer 500ms. Shutter berikutnya bisa trigger dalam ~0.8 detik.
        private const val COOLDOWN_MS = 800L

        // Pixel change tracking for Auto mode (matches ARAY)
        private const val PIXEL_CHANGE_SIZE = 5
        private const val PIXEL_CHANGE_SPIKE_MULTIPLIER = 3.0f
        private const val PIXEL_CHANGE_MIN_ABSOLUTE = 30f
        private const val BRIGHTNESS_DIP_RATIO = 0.80f  // 20% drop
    }

    // Sensitivity-based threshold (matches ARAY's BLACKOUT_THRESHOLD).
    // Must be in the instance body (not companion object) because it depends
    // on the `sensitivity` constructor parameter.
    // 'high'   = 0.70 (trigger on 30% drop)
    // 'medium' = 0.60 (trigger on 40% drop)
    // 'low'    = 0.40 (trigger on 60% drop)
    private val BLACKOUT_THRESHOLD = when (sensitivity) {
        "high" -> 0.70f
        "low" -> 0.40f
        else -> 0.60f  // medium + auto
    }

    /** Called when a shutter press is detected. Set by the caller. */
    var onShutterDetected: (() -> Unit)? = null

    @Volatile private var monitoring = false
    @Volatile private var lastBrightness = -1f
    @Volatile private var dipDetected = false
    @Volatile private var dipBrightness = 0f
    @Volatile private var lastTriggerTime = 0L

    // Rolling history (matches ARAY's brightnessHistory + pixelChangeHistory)
    private val brightnessHistory = ArrayDeque<Float>(5)
    private val pixelChangeHistory = ArrayDeque<Float>(5)
    private var lastFramePixels: IntArray? = null

    // Small canvas for brightness sampling (32x32 = 1024 pixels, matches ARAY)
    private val sampleSize = 32
    private val sampleBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val sampleCanvas = Canvas(sampleBitmap)
    private val samplePaint = Paint().apply { isFilterBitmap = true }

    /**
     * Process a frame from the UVC TextureView. Returns true if a shutter
     * was detected (caller should trigger capture). The caller is responsible
     * for the polling loop — call this every ~200ms (5fps, matches ARAY).
     *
     * @param sourceBitmap The bitmap from TextureView.getBitmap() or UVC capturePhoto.
     * @return true if shutter detected, false otherwise.
     */
    fun processFrame(sourceBitmap: Bitmap): Boolean {
        if (!monitoring) return false

        // Cooldown check (matches ARAY's COOLDOWN_MS)
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < COOLDOWN_MS) return false

        // Downscale to 32x32 for fast brightness + pixel change calculation
        sampleCanvas.drawBitmap(sourceBitmap, null,
            android.graphics.RectF(0f, 0f, sampleSize.toFloat(), sampleSize.toFloat()),
            samplePaint)

        val pixels = IntArray(sampleSize * sampleSize)
        sampleBitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

        // Calculate brightness (luminance) — matches ARAY's 0.299R + 0.587G + 0.114B
        var sum = 0f
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += 0.299f * r + 0.587f * g + 0.114f * b
        }
        val brightness = sum / pixels.size

        // Calculate pixel change vs last frame (matches ARAY's pixelChange tracking)
        var pixelChange = 0f
        if (lastFramePixels != null && lastFramePixels!!.size == pixels.size) {
            var diffSum = 0f
            var count = 0
            // Sample every 4th pixel for speed (matches ARAY's i += 16 step)
            var i = 0
            while (i < pixels.size) {
                val pr = (pixels[i] shr 16) and 0xFF
                val pg = (pixels[i] shr 8) and 0xFF
                val pb = pixels[i] and 0xFF
                val lr = (lastFramePixels!![i] shr 16) and 0xFF
                val lg = (lastFramePixels!![i] shr 8) and 0xFF
                val lb = lastFramePixels!![i] and 0xFF
                diffSum += (abs(pr - lr) + abs(pg - lg) + abs(pb - lb)) / 3f
                count++
                i += 4  // every 4th pixel
            }
            pixelChange = if (count > 0) diffSum / count else 0f
        }
        lastFramePixels = pixels.copyOf()

        if (brightness < 0) return false

        // ── AUTO mode: smart detection (pixel change spike + brightness dip) ──
        // Matches ARAY's auto mode: mirror flip causes massive pixel change
        // + brightness dip simultaneously.
        if (sensitivity == "auto") {
            pixelChangeHistory.addLast(pixelChange)
            if (pixelChangeHistory.size > PIXEL_CHANGE_SIZE) pixelChangeHistory.removeFirst()

            if (pixelChangeHistory.size >= 3) {
                // Average of all except the last (current) frame
                val prevChanges = pixelChangeHistory.toList().dropLast(1)
                val avgChange = prevChanges.average().toFloat()

                // Pixel change spike: current > 3x average AND > 30 absolute
                // AND brightness dipped >20% from last frame
                if (pixelChange > avgChange * PIXEL_CHANGE_SPIKE_MULTIPLIER &&
                    pixelChange > PIXEL_CHANGE_MIN_ABSOLUTE &&
                    lastBrightness > MIN_AVG_BRIGHTNESS &&
                    brightness < lastBrightness * BRIGHTNESS_DIP_RATIO) {

                    Log.i(TAG, "Hardware shutter (auto mode)! pixelChange=${pixelChange.toInt()} " +
                            "(avg=${avgChange.toInt()}), brightness ${lastBrightness.toInt()}→${brightness.toInt()}")

                    lastTriggerTime = now
                    resetState()
                    onShutterDetected?.invoke()
                    return true
                }
            }
            // In auto mode, also fall through to dip-recovery + rolling avg checks below
        }

        // ── DIP-RECOVERY detection ──
        // If previous frame was a dip (dark) and current recovered → brief blackout confirmed.
        // Matches ARAY's dipDetected logic.
        if (dipDetected && brightness > dipBrightness * 1.5f && brightness > MIN_AVG_BRIGHTNESS) {
            Log.i(TAG, "Hardware shutter (dip recovery)! dip=${dipBrightness.toInt()} → recovered=${brightness.toInt()}")

            lastTriggerTime = now
            resetState()
            onShutterDetected?.invoke()
            return true
        }

        // ── FAST DROP detection with sensitivity-based threshold ──
        // Matches ARAY's BLACKOUT_THRESHOLD check.
        if (lastBrightness > MIN_AVG_BRIGHTNESS &&
            brightness < lastBrightness * (1f - BLACKOUT_THRESHOLD)) {

            if (sensitivity == "high") {
                // High sensitivity: trigger immediately (don't wait for recovery)
                Log.i(TAG, "Hardware shutter (high sens immediate)! ${lastBrightness.toInt()} → ${brightness.toInt()}")

                lastTriggerTime = now
                resetState()
                onShutterDetected?.invoke()
                return true
            }

            // For MEDIUM/LOW/AUTO: mark as dip, wait for recovery
            dipDetected = true
            dipBrightness = brightness
            Log.d(TAG, "Dip detected: ${lastBrightness.toInt()} → ${brightness.toInt()} (waiting for recovery)")
        }

        // Also check against rolling average (catches gradual dimming)
        brightnessHistory.addLast(brightness)
        if (brightnessHistory.size > 5) brightnessHistory.removeFirst()
        lastBrightness = brightness

        if (brightnessHistory.size >= 3) {
            val prevSamples = brightnessHistory.toList().dropLast(1)
            val avgBrightness = prevSamples.average().toFloat()

            if (avgBrightness > MIN_AVG_BRIGHTNESS &&
                brightness < avgBrightness * (1f - BLACKOUT_THRESHOLD)) {

                Log.i(TAG, "Hardware shutter (avg drop)! brightness=${brightness.toInt()}, avg=${avgBrightness.toInt()}")

                lastTriggerTime = now
                resetState()
                onShutterDetected?.invoke()
                return true
            }
        }

        return false
    }

    private fun resetState() {
        brightnessHistory.clear()
        pixelChangeHistory.clear()
        lastBrightness = -1f
        dipDetected = false
        lastFramePixels = null
    }

    /**
     * Reset cooldown + state so the detector is immediately ready for the
     * next shutter. Called by AdminOperatorScreen when isCapturing goes
     * false (capture complete) — the detector can detect the NEXT shutter
     * without waiting for the cooldown to expire.
     *
     * This is critical for wisuda 2-shoot mode (toga→ijazah berurutan):
     * without this, the detector would wait COOLDOWN_MS after the toga
     * shutter before it can detect the ijazah shutter.
     */
    fun resetCooldown() {
        lastTriggerTime = 0L
        resetState()
        Log.d(TAG, "Cooldown reset — detector ready for next shutter")
    }

    fun start() {
        monitoring = true
        resetState()
        Log.i(TAG, "Hardware shutter detection started (sensitivity=$sensitivity)")
    }

    fun stop() {
        monitoring = false
        resetState()
        Log.i(TAG, "Hardware shutter detection stopped")
    }
}
