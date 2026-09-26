import { app, BrowserWindow, ipcMain, session, Menu } from 'electron'
import * as path from 'path'
import * as fs from 'fs'
import * as http from 'http'

let mainWindow: BrowserWindow | null = null
let localServer: http.Server | null = null

function parseArgs() {
  const result: any = {}
  for (const arg of process.argv) {
    if (arg.startsWith('--host=')) result.host = arg.slice(7)
    if (arg.startsWith('--port=')) result.port = arg.slice(7)
    if (arg.startsWith('--channel=')) result.channel = arg.slice(10)
    if (arg.startsWith('--password=')) result.password = arg.slice(11)
  }
  return result
}

function startLocalServer(outDir: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const mimeTypes: Record<string, string> = {
      '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
      '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg',
      '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff': 'font/woff',
      '.woff2': 'font/woff2', '.wasm': 'application/wasm',
    }
    localServer = http.createServer((req, res) => {
      let urlPath = req.url?.split('?')[0] || '/'
      if (urlPath === '/') urlPath = '/index.html'
      let filePath = path.join(outDir, urlPath)
      if (!fs.existsSync(filePath)) filePath = path.join(outDir, urlPath + '.html')
      if (!fs.existsSync(filePath)) filePath = path.join(outDir, 'index.html')
      if (fs.existsSync(filePath)) {
        const ext = path.extname(filePath)
        res.setHeader('Content-Type', mimeTypes[ext] || 'application/octet-stream')
        fs.createReadStream(filePath).pipe(res)
      } else { res.writeHead(404); res.end('Not found') }
    })
    localServer.listen(0, '127.0.0.1', () => {
      const port = (localServer!.address() as any).port
      resolve(port)
    })
  })
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280, height: 800, minWidth: 800, minHeight: 600,
    title: 'Saatiril Operator', backgroundColor: '#1a0b2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false,
      webSecurity: false,
    }
  })
  Menu.setApplicationMenu(null)

  const args = parseArgs()
  if (args.host) {
    startLocalServer(path.join(__dirname, 'out')).then((localPort) => {
      const url = `http://127.0.0.1:${localPort}/?role=operator&host=${args.host}&channel=${args.channel || 1}&socketPort=3003&password=${encodeURIComponent(args.password || '')}`
      mainWindow?.loadURL(url)
    }).catch(console.error)
  } else {
    mainWindow.loadFile(path.join(__dirname, 'connection.html'), { query: { role: 'operator' } })
  }

  mainWindow.webContents.on('did-fail-load', (_e: any, errorCode: number, errorDesc: string, validatedURL: string) => {
    if (validatedURL.startsWith('data:')) return
    console.error('[OP] Load failed:', errorCode, errorDesc, validatedURL)
  })

  // Anti-minimize: warn user.
  // CRITICAL: restore+show+focus SYNCHRONOUSLY at the START of the handler —
  // BEFORE the async dialog. On Windows, the OS minimize button can minimize
  // the window BEFORE e.preventDefault() takes effect (an Electron race). If
  // we restore only inside the dialog's async .then(), the window stays
  // minimized while the dialog is open AND may get re-minimized after restore.
  // Restoring synchronously brings the window back ASAP — the app NEVER stays
  // minimized against the user's "Tetap Buka" choice.
  function handleMinimize(e) {
    e.preventDefault()
    const { dialog } = require('electron')
    // Defer the restore (setImmediate) so the minimize event fully processes —
    // calling restore()/show() synchronously conflicts with the OS minimize on
    // Windows and causes a BLANK screen. Only restore if IS minimized (if
    // e.preventDefault() worked, the window is visible — restore/show on a
    // visible window causes a blank flash). Don't call show() — restore() is
    // enough to un-minimize; show() on a visible window caused the blank screen.
    setImmediate(() => {
      if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isMinimized()) {
        mainWindow.restore()
        mainWindow.focus()
      }
    })
    dialog.showMessageBox(mainWindow!, {
      type: 'warning', title: 'Jangan Minimize!',
      message: 'Saatiril Operator sedang berjalan!',
      detail: 'Meminimize dapat mengganggu prosesi.\nLanjutkan minimize?',
      buttons: ['Tetap Buka', 'Minimize Saja'], defaultId: 0, cancelId: 0,
    }).then(({ response }) => {
      if (response === 1) {
        // Minimize Saja — re-minimize + re-attach handler on restore
        mainWindow!.removeAllListeners('minimize')
        mainWindow!.minimize()
        mainWindow!.once('restore', () => { mainWindow!.on('minimize', handleMinimize) })
      }
      // else: Tetap Buka — setImmediate restore above handled the OS-race case;
      // if e.preventDefault() worked, the window was never minimized. No blank.
    })
  }
  mainWindow.on('minimize', handleMinimize)

  // Prevent screen sleep
  const { powerSaveBlocker } = require('electron')
  powerSaveBlocker.start('prevent-display-sleep')

  mainWindow.on('closed', () => { mainWindow = null })
}

app.whenReady().then(async () => {
  session.defaultSession.setPermissionRequestHandler((_w: any, _p: any, cb: (arg0: boolean) => void) => cb(true))
  session.defaultSession.setPermissionCheckHandler(() => true)
  createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})

ipcMain.on('connect-to-server', async (_e: any, connectUrl: string) => {
  try {
    const url = new URL(connectUrl)
    const host = url.searchParams.get('host') || url.hostname
    const channel = url.searchParams.get('channel') || '1'
    const socketPort = url.searchParams.get('socketPort') || '3003'
    const password = url.searchParams.get('password') || ''
    const outDir = path.join(__dirname, 'out')
    const localPort = await startLocalServer(outDir)
    const localUrl = `http://127.0.0.1:${localPort}/?role=operator&host=${host}&channel=${channel}&socketPort=${socketPort}&password=${encodeURIComponent(password)}`
    mainWindow?.loadURL(localUrl)
  } catch (err) { console.error('[OP] Connect error:', err) }
})

ipcMain.handle('get-connection-info', () => parseArgs())

// Save photo to disk (operator local copy — redundancy with admin)
ipcMain.handle('save-photo', async (_event, data: { base64Data: string; filename: string; targetFolder: string }) => {
  try {
    const { base64Data, filename, targetFolder } = data
    if (!targetFolder) {
      console.warn('[SAATIRIL OP-MAIN] save-photo: no targetFolder — skipping local save (admin will save via PHOTOS_SAVED)')
      return null
    }
    fs.mkdirSync(targetFolder, { recursive: true })
    const base64 = base64Data.replace(/^data:image\/\w+;base64,/, '')
    const buffer = Buffer.from(base64, 'base64')
    const filePath = path.join(targetFolder, filename)
    fs.writeFileSync(filePath, buffer)
    console.log(`[SAATIRIL OP-MAIN] Photo saved (local redundancy): ${filePath} (${(buffer.length / 1024).toFixed(1)}KB)`)
    return filePath
  } catch (err: any) {
    console.error('[SAATIRIL OP-MAIN] Failed to save photo:', err.message)
    return null
  }
})
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
