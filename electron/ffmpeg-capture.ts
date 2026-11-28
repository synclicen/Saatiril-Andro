/**
 * FFmpeg Window Capture — shows the live view window of EOS Utility /
 * Imaging Edge inside Saatiril (Hot Folder camera mode).
 *
 *   ffmpeg -f gdigrab -i title=<window title> -f mjpeg pipe:1
 *     → JPEG frames are cut out of stdout
 *     → served as multipart/x-mixed-replace by a local HTTP server (127.0.0.1, random port)
 *     → shown by the renderer through <img src="http://127.0.0.1:PORT/liveview">
 *
 * gdigrab reads the window with GDI (BitBlt), so it also captures the
 * hardware-accelerated EOS Utility window that Electron's desktopCapturer
 * (DXGI) fails to grab. Two gdigrab properties shape this module:
 *   - the window title must match EXACTLY (FindWindow), so the real title
 *     list comes from desktopCapturer instead of guessed titles;
 *   - it copies the window's rectangle from the screen, so the camera window
 *     must stay open while the live view runs — when another window covers
 *     it, the capture shows the covering window instead.
 *
 * Which window shows the live view is decided from its title
 * (CAMERA_WINDOW_PATTERNS). When nothing certain is open, the renderer shows
 * a picker with a preview of every window (listCaptureWindows) and starts the
 * chosen title — the camera software's start page is never captured by itself.
 *
 * ffmpeg.exe ships with the app (package.json → build.extraResources).
 *
 * Electron is required lazily (inside the functions) so the stream parts
 * (parser, server, ffmpeg pipeline) can also run under plain Node in tests.
 */

import { spawn, type ChildProcess } from 'child_process'
import type { IpcMain, IpcMainInvokeEvent } from 'electron'
import * as fs from 'fs'
import * as http from 'http'
import * as path from 'path'

export type CaptureErrorCode =
  | 'unsupported-platform' // gdigrab only exists on Windows
  | 'ffmpeg-not-found' // bundled ffmpeg.exe missing and no ffmpeg on PATH
  | 'no-window' // no window of the camera software found
  | 'live-view-closed' // camera software is open, but its live view window is not
  | 'window-not-found' // gdigrab found no window with that exact title
  | 'capture-failed' // anything else: ffmpeg error, no frames

export interface CaptureResult {
  success: boolean
  /** multipart/x-mixed-replace stream for the renderer's <img>; set when success */
  url?: string
  /** exact window title being captured */
  title?: string
  /** stable error code; set when !success */
  error?: CaptureErrorCode
  /** error text (ffmpeg's own message when there is one); set when !success */
  detail?: string
}

/** One open window of the window picker. */
export interface CaptureWindow {
  /** desktopCapturer source id (information only — gdigrab is started by title) */
  id: string
  /** window title */
  name: string
  /** index in CAMERA_WINDOW_PATTERNS; CAMERA_WINDOW_PATTERNS.length = not camera software */
  rank: number
  /** small JPEG data URL preview; only when a thumbnail width was requested */
  thumbnail?: string
}

/** Pushed to the renderer when a running capture stops on its own. */
export interface CaptureEndedInfo {
  /** 'exited' = ffmpeg ended, 'stalled' = no frames for STALL_TIMEOUT_MS, 'error' = spawn failure */
  reason: string
  detail: string
}

export interface CaptureStatus {
  active: boolean
  title: string | null
  url: string | null
  frames: number
  ffmpeg: { path: string; version: string } | null
}

const FPS = 15
const MAX_WIDTH = 1280 // keeps a 15 fps MJPEG stream well under 1 MB/s
const FIRST_FRAME_TIMEOUT_MS = 6000
const STALL_TIMEOUT_MS = 5000
const STREAM_PATH = '/liveview'
const BOUNDARY = 'frame'
const MAX_CLIENT_BACKLOG = 4 * 1024 * 1024 // never queue more than this for one stalled reader
const MAX_PARSER_BUFFER = 16 * 1024 * 1024 // no frame gets this big → drop a garbage stream
const FFMPEG_EXE = process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg'
const LOG = '[FFmpeg Capture]'

// ── Window titles ───────────────────────────────────────────────────────
// Ordered from "certainly the live view" to "belongs to the camera software".
// Camera software does not call its live view window "live view": Imaging
// Edge (Sony / Canon) names it just "Remote", EOS Utility "Remote Live View".
// Its start page is a trap — Imaging Edge's launcher ("Imaging Edge") only
// offers Remote / Viewer / Edit and never shows a picture — so it is
// recognised (to explain what is missing) but never captured by itself.
// The camera-brand tier is no live view either: it only leads the picker.
const CAMERA_WINDOW_PATTERNS: RegExp[] = [
  /live ?view/i, // "Remote Live View" (EOS Utility), "Live View"
  /^remote(\s*[-–—:|].*)?$/i, // remote shooting window of Imaging Edge: "Remote"
  /eos utility|remote (shooting|capture|control)/i,
  /imaging edge/i, // start page (Remote / Viewer / Edit) — no live view here
  /\b(canon|eos|nikon|sony|fuji(film)?|panasonic|lumix|olympus)\b/i,
]
/** Windows up to this rank start without asking; the rest only leads the picker. */
const AUTO_START_MAX_RANK = 1
const PICK_LIST_LIMIT = 24
const PICK_THUMBNAIL_WIDTH = 320
const OWN_WINDOW_RE = /saatiril/i
const BROWSER_WINDOW_RE = /(google chrome|mozilla firefox|microsoft ?edge|brave|vivaldi|opera)\s*$/i

function cameraWindowRank(title: string): number {
  const index = CAMERA_WINDOW_PATTERNS.findIndex((re) => re.test(title))
  return index === -1 ? CAMERA_WINDOW_PATTERNS.length : index
}

/** Camera software windows only (no browsers, no Saatiril), best candidate first. */
export function rankCameraWindows(titles: string[]): string[] {
  return titles
    .filter((t) => !OWN_WINDOW_RE.test(t) && !BROWSER_WINDOW_RE.test(t))
    .filter((t) => cameraWindowRank(t) < CAMERA_WINDOW_PATTERNS.length)
    .sort((a, b) => cameraWindowRank(a) - cameraWindowRank(b) || a.localeCompare(b))
}

/** The windows that show the live view, in the order they are tried without asking. */
export function automaticCameraWindows(titles: string[]): string[] {
  return rankCameraWindows(titles)
    .filter((t) => cameraWindowRank(t) <= AUTO_START_MAX_RANK)
    .slice(0, 3)
}

/**
 * Why nothing was captured, in words for the operator. The renderer shows
 * `detail` above its window picker, so it says what to open — not just that
 * nothing was found.
 */
export function explainNoWindow(titles: string[], lastDetail?: string): CaptureResult {
  const tried = automaticCameraWindows(titles)
  if (tried.length > 0) {
    return {
      success: false,
      error: 'no-window',
      detail: `Window live view ditemukan (${tried.join(', ')}), tetapi gambarnya tidak bisa diambil${lastDetail ? `: ${lastDetail}` : '.'}`,
    }
  }
  const cameraWindows = rankCameraWindows(titles)
  if (cameraWindows.some((t) => /imaging edge/i.test(t))) {
    return {
      success: false,
      error: 'live-view-closed',
      detail:
        'Imaging Edge sudah terbuka, tetapi window "Remote" belum. ' +
        'Klik "Remote" di jendela Imaging Edge, tunggu sampai gambar kamera muncul, lalu coba lagi.',
    }
  }
  if (cameraWindows.length > 0) {
    return {
      success: false,
      error: 'live-view-closed',
      detail:
        'Software kamera sudah terbuka, tetapi window yang menampilkan gambar kamera belum. ' +
        'Buka layar live view / remote shooting di software kamera, lalu coba lagi.',
    }
  }
  return {
    success: false,
    error: 'no-window',
    detail:
      'Window software kamera (Imaging Edge / EOS Utility) tidak ditemukan. ' +
      'Buka software-nya dan tampilkan live view kameranya, lalu coba lagi.',
  }
}

/**
 * Open windows with their rank, best candidates first. Saatiril's own
 * windows are left out. Without a thumbnail width only the titles are read;
 * with one, every window comes with a small JPEG preview for the picker.
 */
export async function listCaptureWindows(thumbnailWidth = 0): Promise<CaptureWindow[]> {
  try {
    const { desktopCapturer } = require('electron') as typeof import('electron')
    const sources = await desktopCapturer.getSources({
      types: ['window'],
      thumbnailSize: { width: thumbnailWidth, height: Math.round((thumbnailWidth * 9) / 16) },
      fetchWindowIcons: false,
    })
    const windows: CaptureWindow[] = []
    for (const source of sources) {
      const name = String(source?.name || '').trim()
      // gdigrab addresses a window by title: two windows with the same title are one choice.
      if (!name || OWN_WINDOW_RE.test(name) || windows.some((w) => w.name === name)) continue
      const thumbnail = source.thumbnail
      windows.push({
        id: String(source.id),
        name,
        // a browser tab about a camera is not camera software
        rank: BROWSER_WINDOW_RE.test(name) ? CAMERA_WINDOW_PATTERNS.length : cameraWindowRank(name),
        ...(thumbnailWidth > 0 && thumbnail && !thumbnail.isEmpty()
          ? { thumbnail: `data:image/jpeg;base64,${thumbnail.toJPEG(60).toString('base64')}` }
          : {}),
      })
    }
    return windows.sort((a, b) => a.rank - b.rank || a.name.localeCompare(b.name))
  } catch (e) {
    console.error(`${LOG} listing windows failed:`, (e as Error).message)
    return []
  }
}

/** ffmpeg arguments: one window (exact title) → MJPEG on stdout. */
export function gdigrabArgs(windowTitle: string): string[] {
  return [
    '-hide_banner', '-loglevel', 'error',
    '-f', 'gdigrab',
    '-framerate', String(FPS),
    '-draw_mouse', '0',
    '-i', `title=${windowTitle}`,
    '-an',
    // at most MAX_WIDTH pixels wide, aspect ratio kept (-2 = even height)
    '-vf', `scale='min(${MAX_WIDTH},iw)':-2`,
    '-f', 'mjpeg',
    '-q:v', '5',
    'pipe:1',
  ]
}

// ── Locating ffmpeg.exe ─────────────────────────────────────────────────
let ffmpegInfo: { path: string; version: string } | null = null

function ffmpegCandidates(): string[] {
  const resources = process.resourcesPath || ''
  return [
    process.env.SAATIRIL_FFMPEG || '', // explicit override
    resources ? path.join(resources, 'ffmpeg', FFMPEG_EXE) : '', // packaged app (build.extraResources)
    path.join(__dirname, '..', 'node_modules', 'ffmpeg-static', FFMPEG_EXE), // unpackaged checkout (npm run electron:dev)
  ].filter(Boolean)
}

function parseVersion(stdout: string): string {
  // "ffmpeg version 7.0.2-static https://... Copyright ..." → "7.0.2-static"
  const firstLine = stdout.split(/\r?\n/)[0] || ''
  const match = firstLine.match(/^ffmpeg version (\S+)/)
  return match ? match[1] : firstLine.slice(0, 40)
}

function probeFfmpeg(command: string): Promise<string | null> {
  return new Promise((resolve) => {
    let done = false
    let timer: NodeJS.Timeout | undefined
    const finish = (value: string | null) => {
      if (done) return
      done = true
      if (timer) clearTimeout(timer)
      resolve(value)
    }
    let proc: ChildProcess
    try {
      proc = spawn(command, ['-version'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    } catch {
      finish(null)
      return
    }
    timer = setTimeout(() => {
      try { proc.kill() } catch { /* already gone */ }
      finish(null)
    }, 5000)
    let out = ''
    proc.stdout?.on('data', (chunk: Buffer) => { out += chunk.toString('utf8') })
    proc.on('error', () => finish(null))
    proc.on('close', (code) => finish(code === 0 ? parseVersion(out) : null))
  })
}

/** Bundled ffmpeg.exe first, then the dev dependency, then ffmpeg on PATH. */
export async function resolveFfmpeg(): Promise<{ path: string; version: string } | null> {
  if (ffmpegInfo) return ffmpegInfo
  for (const candidate of ffmpegCandidates()) {
    if (!fs.existsSync(candidate)) continue
    const version = await probeFfmpeg(candidate)
    if (version) {
      ffmpegInfo = { path: candidate, version }
      console.log(`${LOG} ffmpeg ${version} at ${candidate}`)
      return ffmpegInfo
    }
  }
  const pathVersion = await probeFfmpeg(FFMPEG_EXE)
  if (pathVersion) {
    ffmpegInfo = { path: FFMPEG_EXE, version: pathVersion }
    console.log(`${LOG} ffmpeg ${pathVersion} from PATH`)
    return ffmpegInfo
  }
  console.error(`${LOG} ffmpeg not found (tried ${ffmpegCandidates().join(', ')} and PATH)`)
  return null
}

// ── JPEG frame parser ───────────────────────────────────────────────────
const SOI = Buffer.from([0xff, 0xd8]) // start of image
const EOI = Buffer.from([0xff, 0xd9]) // end of image

/**
 * Cuts complete JPEG images out of a byte stream (the mjpeg muxer writes
 * them back to back). JPEG entropy data is byte-stuffed, so an SOI/EOI
 * cannot occur inside a frame and a plain marker search is exact. A frame
 * start must be followed by a marker byte (0xFF) — a JPEG always continues
 * with APPn/DQT/SOF right after the SOI — which rejects stray FF D8 pairs.
 * Frames may be split across any number of push() calls.
 */
export class JpegFrameParser {
  private pending: Buffer = Buffer.alloc(0)

  push(data: Buffer): Buffer[] {
    this.pending = this.pending.length === 0 ? data : Buffer.concat([this.pending, data])
    const frames: Buffer[] = []
    let offset = 0
    for (;;) {
      const soi = this.pending.indexOf(SOI, offset)
      if (soi < 0) {
        // No frame start. Drop everything except a lone 0xFF that may be
        // the first half of an SOI split across two chunks.
        const last = this.pending.length - 1
        offset = last >= 0 && this.pending[last] === 0xff ? last : this.pending.length
        break
      }
      if (soi + SOI.length >= this.pending.length) {
        offset = soi // SOI complete, the byte after it is still missing
        break
      }
      if (this.pending[soi + SOI.length] !== 0xff) {
        offset = soi + 1 // stray FF D8 inside other data — not a frame
        continue
      }
      const eoi = this.pending.indexOf(EOI, soi + SOI.length)
      if (eoi < 0) {
        // Frame not complete yet — keep it and wait for more bytes.
        offset = this.pending.length - soi > MAX_PARSER_BUFFER ? this.pending.length : soi
        break
      }
      frames.push(this.pending.subarray(soi, eoi + EOI.length))
      offset = eoi + EOI.length
    }
    this.pending = offset === 0 ? this.pending : Buffer.from(this.pending.subarray(offset))
    return frames
  }

  reset(): void {
    this.pending = Buffer.alloc(0)
  }
}

// ── MJPEG HTTP stream ───────────────────────────────────────────────────
type MjpegServer = { server: http.Server; port: number; clients: Set<http.ServerResponse> }

function writeFrame(res: http.ServerResponse, frame: Buffer): void {
  if (res.destroyed || res.writableEnded) return
  // A reader that stopped consuming (hidden tab) must not grow the buffer without bound.
  if (res.writableLength > MAX_CLIENT_BACKLOG) return
  res.write(`--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`)
  res.write(frame)
  res.write('\r\n')
}

/** Loopback-only server that streams the latest frame as multipart/x-mixed-replace. */
function openMjpegServer(getFrame: () => Buffer | null): Promise<MjpegServer> {
  const clients = new Set<http.ServerResponse>()
  const server = http.createServer((req, res) => {
    // Loopback only: refuse requests that are not addressed to 127.0.0.1 (DNS rebinding guard).
    const host = String(req.headers.host || '')
    if ((req.url || '').split('?')[0] !== STREAM_PATH || !/^127\.0\.0\.1(:\d+)?$/.test(host)) {
      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('Not found')
      return
    }
    res.writeHead(200, {
      'Content-Type': `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
      'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0',
      Pragma: 'no-cache',
      Connection: 'close',
    })
    clients.add(res)
    res.on('close', () => clients.delete(res))
    const frame = getFrame()
    if (frame) writeFrame(res, frame) // new viewers get a picture immediately
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (!address || typeof address !== 'object') {
        server.close()
        reject(new Error('MJPEG server got no port'))
        return
      }
      resolve({ server, port: address.port, clients })
    })
  })
}

// ── Capture state ───────────────────────────────────────────────────────
let ffmpegProcess: ChildProcess | null = null
let httpServer: http.Server | null = null
let httpClients: Set<http.ServerResponse> = new Set()
let httpPort = 0
let latestFrame: Buffer | null = null
let frameCount = 0
let currentTitle: string | null = null
let watchdog: NodeJS.Timeout | undefined
// A start that is still waiting for its first frame registers its settle()
// here so stopWindowCapture() can end it; `session` invalidates starts that
// are still opening their server.
let abortStart: ((result: CaptureResult) => void) | null = null
let session = 0

function lastErrorLine(stderr: string): string {
  const lines = stderr.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  return (lines[lines.length - 1] || '').slice(0, 300)
}

function classifyError(message: string): CaptureErrorCode {
  if (/can't find window|input\/output error/i.test(message)) return 'window-not-found'
  if (/enoent|not recognized|no such file/i.test(message)) return 'ffmpeg-not-found'
  return 'capture-failed'
}

function notifyCaptureEnded(info: CaptureEndedInfo): void {
  try {
    const { BrowserWindow } = require('electron') as typeof import('electron')
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed()) win.webContents.send('ffmpeg-capture:ended', info)
    }
  } catch {
    // not running under Electron (unit tests) — nothing to notify
  }
}

/** Kills ffmpeg and closes the stream server. Safe to call at any time. */
export function stopWindowCapture(): void {
  session++
  const proc = ffmpegProcess
  const server = httpServer
  const clients = httpClients
  ffmpegProcess = null
  httpServer = null
  httpClients = new Set()
  httpPort = 0
  currentTitle = null
  latestFrame = null
  if (watchdog) {
    clearTimeout(watchdog)
    watchdog = undefined
  }
  if (proc) {
    try { proc.kill() } catch { /* already gone */ }
  }
  if (server) {
    for (const res of clients) {
      try { res.destroy() } catch { /* already closed */ }
    }
    try { server.closeAllConnections() } catch { /* Node < 18.2 */ }
    try { server.close() } catch { /* not listening */ }
  }
  if (proc || server) console.log(`${LOG} stopped`)
  const abort = abortStart
  abortStart = null
  if (abort) abort({ success: false, error: 'capture-failed', detail: 'Capture dihentikan.' })
}

export function getLiveViewUrl(): string | null {
  return httpServer && httpPort > 0 ? `http://127.0.0.1:${httpPort}${STREAM_PATH}` : null
}

export function getCaptureStatus(): CaptureStatus {
  return { active: ffmpegProcess !== null, title: currentTitle, url: getLiveViewUrl(), frames: frameCount, ffmpeg: ffmpegInfo }
}

/**
 * Runs ffmpeg with `args` and streams its MJPEG stdout. Resolves as soon as
 * the first frame arrived — so a wrong window title fails fast instead of
 * showing a black picture — or with ffmpeg's error when none arrives.
 */
export async function startMjpegCapture(ffmpegPath: string, args: string[], title: string | null = null): Promise<CaptureResult> {
  stopWindowCapture()
  const mySession = session

  let stream: MjpegServer
  try {
    stream = await openMjpegServer(() => latestFrame)
  } catch (e) {
    return { success: false, error: 'capture-failed', detail: (e as Error).message }
  }
  if (mySession !== session) {
    // stopped or replaced while the server was still opening
    stream.server.close()
    return { success: false, error: 'capture-failed', detail: 'Capture dihentikan.' }
  }
  const { server, port, clients } = stream
  httpServer = server
  httpClients = clients
  httpPort = port
  currentTitle = title
  frameCount = 0

  return new Promise<CaptureResult>((resolve) => {
    let settled = false
    let stderr = ''
    const parser = new JpegFrameParser()
    const settle = (result: CaptureResult) => {
      if (settled) return
      settled = true
      if (abortStart === settle) abortStart = null
      resolve(result)
    }
    abortStart = settle

    const fail = (detail: string, code: CaptureErrorCode = classifyError(detail)) => {
      console.error(`${LOG} start failed (${code}): ${detail}`)
      settle({ success: false, error: code, detail })
      stopWindowCapture()
    }
    // A capture that was already showing stops here: tear down + tell the renderer.
    const endLater = (reason: string, detail: string) => {
      stopWindowCapture()
      console.error(`${LOG} capture ended (${reason}): ${detail}`)
      notifyCaptureEnded({ reason, detail })
    }

    const proc = spawn(ffmpegPath, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    ffmpegProcess = proc
    console.log(`${LOG} spawn: "${ffmpegPath}" ${args.join(' ')}`)

    const onStall = () => {
      const detail = lastErrorLine(stderr)
      if (frameCount === 0) {
        fail(detail || `Tidak ada frame dari FFmpeg dalam ${FIRST_FRAME_TIMEOUT_MS / 1000} detik.`, 'capture-failed')
      } else {
        endLater('stalled', detail || `Tidak ada frame selama ${STALL_TIMEOUT_MS / 1000} detik.`)
      }
    }
    const armWatchdog = (delay: number) => {
      if (watchdog) clearTimeout(watchdog)
      watchdog = setTimeout(() => {
        watchdog = undefined
        onStall()
      }, delay)
    }

    proc.stdout?.on('data', (chunk: Buffer) => {
      for (const frame of parser.push(chunk)) {
        latestFrame = frame
        frameCount++
        for (const res of clients) writeFrame(res, frame)
        armWatchdog(STALL_TIMEOUT_MS)
        if (!settled) {
          const url = getLiveViewUrl()
          console.log(`${LOG} streaming "${title}" → ${url} (first frame ${frame.length} bytes)`)
          settle({ success: true, url: url ?? undefined, title: title ?? undefined })
        }
      }
    })
    proc.stderr?.on('data', (chunk: Buffer) => { stderr += chunk.toString('utf8') })
    proc.on('error', (err) => {
      stderr += `\n${err.message}`
      if (ffmpegProcess !== proc) return // already stopped/replaced
      const code: CaptureErrorCode = /ENOENT/.test(err.message) ? 'ffmpeg-not-found' : 'capture-failed'
      if (settled) endLater('error', err.message)
      else fail(err.message, code)
    })
    proc.on('close', (code, signal) => {
      if (ffmpegProcess !== proc) return // stopWindowCapture() already handled it
      const detail = lastErrorLine(stderr) || `FFmpeg berhenti (kode ${code ?? signal ?? '?'}).`
      if (settled) endLater('exited', detail)
      else fail(detail)
    })
    armWatchdog(FIRST_FRAME_TIMEOUT_MS)
  })
}

/**
 * Start the live view. Without a title the camera window is found by its
 * real OS title — windows that show the live view only, best match first
 * (see CAMERA_WINDOW_PATTERNS). When nothing could be started the result
 * carries the reason in `detail`; the renderer then offers its window
 * picker, and a picked title comes back here as it is.
 */
export async function startWindowCapture(windowTitle?: string): Promise<CaptureResult> {
  if (process.platform !== 'win32') {
    return { success: false, error: 'unsupported-platform', detail: 'Pengambilan window (gdigrab) hanya tersedia di Windows.' }
  }
  const ffmpeg = await resolveFfmpeg()
  if (!ffmpeg) {
    return { success: false, error: 'ffmpeg-not-found', detail: 'ffmpeg.exe bawaan Saatiril tidak ditemukan.' }
  }

  // A title the user picked is used as is — no guessing.
  if (windowTitle) return startMjpegCapture(ffmpeg.path, gdigrabArgs(windowTitle), windowTitle)

  const titles = (await listCaptureWindows()).map((w) => w.name)
  const automatic = automaticCameraWindows(titles)
  console.log(
    `${LOG} camera windows: ${rankCameraWindows(titles).map((t) => JSON.stringify(t)).join(', ') || '(none)'} — trying ${automatic.map((t) => JSON.stringify(t)).join(', ') || 'none'}`,
  )
  let lastDetail: string | undefined
  for (const title of automatic) {
    const result = await startMjpegCapture(ffmpeg.path, gdigrabArgs(title), title)
    if (result.success) return result
    lastDetail = result.detail
  }
  return explainNoWindow(titles, lastDetail)
}

// ── IPC ─────────────────────────────────────────────────────────────────
export function registerFfmpegCaptureIpc(ipcMain: IpcMain): void {
  ipcMain.handle('ffmpeg-capture:start', async (_event: IpcMainInvokeEvent, windowTitle?: string) => {
    const title = typeof windowTitle === 'string' && windowTitle.trim() ? windowTitle.trim() : undefined
    try {
      return await startWindowCapture(title)
    } catch (e) {
      console.error(`${LOG} start failed:`, (e as Error).message)
      return { success: false, error: 'capture-failed', detail: (e as Error).message }
    }
  })

  // Window list for the picker: likely live view windows first, each with a preview.
  ipcMain.handle('ffmpeg-capture:windows', async () =>
    (await listCaptureWindows(PICK_THUMBNAIL_WIDTH)).slice(0, PICK_LIST_LIMIT),
  )

  ipcMain.handle('ffmpeg-capture:stop', async () => {
    stopWindowCapture()
    return true
  })

  ipcMain.handle('ffmpeg-capture:url', async () => getLiveViewUrl())

  ipcMain.handle('ffmpeg-capture:status', async () => getCaptureStatus())

  ipcMain.handle('ffmpeg-capture:check', async () => {
    const ffmpeg = await resolveFfmpeg()
    return ffmpeg ? { available: true, path: ffmpeg.path, version: ffmpeg.version } : { available: false }
  })
}
