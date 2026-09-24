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

  // Anti-minimize: warn user
  mainWindow.on('minimize', (e) => {
    e.preventDefault()
    const { dialog } = require('electron')
    dialog.showMessageBox(mainWindow!, {
      type: 'warning', title: 'Jangan Minimize!',
      message: 'Saatiril Operator sedang berjalan!',
      detail: 'Meminimize dapat mengganggu prosesi.\nLanjutkan minimize?',
      buttons: ['Tetap Buka', 'Minimize Saja'], defaultId: 0, cancelId: 0,
    }).then(({ response }) => {
      if (response === 1) {
        mainWindow!.removeAllListeners('minimize')
        mainWindow!.minimize()
        mainWindow!.once('restore', () => {
          mainWindow!.on('minimize', preventMin)
        })
      }
    })
  })
  function preventMin(e) {
    e.preventDefault()
    const { dialog } = require('electron')
    dialog.showMessageBox(mainWindow!, {
      type: 'warning', title: 'Jangan Minimize!',
      message: 'Saatiril Operator sedang berjalan!',
      detail: 'Meminimize dapat mengganggu prosesi.\nLanjutkan minimize?',
      buttons: ['Tetap Buka', 'Minimize Saja'], defaultId: 0, cancelId: 0,
    }).then(({ response }) => {
      if (response === 1) {
        mainWindow!.removeAllListeners('minimize')
        mainWindow!.minimize()
        mainWindow!.once('restore', () => { mainWindow!.on('minimize', preventMin) })
      }
    })
  }

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
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
