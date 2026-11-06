'use client'

import { useRef, useCallback, useEffect, useState } from 'react'

/**
 * Hardware Shutter Detector — adapted from ARAY (github.com/synclicen/ARAY).
 *
 * Detects DSLR shutter press via USB capture card by analyzing brightness +
 * pixel change between consecutive frames of a <video> element. When the DSLR
 * shutter fires:
 * 1. The mirror flips → ENTIRE frame changes (massive pixel change)
 * 2. The viewfinder briefly goes dark → brightness drops
 *
 * This is the SAME algorithm as ARAY's Booth.tsx hardware shutter detection,
 * adapted into a React hook for Saatiril Electron.
 *
 * Sensitivity modes (matching ARAY):
 * - 'auto'   = smart detection — combines brightness dip + pixel change spike
 * - 'high'   = 30% brightness drop, trigger immediately
 * - 'medium' = 40% drop, wait for recovery
 * - 'low'    = 60% drop, only long blackouts
 *
 * Usage:
 *   const { isReady } = useHardwareShutter({
 *     videoRef,
 *     enabled: dslrShutterEnabled,
 *     isCapturing,
 *     hasActiveTarget,
 *     onShutter: () => triggerCapture(),
 *     sensitivity: 'auto',
 *   })
 */

interface HardwareShutterOptions {
  videoRef: React.RefObject<HTMLVideoElement>
  enabled: boolean
  isCapturing: boolean
  hasActiveTarget: boolean
  onShutter: () => void
  sensitivity?: 'auto' | 'high' | 'medium' | 'low'
}

interface HardwareShutterState {
  isReady: boolean  // true when detector is active + ready to detect
}

export function useHardwareShutter(opts: HardwareShutterOptions): HardwareShutterState {
  const { videoRef, enabled, isCapturing, hasActiveTarget, onShutter, sensitivity = 'auto' } = opts
  const [isReady, setIsReady] = useState(false)

  // Refs for stable values inside the polling loop
  const onShutterRef = useRef(onShutter)
  const isCapturingRef = useRef(isCapturing)
  useEffect(() => { onShutterRef.current = onShutter }, [onShutter])
  useEffect(() => { isCapturingRef.current = isCapturing }, [isCapturing])

  // Detector state
  const lastBrightnessRef = useRef(-1)
  const dipDetectedRef = useRef(false)
  const dipBrightnessRef = useRef(0)
  const lastTriggerTimeRef = useRef(0)
  const brightnessHistoryRef = useRef<number[]>([])
  const pixelChangeHistoryRef = useRef<number[]>([])
  const lastFrameDataRef = useRef<Uint8ClampedArray | null>(null)

  // Constants (matching ARAY + Saatiril Android)
  const COOLDOWN_MS = 800  // Reduced from ARAY's 3000ms for faster 2-shoot turnaround
  const MIN_AVG_BRIGHTNESS = 20
  const PIXEL_CHANGE_SIZE = 5
  const PIXEL_CHANGE_SPIKE_MULTIPLIER = 3.0
  const PIXEL_CHANGE_MIN_ABSOLUTE = 30
  const BRIGHTNESS_DIP_RATIO = 0.65  // 35% drop (raised to reject AF lock)
  const BLACKOUT_THRESHOLD = sensitivity === 'high' ? 0.70 : sensitivity === 'low' ? 0.40 : 0.60

  // Reusable canvas for brightness sampling (32x32, matches ARAY)
  const sampleCanvasRef = useRef<HTMLCanvasElement | null>(null)
  const sampleCtxRef = useRef<CanvasRenderingContext2D | null>(null)

  // Initialize canvas once
  useEffect(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 32
    canvas.height = 32
    sampleCanvasRef.current = canvas
    sampleCtxRef.current = canvas.getContext('2d', { willReadFrequently: true })
  }, [])

  // Reset cooldown + state (called when isCapturing goes false)
  const resetCooldown = useCallback(() => {
    lastTriggerTimeRef.current = 0
    brightnessHistoryRef.current = []
    pixelChangeHistoryRef.current = []
    lastBrightnessRef.current = -1
    dipDetectedRef.current = false
    lastFrameDataRef.current = null
  }, [])

  // Reset when isCapturing goes false (capture complete → ready for next shutter)
  useEffect(() => {
    if (!isCapturing && enabled) {
      resetCooldown()
    }
  }, [isCapturing, enabled, resetCooldown])

  // Main polling loop
  useEffect(() => {
    if (!enabled || !hasActiveTarget) {
      setIsReady(false)
      return
    }

    let cancelled = false
    let monitoring = true
    setIsReady(true)

    // Reset state on start
    brightnessHistoryRef.current = []
    pixelChangeHistoryRef.current = []
    lastBrightnessRef.current = -1
    dipDetectedRef.current = false
    lastFrameDataRef.current = null

    const monitorLoop = async () => {
      while (monitoring && !cancelled) {
        // Skip during capture (the capture's own flash/brightness change
        // would be misdetected as a shutter)
        if (isCapturingRef.current) {
          await new Promise(r => setTimeout(r, 100))
          continue
        }

        // Cooldown check
        const now = Date.now()
        if (now - lastTriggerTimeRef.current < COOLDOWN_MS) {
          await new Promise(r => setTimeout(r, 100))
          continue
        }

        const video = videoRef.current
        const ctx = sampleCtxRef.current
        const canvas = sampleCanvasRef.current
        if (!video || !ctx || !canvas || video.readyState < 2) {
          await new Promise(r => setTimeout(r, 100))
          continue
        }

        try {
          // Downscale to 32x32 for fast brightness + pixel change calculation
          ctx.drawImage(video, 0, 0, 32, 32)
          const imageData = ctx.getImageData(0, 0, 32, 32)
          const data = imageData.data

          // Calculate brightness (luminance: 0.299R + 0.587G + 0.114B)
          let sum = 0
          for (let i = 0; i < data.length; i += 4) {
            sum += 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]
          }
          const brightness = sum / (data.length / 4)

          // Calculate pixel change vs last frame
          let pixelChange = 0
          if (lastFrameDataRef.current && lastFrameDataRef.current.length === data.length) {
            let diffSum = 0
            let count = 0
            for (let i = 0; i < data.length; i += 16) {  // every 4th pixel
              const dr = Math.abs(data[i] - lastFrameDataRef.current[i])
              const dg = Math.abs(data[i + 1] - lastFrameDataRef.current[i + 1])
              const db = Math.abs(data[i + 2] - lastFrameDataRef.current[i + 2])
              diffSum += (dr + dg + db) / 3
              count++
            }
            pixelChange = count > 0 ? diffSum / count : 0
          }
          lastFrameDataRef.current = new Uint8ClampedArray(data)

          if (brightness < 0) {
            await new Promise(r => setTimeout(r, 100))
            continue
          }

          let triggered = false

          // ── SIMPLE BRIGHTNESS DIP FALLBACK (no history needed) ──
          // Catches shutters even when history is empty (right after
          // resetCooldown — e.g. toga→ijazah fast succession).
          // CRITICAL: require BOTH brightness dip (>50%) AND pixel change (>40)
          // to avoid false triggers from Sony A7 AF lock. AF lock causes ~30-40%
          // brightness dip but minimal pixel change (same scene, just blurred).
          // Real shutter = mirror flip = massive pixel change + massive dip.
          if (lastBrightnessRef.current > MIN_AVG_BRIGHTNESS &&
              brightness < lastBrightnessRef.current * 0.50) {
            if (pixelChange > 40) {
              console.log(`[HW Shutter] simple dip+pixel! ${lastBrightnessRef.current.toFixed(0)} → ${brightness.toFixed(0)}, pixelChange=${pixelChange.toFixed(0)}`)
              triggered = true
            } else {
              console.log(`[HW Shutter] brightness dip but low pixel change (${pixelChange.toFixed(0)}) — likely AF lock, not shutter`)
            }
          }

          // ── AUTO mode: pixel change spike + brightness dip ──
          if (sensitivity === 'auto') {
            pixelChangeHistoryRef.current.push(pixelChange)
            if (pixelChangeHistoryRef.current.length > PIXEL_CHANGE_SIZE) {
              pixelChangeHistoryRef.current.shift()
            }

            if (pixelChangeHistoryRef.current.length >= 3) {
              const prevChanges = pixelChangeHistoryRef.current.slice(0, -1)
              const avgChange = prevChanges.reduce((a, b) => a + b, 0) / prevChanges.length
              if (pixelChange > avgChange * PIXEL_CHANGE_SPIKE_MULTIPLIER &&
                  pixelChange > PIXEL_CHANGE_MIN_ABSOLUTE &&
                  lastBrightnessRef.current > MIN_AVG_BRIGHTNESS &&
                  brightness < lastBrightnessRef.current * BRIGHTNESS_DIP_RATIO) {
                console.log(`[HW Shutter] auto mode! pixelChange=${pixelChange.toFixed(0)} (avg=${avgChange.toFixed(0)}), brightness ${lastBrightnessRef.current.toFixed(0)}→${brightness.toFixed(0)}`)
                triggered = true
              }
            }
          }

          // ── DIP-RECOVERY detection ──
          if (!triggered && dipDetectedRef.current &&
              brightness > dipBrightnessRef.current * 1.5 &&
              brightness > MIN_AVG_BRIGHTNESS) {
            console.log(`[HW Shutter] dip recovery! dip=${dipBrightnessRef.current.toFixed(0)} → recovered=${brightness.toFixed(0)}`)
            triggered = true
          }

          // ── FAST DROP detection ──
          if (!triggered && lastBrightnessRef.current > MIN_AVG_BRIGHTNESS &&
              brightness < lastBrightnessRef.current * (1 - BLACKOUT_THRESHOLD)) {
            if (sensitivity === 'high') {
              console.log(`[HW Shutter] high sens immediate! ${lastBrightnessRef.current.toFixed(0)} → ${brightness.toFixed(0)}`)
              triggered = true
            } else {
              dipDetectedRef.current = true
              dipBrightnessRef.current = brightness
            }
          }

          // ── Rolling average drop ──
          if (!triggered) {
            brightnessHistoryRef.current.push(brightness)
            if (brightnessHistoryRef.current.length > 5) brightnessHistoryRef.current.shift()
            lastBrightnessRef.current = brightness

            if (brightnessHistoryRef.current.length >= 3) {
              const prevSamples = brightnessHistoryRef.current.slice(0, -1)
              const avgBrightness = prevSamples.reduce((a, b) => a + b, 0) / prevSamples.length
              if (avgBrightness > MIN_AVG_BRIGHTNESS &&
                  brightness < avgBrightness * (1 - BLACKOUT_THRESHOLD)) {
                console.log(`[HW Shutter] avg drop! brightness=${brightness.toFixed(0)}, avg=${avgBrightness.toFixed(0)}`)
                triggered = true
              }
            }
          }

          if (triggered) {
            lastTriggerTimeRef.current = Date.now()
            brightnessHistoryRef.current = []
            pixelChangeHistoryRef.current = []
            lastBrightnessRef.current = -1
            dipDetectedRef.current = false
            lastFrameDataRef.current = null
            onShutterRef.current()
            // Wait for cooldown before resuming
            await new Promise(r => setTimeout(r, COOLDOWN_MS))
            continue
          }
        } catch (e) {
          // Expected — keep polling
        }

        // 200ms polling (5fps, matches ARAY)
        await new Promise(r => setTimeout(r, 100))
      }
    }

    monitorLoop()
    console.log('[HW Shutter] detection started (sensitivity=' + sensitivity + ')')

    return () => {
      cancelled = true
      monitoring = false
      setIsReady(false)
      console.log('[HW Shutter] detection stopped')
    }
  }, [enabled, hasActiveTarget, videoRef, sensitivity])

  return { isReady }
}
