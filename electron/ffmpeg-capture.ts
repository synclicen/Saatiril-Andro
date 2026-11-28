/**
 * FFmpeg Window Capture — captures EOS Utility / Imaging Edge window
 * using FFmpeg gdigrab (GDI-based, captures hardware-accelerated windows).
 * 
 * Outputs MJPEG stream to a local HTTP server that the renderer
 * displays via <img src="http://127.0.0.1:PORT/liveview">.
 * 
 * gdigrab uses Windows GDI which CAN capture DirectX/hardware-accelerated
 * windows — unlike Electron's desktopCapturer which uses DXGI and fails.
 */

import { spawn, ChildProcess } from 'child_process'
import * as http from 'http'
import * as path from 'path'
import * as fs from 'fs'
import * as os from 'os'

let ffmpegProcess: ChildProcess | null = null
let httpServer: http.Server | null = null
let httpPort = 0
let latestFrame: Buffer | null = null
let captureActive = false

function getFfmpegPath(): string {
  const candidates = [
    path.join(__dirname, 'ffmpeg.exe'),
    path.join(__dirname, '..', 'ffmpeg.exe'),
    path.join(process.resourcesPath || '', 'ffmpeg.exe'),
    path.join(process.resourcesPath || '', 'app.asar.unpacked', 'ffmpeg.exe'),
    'ffmpeg', // system PATH
  ]
  for (const p of candidates) {
    if (p !== 'ffmpeg' && fs.existsSync(p)) return p
  }
  // Try system PATH
  return 'ffmpeg'
}

/**
 * Start capturing a window by title using FFmpeg gdigrab.
 * The window title can be partial — FFmpeg matches it.
 * Returns the HTTP URL for the MJPEG stream.
 */
export function startWindowCapture(windowTitle: string): { success: boolean; url?: string; error?: string } {
  if (captureActive) {
    stopWindowCapture()
  }

  const ffmpegPath = getFfmpegPath()
  console.log('[FFmpeg Capture] Using:', ffmpegPath)

  // FFmpeg gdigrab command:
  // -f gdigrab → GDI-based capture (works with DirectX windows)
  // -i title:"..." → capture window by title
  // -f mjpeg → output as MJPEG
  // -q:v 5 → quality (1-31, lower=better)
  // -r 15 → 15 fps
  // -an → no audio
  // pipe:1 → output to stdout
  const args = [
    '-f', 'gdigrab',
    '-framerate', '15',
    '-i', `title=${windowTitle}`,
    '-f', 'mjpeg',
    '-q:v', '5',
    '-an',
    '-s', '640x480', // downscale for performance
    'pipe:1',
  ]

  try {
    ffmpegProcess = spawn(ffmpegPath, args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    })

    let buffer = Buffer.alloc(0)

    ffmpegProcess.stdout?.on('data', (data: Buffer) => {
      // Accumulate data and extract JPEG frames
      buffer = Buffer.concat([buffer, data])
      // Find JPEG SOI (0xFFD8) and EOI (0xFFD9)
      while (true) {
        const soi = buffer.indexOf(Buffer.from([0xff, 0xd8]))
        if (soi < 0) break
        const eoi = buffer.indexOf(Buffer.from([0xff, 0xd9]), soi + 2)
        if (eoi < 0) break
        // Extract complete JPEG frame
        const frame = buffer.subarray(soi, eoi + 2)
        latestFrame = Buffer.from(frame) // copy
        // Remove processed data
        buffer = buffer.subarray(eoi + 2)
      }
    })

    ffmpegProcess.stderr?.on('data', (data: Buffer) => {
      const msg = data.toString()
      if (msg.includes('error') || msg.includes('Error')) {
        console.error('[FFmpeg Capture] stderr:', msg.trim())
      }
    })

    ffmpegProcess.on('error', (err) => {
      console.error('[FFmpeg Capture] Process error:', err.message)
      captureActive = false
    })

    ffmpegProcess.on('exit', (code) => {
      console.log('[FFmpeg Capture] Process exited, code:', code)
      captureActive = false
    })

    // Start HTTP server to serve the MJPEG stream
    httpServer = http.createServer((req, res) => {
      if (req.url?.startsWith('/liveview')) {
        res.writeHead(200, {
          'Cache-Control': 'no-store',
          'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
        })
        const sendFrame = () => {
          if (res.destroyed) return
          if (latestFrame && latestFrame.length > 0) {
            res.write('--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ' + latestFrame.length + '\r\n\r\n')
            res.write(latestFrame)
            res.write('\r\n')
          }
          setTimeout(sendFrame, 66) // ~15fps
        }
        sendFrame()
        req.on('close', () => {})
      } else {
        res.writeHead(404)
        res.end('Not found')
      }
    })

    httpServer.listen(0, '127.0.0.1', () => {
      const addr = httpServer?.address() as any
      if (addr && typeof addr === 'object') {
        httpPort = addr.port
        console.log(`[FFmpeg Capture] MJPEG server on http://127.0.0.1:${httpPort}/liveview`)
      }
    })

    captureActive = true
    const url = `http://127.0.0.1:${httpPort || 0}/liveview`
    // Wait a moment for the port to be assigned
    return { success: true, url }
  } catch (e) {
    return { success: false, error: (e as Error).message }
  }
}

/** Get the live view URL (or null if not running). */
export function getLiveViewUrl(): string | null {
  if (!captureActive || httpPort === 0) return null
  return `http://127.0.0.1:${httpPort}/liveview`
}

/** Stop the FFmpeg capture + HTTP server. */
export function stopWindowCapture() {
  if (ffmpegProcess) {
    try { ffmpegProcess.kill('SIGTERM') } catch (e) {}
    ffmpegProcess = null
  }
  if (httpServer) {
    try { httpServer.close() } catch (e) {}
    httpServer = null
  }
  latestFrame = null
  captureActive = false
  httpPort = 0
  console.log('[FFmpeg Capture] Stopped')
}

/** Check if capture is running. */
export function isCaptureActive(): boolean {
  return captureActive
}

// ── IPC Registration ──────────────────────────────────────────────
export function registerFfmpegCaptureIpc(ipcMain: any) {
  ipcMain.handle('ffmpeg-capture:start', async (_e: any, windowTitle: string) => {
    // Wait for HTTP server to assign a port
    const result = startWindowCapture(windowTitle)
    if (result.success) {
      // Wait a bit for the port to be assigned
      await new Promise(r => setTimeout(r, 500))
      const url = getLiveViewUrl()
      return { success: true, url }
    }
    return result
  })

  ipcMain.handle('ffmpeg-capture:stop', async () => {
    stopWindowCapture()
    return true
  })

  ipcMain.handle('ffmpeg-capture:url', async () => {
    return getLiveViewUrl()
  })

  ipcMain.handle('ffmpeg-capture:check', async () => {
    // Check if ffmpeg is available
    return new Promise((resolve) => {
      const proc = spawn(getFfmpegPath(), ['-version'], { stdio: 'pipe', windowsHide: true })
      proc.on('error', () => resolve({ available: false }))
      proc.on('exit', (code) => resolve({ available: code === 0 }))
      setTimeout(() => resolve({ available: false }), 3000)
    })
  })
}
