package com.saatiril.andro.camera

import android.graphics.*
import android.util.Log
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.ProjectConfig
import java.io.ByteArrayOutputStream

/**
 * Handles photo capture processing:
 * - Center-crop to aspect ratio
 * - Apply filter presets (ColorMatrix)
 * - Overlay frame
 * - Convert to base64
 *
 * QUALITY POLICY (HD+ graduation photos):
 * - processFrame preserves the SOURCE bitmap resolution as much as possible.
 *   The previous implementation hardcoded output to 1920px wide, which
 *   UPSCALED small TextureView bitmaps (e.g. 800px) → blurry photos. Now
 *   the output matches the source resolution after center-crop, capped at
 *   MAX_OUTPUT_WIDTH (2560) to avoid OOM on very high-res sources.
 * - bitmapToBase64 defaults to JPEG quality 100 (was 95). Quality 100
 *   produces larger files (~3-6 MB for 1920×1440) but eliminates JPEG
 *   compression artifacts — critical for graduation photos that may be
 *   printed or zoomed. Callers that need smaller files (e.g. live preview
 *   relay) can pass a lower quality explicitly.
 */
object CameraCapture {

    private const val TAG = "CameraCapture"
    /**
     * Maximum output width. Sources wider than this are downscaled to this
     * width (preserving aspect ratio). Sources narrower keep their native
     * width (no upscaling → no blur). 2560 is a safe cap that avoids OOM
     * on 4 GB+ phones while allowing full HD+ 4:3 (1920×1440) and 16:9
     * (1920×1080) sources to pass through at native resolution.
     */
    private const val MAX_OUTPUT_WIDTH = 2560

    /**
     * Process a camera frame into a final photo:
     * 1. Center-crop to the project's aspect ratio (at SOURCE resolution)
     * 2. Downscale only if the cropped source exceeds [MAX_OUTPUT_WIDTH]
     * 3. Apply the configured filter preset
     * 4. Overlay the frame (if any), scaled to match the output exactly
     */
    fun processFrame(
        sourceBitmap: Bitmap,
        config: ProjectConfig,
        frameBitmap: Bitmap? = null
    ): Bitmap {
        val aspectRatio = config.parseAspectRatio()

        // ── Step 1: center-crop the source to the target aspect ratio ──
        // Do this at SOURCE resolution (not a fixed target) so we preserve
        // all the detail the camera delivered. The previous code drew the
        // source into a fixed 1920-wide canvas — if the source was 800px,
        // it upscaled 2.4× → blur.
        val srcWidth = sourceBitmap.width
        val srcHeight = sourceBitmap.height
        val srcRatio = srcWidth.toFloat() / srcHeight.toFloat()

        val cropRect = if (srcRatio > aspectRatio) {
            // Source is wider than target — crop sides to match aspect ratio
            val cropWidth = (srcHeight * aspectRatio).toInt()
            val left = (srcWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, srcHeight)
        } else {
            // Source is taller than target — crop top/bottom
            val cropHeight = (srcWidth / aspectRatio).toInt()
            val top = (srcHeight - cropHeight) / 2
            Rect(0, top, srcWidth, top + cropHeight)
        }

        // ── Step 2: determine output size ──
        // Use the cropped source size, capped at MAX_OUTPUT_WIDTH.
        val croppedWidth = cropRect.width()
        val croppedHeight = cropRect.height()
        val outputWidth: Int
        val outputHeight: Int
        if (croppedWidth > MAX_OUTPUT_WIDTH) {
            // Downscale (preserves aspect ratio) — source is very high-res
            outputWidth = MAX_OUTPUT_WIDTH
            outputHeight = (MAX_OUTPUT_WIDTH.toFloat() / croppedWidth * croppedHeight).toInt()
        } else {
            // Keep native cropped resolution — NO upscaling, NO downscaling
            outputWidth = croppedWidth
            outputHeight = croppedHeight
        }

        Log.i(TAG, "processFrame: src=${srcWidth}x${srcHeight}, crop=${croppedWidth}x${croppedHeight}, " +
            "output=${outputWidth}x${outputHeight} (aspect=$aspectRatio, preset=${config.preset})")

        // Create output bitmap
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Fill with black (in case the frame overlay has transparency)
        canvas.drawColor(Color.BLACK)

        val dstRect = Rect(0, 0, outputWidth, outputHeight)

        // Apply filter preset + draw the cropped camera frame at full output resolution
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        applyFilterPreset(paint, config.preset)
        canvas.drawBitmap(sourceBitmap, cropRect, dstRect, paint)

        // Draw frame overlay — scaled to EXACTLY match the output so the frame
        // aligns pixel-perfectly with the photo. The frame PNG should have the
        // same aspect ratio as the project config; any mismatch is stretched
        // to fit (matches the Electron behavior).
        if (frameBitmap != null) {
            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(frameBitmap, null, dstRect, framePaint)
        }

        return output
    }

    /**
     * Convert bitmap to base64 JPEG data URI.
     *
     * Default quality is **100** (lossless-ish JPEG) for maximum photo
     * clarity. A 1920×1440 photo at quality 100 is ~4-7 MB; at quality 95
     * it's ~1.5-3 MB. The size increase is acceptable for graduation
     * photos that may be printed or zoomed — clarity > file size.
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 100): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
    
    /**
     * Apply CSS filter equivalent via ColorMatrix
     */
    private fun applyFilterPreset(paint: Paint, preset: String) {
        val colorMatrix = when (preset) {
            "studio" -> ColorMatrix(floatArrayOf(
                1.1f, 0f, 0f, 0f, 0f,      // R: brightness 1.1
                0f, 1.1f, 0f, 0f, 0f,       // G: brightness 1.1
                0f, 0f, 1.1f, 0f, 0f,       // B: brightness 1.1
                0f, 0f, 0f, 1.05f, 0f,      // A: contrast 1.05
                0f, 0f, 0f, 0f, 1f          // offset
            )).also { 
                // Saturation 1.1 via setSaturation
                it.setSaturation(1.1f)
            }
            
            "cinematic" -> ColorMatrix().apply {
                setSaturation(1.3f)
                val scale = floatArrayOf(
                    0.95f, 0f, 0f, 0f, 0f,    // brightness 0.95
                    0f, 0.95f, 0f, 0f, 0f,
                    0f, 0f, 0.95f, 0f, 0f,
                    0f, 0f, 0f, 1.1f, 0f      // contrast 1.1
                )
                val scaleMatrix = ColorMatrix(scale)
                preConcat(scaleMatrix)
                // Sepia overlay
                val sepia = ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                // Blend 15% sepia
                val blend = ColorMatrix()
                blend.set(floatArrayOf(
                    1f - 0.15f * (1f - 0.393f), 0.15f * 0.769f, 0.15f * 0.189f, 0f, 0f,
                    0.15f * 0.349f, 1f - 0.15f * (1f - 0.686f), 0.15f * 0.168f, 0f, 0f,
                    0.15f * 0.272f, 0.15f * 0.534f, 1f - 0.15f * (1f - 0.131f), 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                preConcat(blend)
            }
            
            "pro" -> ColorMatrix(floatArrayOf(
                1.05f, 0f, 0f, 0f, 0f,      // brightness 1.05
                0f, 1.05f, 0f, 0f, 0f,
                0f, 0f, 1.05f, 0f, 0f,
                0f, 0f, 0f, 1.25f, 0f       // contrast 1.25
            )).also { it.setSaturation(1.15f) }
            
            "vivid" -> ColorMatrix(floatArrayOf(
                1.08f, 0f, 0f, 0f, 0f,      // brightness 1.08
                0f, 1.08f, 0f, 0f, 0f,
                0f, 0f, 1.08f, 0f, 0f,
                0f, 0f, 0f, 1.12f, 0f       // contrast 1.12
            )).also { it.setSaturation(1.45f) }
            
            "softPortrait" -> ColorMatrix(floatArrayOf(
                1.12f, 0f, 0f, 0f, 0f,      // brightness 1.12
                0f, 1.12f, 0f, 0f, 0f,
                0f, 0f, 1.12f, 0f, 0f,
                0f, 0f, 0f, 0.92f, 0f       // contrast 0.92
            )).also { it.setSaturation(1.08f) }
            
            "classicFilm" -> ColorMatrix(floatArrayOf(
                1.02f, 0f, 0f, 0f, 0f,
                0f, 1.02f, 0f, 0f, 0f,
                0f, 0f, 1.02f, 0f, 0f,
                0f, 0f, 0f, 1.15f, 0f
            )).also { it.setSaturation(0.85f) }
            
            "dramaticBW" -> ColorMatrix().apply {
                setSaturation(0f) // Grayscale
                val contrast = ColorMatrix(floatArrayOf(
                    1.05f, 0f, 0f, 0f, 0f,
                    0f, 1.05f, 0f, 0f, 0f,
                    0f, 0f, 1.05f, 0f, 0f,
                    0f, 0f, 0f, 1.35f, 0f
                ))
                postConcat(contrast)
            }
            
            "warmSunset" -> ColorMatrix(floatArrayOf(
                1.06f, 0f, 0f, 0f, 0f,
                0f, 1.06f, 0f, 0f, 0f,
                0f, 0f, 1.06f, 0f, 0f,
                0f, 0f, 0f, 1.08f, 0f
            )).also { it.setSaturation(1.3f) }
            
            else -> null // "original" — no filter
        }
        
        if (colorMatrix != null) {
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
    }
}
