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
    width: 420, height: 750, minWidth: 360, minHeight: 600,
    title: 'Saatiril MC', backgroundColor: '#1a0b2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false,
    }
  })
  const args = parseArgs()
  if (args.host) {
    mainWindow.loadURL(`http://${args.host}:${args.port || 3000}/?role=mc&channel=${args.channel || 1}&socketPort=3003&password=${encodeURIComponent(args.password || '')}&v=23`)
  } else {
    mainWindow.loadFile(path.join(__dirname, 'connection.html'), { query: { role: 'mc' } })
  }
  mainWindow.on('closed', () => { mainWindow = null })
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_w, _p, cb) => cb(true))
  session.defaultSession.setPermissionCheckHandler(() => true)
  createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})

ipcMain.on('connect-to-server', (_e, url: string) => { if (mainWindow) mainWindow.loadURL(url) })
ipcMain.handle('get-connection-info', () => parseArgs())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
