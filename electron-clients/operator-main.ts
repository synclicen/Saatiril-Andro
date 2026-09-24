import { app, BrowserWindow, ipcMain, session } from 'electron'
import * as path from 'path'

let mainWindow: BrowserWindow | null = null

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
  
  mainWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
    '<html><body style="background:#1a0b2e;color:#d4af37;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center"><h1 style="font-size:24px">SAATIRIL OPERATOR</h1><p style="color:#c4b5fd;font-size:12px">Memuat...</p></div></body></html>'
  ))
  
  const args = parseArgs()
  if (args.host) {
    const url = `http://${args.host}:${args.port || 3000}/?role=operator&channel=${args.channel || 1}&socketPort=3003&password=${encodeURIComponent(args.password || '')}&v=24`
    console.log('[OP] Auto-connecting to:', url)
    setTimeout(() => mainWindow?.loadURL(url), 500)
  } else {
    setTimeout(() => {
      mainWindow?.loadFile(path.join(__dirname, 'connection.html'), { query: { role: 'operator' } })
    }, 500)
  }

  mainWindow.webContents.on('did-fail-load', (_e: any, errorCode: number, errorDesc: string, validatedURL: string) => {
    console.error('[OP] Page failed to load:', errorCode, errorDesc, validatedURL)
    mainWindow?.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
      `<html><body style="background:#1a0b2e;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center"><div><h2 style="color:#ef4444">Gagal Menghubungkan</h2><p style="color:#c4b5fd;font-size:12px">Error: ${errorDesc}<br>URL: ${validatedURL}<br><br>Pastikan admin berjalan dan IP benar.</p><button onclick="location.reload()" style="margin-top:16px;padding:10px 24px;background:#d4af37;color:#1a0b2e;border:none;border-radius:8px;font-weight:bold;cursor:pointer">Coba Lagi</button></div></body></html>`
    ))
  })

  mainWindow.webContents.on('console-message', (_e: any, level: number, message: string, line: number, sourceId: string) => {
    if (level === 3) {
      console.error('[OP] Console error:', message, 'at', sourceId, 'line', line)
    }
  })

  mainWindow.on('closed', () => { mainWindow = null })
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_w: any, _p: any, cb: (arg0: boolean) => void) => cb(true))
  session.defaultSession.setPermissionCheckHandler(() => true)
  createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})

ipcMain.on('connect-to-server', (_e: any, url: string) => {
  console.log('[OP] Connecting to:', url)
  if (mainWindow) {
    mainWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
      '<html><body style="background:#1a0b2e;color:#d4af37;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center"><h1 style="font-size:24px">SAATIRIL OPERATOR</h1><p style="color:#c4b5fd;font-size:12px">Menghubungkan ke server...</p></div></body></html>'
    ))
    setTimeout(() => mainWindow?.loadURL(url), 300)
  }
})
ipcMain.handle('get-connection-info', () => parseArgs())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
