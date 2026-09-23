/**
 * SAATIRIL — Operator Electron Client (Main Process)
 *
 * Large-window Electron app for the photo Operator.
 * It does NOT bundle the Next.js app — instead, after the user enters
 * the admin's IP + password + channel on the connection screen, it
 * calls `loadURL` to load the admin's `operator.html` over HTTP. The
 * admin's portable.exe must already be running and serving on port 3000.
 *
 * CRITICAL: This app grants camera + microphone + WASM permissions on
 * HTTP origins. Browsers (Chrome/Edge) block camera on HTTP, but
 * Electron's permission handler can grant it explicitly. This is the
 * main reason the Operator uses Electron instead of a browser tab.
 *
 * Flow:
 *   1. App launches → show connection screen (connection.html?role=operator)
 *   2. User enters IP, password, channel → press CONNECT
 *   3. Renderer calls saatirilAPI.connectToServer(url) → IPC → loadURL
 *   4. Window navigates to http://{IP}:3000/operator?channel=..&socketPort=..&password=..&v=23
 *
 * Auto-connect (QR / link launch):
 *   saatiril-operator-electron.exe --host=192.168.100.61 --port=3003 --channel=1 --password=xxx
 *
 * Permissions granted:
 *   - media (camera, microphone)              ← CRITICAL for HTTP camera
 *   - display-capture, fullscreen             ← operator uses fullscreen
 *   - clipboard-read/write                    ← QR / paste support
 *   - geolocation, notifications, midi, pointerLock, openExternal
 *   - WASM (MediaPipe / palm-detection) — via CSP strip in onHeadersReceived
 *
 * NOTE: We strip Content-Security-Policy from the admin's HTTP
 * response so the operator.html page can freely run WASM, MediaPipe,
 * inline scripts, and access the camera over HTTP.
 */

import { app, BrowserWindow, ipcMain, session, shell } from 'electron'
import * as path from 'path'
import * as os from 'os'

// ─── Role / window config ────────────────────────────────────────────────
const ROLE = 'operator'
const WIN_W = 1280
const WIN_H = 800
const WIN_TITLE = 'Saatiril Operator'

// URL pattern after connection (matches admin's operator.html — version v23)
// http://{IP}:3000/operator?channel={CH}&socketPort=3003&password={PW}&v=23
const ADMIN_HTTP_PORT = 3000
const DEFAULT_SOCKET_PORT = 3003
const URL_VERSION = '23'

// ─── Command-line args parsing ────────────────────────────────────────────
function parseArg(name: string): string | null {
  const prefix = `--${name}=`
  for (const arg of process.argv) {
    if (arg.startsWith(prefix)) {
      return arg.slice(prefix.length)
    }
  }
  return null
}

interface ConnectionInfo {
  host: string
  port: number
  channel: number
  password: string
}

function getConnectionInfoFromArgs(): ConnectionInfo | null {
  const host = parseArg('host')
  if (!host) return null
  const port = parseInt(parseArg('port') || String(DEFAULT_SOCKET_PORT), 10)
  const channel = parseInt(parseArg('channel') || '1', 10)
  const password = parseArg('password') || ''
  return { host, port, channel, password }
}

// ─── LAN info (used by some HTML pages) ───────────────────────────────────
function getLocalIps(): { name: string; address: string }[] {
  const ifaces = os.networkInterfaces()
  const result: { name: string; address: string }[] = []
  for (const [name, addrs] of Object.entries(ifaces)) {
    if (!addrs) continue
    for (const a of addrs) {
      if (a.family === 'IPv4' && !a.internal) {
        result.push({ name, address: a.address })
      }
    }
  }
  return result
}

// ─── Main window ──────────────────────────────────────────────────────────
let mainWindow: BrowserWindow | null = null

function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: WIN_W,
    height: WIN_H,
    frame: true,
    title: WIN_TITLE,
    backgroundColor: '#1a0b2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false, // preload needs ipcRenderer; sandbox=false required
      webSecurity: true, // keep security ON; permission handler grants camera
      allowRunningInsecureContent: true, // admin serves over HTTP on LAN
      zoomFactor: 1.0,
    },
  })

  // Open external links in the user's default browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  // Load connection screen first
  const connUrl = `file://${path.join(__dirname, 'connection.html')}?role=${ROLE}`
  win.loadURL(connUrl)

  return win
}

// ─── IPC handlers ─────────────────────────────────────────────────────────

ipcMain.on('connect-to-server', (event, url: string) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (!win) return
  console.log('[Operator] loadURL →', url)

  win.webContents.once('did-fail-load', (_e, errorCode, errorDesc) => {
    console.error('[Operator] load failed:', errorCode, errorDesc)
    win.webContents
      .executeJavaScript(
        `window.dispatchEvent(new CustomEvent('saatiril-connect-error', { detail: ${JSON.stringify(
          `Connection failed: ${errorDesc} (${errorCode})`
        )} }))`
      )
      .catch(() => {})
    const connUrl = `file://${path.join(__dirname, 'connection.html')}?role=${ROLE}`
    win.loadURL(connUrl)
  })

  win.webContents.once('did-finish-load', () => {
    win!.webContents
      .executeJavaScript(`window.dispatchEvent(new CustomEvent('saatiril-connect-success'))`)
      .catch(() => {})
  })

  win.loadURL(url)
})

ipcMain.on('get-connection-info', (event) => {
  event.returnValue = getConnectionInfoFromArgs()
})

ipcMain.handle('get-lan-info', () => {
  return {
    httpPort: ADMIN_HTTP_PORT,
    socketPort: DEFAULT_SOCKET_PORT,
    ips: getLocalIps(),
  }
})

// ─── Permissions (CRITICAL — HTTP camera + WASM) ──────────────────────────
function setupPermissions() {
  // Full list of permissions we will grant.
  // 'media' = camera + microphone — required for HTTP camera in operator.html.
  // 'display-capture' = getDisplayMedia
  // 'fullscreen' = Document.fullscreen
  // 'clipboard-read'/'clipboard-write' = clipboard for QR / paste
  // 'geolocation', 'midi', 'notifications', 'pointerLock', 'openExternal'
  const allowedPermissions = [
    'media',
    'display-capture',
    'fullscreen',
    'clipboard-read',
    'clipboard-write',
    'geolocation',
    'midi',
    'notifications',
    'pointerLock',
    'openExternal',
    'unknown', // sometimes requested by MediaPipe
  ]

  // setPermissionRequestHandler — called when the page calls
  // navigator.mediaDevices.getUserMedia(). Returning true grants camera.
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    const allow = allowedPermissions.includes(permission)
    console.log(`[Operator] permission request: ${permission} → ${allow}`)
    callback(allow)
  })

  // setPermissionCheckHandler — called synchronously to check whether
  // a given permission is already granted. Returning true for 'media'
  // makes the page think it has camera permission without prompting.
  session.defaultSession.setPermissionCheckHandler((_wc, permission) => {
    return allowedPermissions.includes(permission)
  })

  // Allow insecure (HTTP) content — admin's LAN server is HTTP, not HTTPS.
  session.defaultSession.webRequest.onBeforeSendHeaders((details, cb) => {
    cb({ requestHeaders: details.requestHeaders })
  })

  // Strip CSP / X-Frame-Options from admin HTTP responses so the
  // operator.html page can:
  //   - load WASM (MediaPipe palm-detection models)
  //   - run inline scripts
  //   - access camera via getUserMedia on HTTP
  //   - load scripts/styles from CDN or local file paths
  session.defaultSession.webRequest.onHeadersReceived((details, cb) => {
    const headers = details.responseHeaders || {}
    delete headers['content-security-policy']
    delete headers['Content-Security-Policy']
    delete headers['x-content-security-policy']
    delete headers['X-Content-Security-Policy']
    delete headers['content-security-policy-report-only']
    delete headers['Content-Security-Policy-Report-Only']
    delete headers['x-frame-options']
    delete headers['X-Frame-Options']
    cb({ responseHeaders: headers })
  })
}

// ─── App lifecycle ────────────────────────────────────────────────────────
app.whenReady().then(() => {
  setupPermissions()
  mainWindow = createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      mainWindow = createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  app.quit()
})

app.on('web-contents-created', (_e, contents) => {
  contents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
})
