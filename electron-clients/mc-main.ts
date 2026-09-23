/**
 * SAATIRIL — MC Electron Client (Main Process)
 *
 * Small-window Electron app for the Master of Ceremony (MC).
 * It does NOT bundle the Next.js app — instead, after the user enters
 * the admin's IP + password + channel on the connection screen, it
 * calls `loadURL` to load the admin's Next.js app over HTTP. The admin's
 * portable.exe must already be running and serving on port 3000.
 *
 * CRITICAL: This loads the Next.js ROOT page (not mc.html) with a ?role=mc
 * query parameter. The Next.js page.tsx detects ?role=mc and renders the
 * React <McPanel /> component directly (no admin dashboard, no license gate,
 * no hub/setup screens). The same proven React panel used by the admin app
 * is reused here — only the chrome (license, hub, tabs) is skipped.
 *
 * Flow:
 *   1. App launches → show connection screen (connection.html?role=mc)
 *   2. User enters IP, password, channel → press CONNECT
 *   3. Renderer calls saatirilAPI.connectToServer(url) → IPC → loadURL
 *   4. Window navigates to http://{IP}:3000/?role=mc&channel=..&socketPort=..&password=..&v=23
 *   5. Next.js page.tsx detects ?role=mc → renders <McPanel /> only
 *   6. McPanel calls connectSocket() which reads socketPort from URL params
 *      and connects to http://{IP}:3003 (admin's socket.io server)
 *
 * Auto-connect (QR / link launch):
 *   saatiril-mc-electron.exe --host=192.168.100.61 --port=3003 --channel=1 --password=xxx
 *   → preload exposes window.electronConnectionInfo → connection.html auto-fills + auto-connects
 *
 * Permissions:
 *   - Media (camera/microphone) allowed but NOT required for MC.
 *   - Allow running insecure content (HTTP) — admin serves over HTTP on LAN.
 *   - Allow WASM (asm.js / WebAssembly) — harmless for MC.
 */

import { app, BrowserWindow, ipcMain, session, shell } from 'electron'
import * as path from 'path'
import * as os from 'os'

// ─── Role / window config ────────────────────────────────────────────────
const ROLE = 'mc'
const WIN_W = 420
const WIN_H = 750
const WIN_TITLE = 'Saatiril MC'

// URL pattern after connection — loads the Next.js root page with ?role=mc.
// The admin's portable.exe serves the Next.js static export at http://{IP}:3000/.
// page.tsx detects ?role=mc and renders <McPanel /> directly (skipping license/hub).
// http://{IP}:3000/?role=mc&channel={CH}&socketPort=3003&password={PW}&v=23
const ADMIN_HTTP_PORT = 3000
const DEFAULT_SOCKET_PORT = 3003
const URL_VERSION = '23'

// ─── Command-line args parsing ────────────────────────────────────────────
// Parses args like:  --host=192.168.100.61 --port=3003 --channel=1 --password=xxx
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

// ─── LAN info (used by some HTML pages that call getLanInfo) ──────────────
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
      sandbox: false, // preload needs ipcRenderer; sandbox=false is required
      webSecurity: true,
      allowRunningInsecureContent: true, // admin serves over HTTP on LAN
      zoomFactor: 1.0,
    },
  })

  // Open external links in the user's default browser (not inside Electron)
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

// Renderer (connection screen) → main: load the admin server URL
ipcMain.on('connect-to-server', (event, url: string) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (!win) return
  console.log('[MC] loadURL →', url)

  // Catch load failures → send error event back to renderer
  win.webContents.once('did-fail-load', (_e, errorCode, errorDesc) => {
    console.error('[MC] load failed:', errorCode, errorDesc)
    win.webContents
      .executeJavaScript(
        `window.dispatchEvent(new CustomEvent('saatiril-connect-error', { detail: ${JSON.stringify(
          `Connection failed: ${errorDesc} (${errorCode})`
        )} }))`
      )
      .catch(() => {})
    // Reload the connection screen so the user can retry
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

// Renderer (preload) → main: get command-line connection info
ipcMain.on('get-connection-info', (event) => {
  event.returnValue = getConnectionInfoFromArgs()
})

// Renderer → main: get LAN info (used by some HTML pages)
ipcMain.handle('get-lan-info', () => {
  return {
    httpPort: ADMIN_HTTP_PORT,
    socketPort: DEFAULT_SOCKET_PORT,
    ips: getLocalIps(),
  }
})

// Renderer → main: open URL in default browser (for Web Bluetooth etc.)
ipcMain.handle('open-in-browser', (_event, url: string) => {
  if (typeof url === 'string' && url.length > 0) {
    shell.openExternal(url).catch((e) => console.error('[MC] open-in-browser failed:', e))
    return true
  }
  return false
})

// ─── Permissions ──────────────────────────────────────────────────────────
// MC doesn't strictly need camera, but allow media + display-capture
// just in case the page calls getUserMedia for any reason.
function setupPermissions() {
  const allowed = [
    'media',
    'display-capture',
    'fullscreen',
    'clipboard-read',
    'clipboard-write',
    'openExternal',
    'geolocation',
  ]

  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(allowed.includes(permission))
  })

  session.defaultSession.setPermissionCheckHandler((_wc, permission) => {
    return allowed.includes(permission)
  })

  // Strip CSP / X-Frame-Options from admin HTTP responses so the
  // Next.js app (loaded with ?role=mc) can freely run WASM, inline scripts
  // (Next.js uses inline scripts for hydration), and access camera over HTTP
  // without CSP restrictions.
  session.defaultSession.webRequest.onHeadersReceived((details, cb) => {
    const headers = details.responseHeaders || {}
    delete headers['content-security-policy']
    delete headers['Content-Security-Policy']
    delete headers['x-content-security-policy']
    delete headers['X-Content-Security-Policy']
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

// Prevent creating additional windows from the page (MC is single-window)
app.on('web-contents-created', (_e, contents) => {
  contents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
})
