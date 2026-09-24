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

// Start local HTTP server to serve Next.js static export
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
      // Try exact file
      let filePath = path.join(outDir, urlPath)
      if (!fs.existsSync(filePath)) {
        // Try .html extension
        filePath = path.join(outDir, urlPath + '.html')
      }
      if (!fs.existsSync(filePath)) {
        // Fallback to index.html (SPA routing)
        filePath = path.join(outDir, 'index.html')
      }
      if (fs.existsSync(filePath)) {
        const ext = path.extname(filePath)
        res.setHeader('Content-Type', mimeTypes[ext] || 'application/octet-stream')
        fs.createReadStream(filePath).pipe(res)
      } else {
        res.writeHead(404)
        res.end('Not found')
      }
    })
    localServer.listen(0, '127.0.0.1', () => {
      const port = (localServer!.address() as any).port
      console.log('[MC] Local server on http://127.0.0.1:' + port)
      resolve(port)
    })
  })
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 420, height: 750, minWidth: 360, minHeight: 600,
    title: 'Saatiril MC', backgroundColor: '#1a0b2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false,
      webSecurity: false,
    }
  })
  Menu.setApplicationMenu(null) // Remove menu bar

  const args = parseArgs()
  if (args.host) {
    // Auto-connect: start local server, then load page with admin connection params
    startLocalServer(path.join(__dirname, 'out')).then((localPort) => {
      const url = `http://127.0.0.1:${localPort}/?role=mc&host=${args.host}&channel=${args.channel || 1}&socketPort=3003&password=${encodeURIComponent(args.password || '')}`
      console.log('[MC] Loading:', url)
      mainWindow?.loadURL(url)
    }).catch((err) => {
      console.error('[MC] Failed to start local server:', err)
    })
  } else {
    // Show connection screen
    mainWindow.loadFile(path.join(__dirname, 'connection.html'), { query: { role: 'mc' } })
  }

  mainWindow.webContents.on('did-fail-load', (_e: any, errorCode: number, errorDesc: string, validatedURL: string) => {
    if (validatedURL.startsWith('data:')) return
    console.error('[MC] Load failed:', errorCode, errorDesc, validatedURL)
    mainWindow?.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
      `<html><body style="background:#1a0b2e;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center"><div><h2 style="color:#ef4444">Gagal</h2><p style="color:#c4b5fd;font-size:12px">${errorDesc}</p></div></body></html>`
    ))
  })

  mainWindow.on('closed', () => { mainWindow = null })
}

app.whenReady().then(async () => {
  session.defaultSession.setPermissionRequestHandler((_w: any, _p: any, cb: (arg0: boolean) => void) => cb(true))
  session.defaultSession.setPermissionCheckHandler(() => true)
  createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})

// Connection screen → start local server + load page
ipcMain.on('connect-to-server', async (_e: any, connectUrl: string) => {
  console.log('[MC] Connect request:', connectUrl)
  // Parse the admin URL to get connection params
  try {
    const url = new URL(connectUrl)
    const host = url.searchParams.get('host') || url.hostname
    const channel = url.searchParams.get('channel') || '1'
    const socketPort = url.searchParams.get('socketPort') || '3003'
    const password = url.searchParams.get('password') || ''
    
    // Start local server
    const outDir = path.join(__dirname, 'out')
    if (!fs.existsSync(outDir)) {
      console.error('[MC] out/ directory not found at', outDir)
      return
    }
    const localPort = await startLocalServer(outDir)
    const localUrl = `http://127.0.0.1:${localPort}/?role=mc&host=${host}&channel=${channel}&socketPort=${socketPort}&password=${encodeURIComponent(password)}`
    console.log('[MC] Loading local page:', localUrl)
    mainWindow?.loadURL(localUrl)
  } catch (err) {
    console.error('[MC] Connect error:', err)
  }
})

ipcMain.handle('get-connection-info', () => parseArgs())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
