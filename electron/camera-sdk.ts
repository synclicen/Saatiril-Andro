// @ts-nocheck
/**
 * camera-sdk.ts — Unified camera SDK bridge (Sony + Canon)
 *
 * Sony: calls sony-cli.exe (compiled from Sony Camera Remote SDK)
 * Canon: calls canon-cli.ps1 (uses FC.SDK — pure C#, no DLL needed)
 *
 * Both output JSON: { success, cameras/base64/error }
 */

import { execFile } from 'child_process'
import * as path from 'path'
import * as os from 'os'
import * as fs from 'fs'

function isWindows(): boolean { return os.platform() === 'win32' }

function getSonyCliPath(): string {
  // In packaged app, __dirname is the app root or electron/
  const candidates = [
    path.join(__dirname, 'sony-sdk', 'sony-cli.exe'),
    path.join(__dirname, 'electron', 'sony-sdk', 'sony-cli.exe'),
    path.join(process.resourcesPath || '', 'electron', 'sony-sdk', 'sony-cli.exe'),
  ]
  for (const p of candidates) {
    if (fs.existsSync(p)) return p
  }
  return candidates[0] // return first even if not found (will error)
}

function getCanonScriptPath(): string {
  return path.join(__dirname, 'canon-sdk', 'canon-cli.ps1')
}

export interface SdkCamera {
  id: string
  brand: string
  model: string
  name: string
}

/** Detect cameras via Sony SDK CLI */
export async function listSonyCameras(): Promise<SdkCamera[]> {
  if (!isWindows()) return []
  return new Promise((resolve) => {
    const cliPath = getSonyCliPath()
    if (!fs.existsSync(cliPath)) { resolve([]); return }
    execFile(cliPath, ['list'], { timeout: 15000, maxBuffer: 10 * 1024 * 1024 },
      (err: any, stdout: string, _stderr: string) => {
        if (err) { console.warn('[Sony CLI] list error:', err.message); resolve([]); return }
        try {
          const resp = JSON.parse(stdout.trim())
          if (resp.success && resp.cameras) {
            resolve(resp.cameras.map((c: any, i: number) => ({
              id: `sony:${i}`,
              brand: 'Sony',
              model: c.model || '',
              name: c.name || c.model || 'Sony Camera',
            })))
          } else { resolve([]) }
        } catch (e) { resolve([]) }
      })
  })
}

/** Get live view frame from Sony camera */
export async function getSonyLiveViewFrame(camIndex: number): Promise<string | null> {
  if (!isWindows()) return null
  return new Promise((resolve) => {
    const cliPath = getSonyCliPath()
    if (!fs.existsSync(cliPath)) { resolve(null); return }
    execFile(cliPath, ['liveview', String(camIndex)], { timeout: 30000, maxBuffer: 30 * 1024 * 1024 },
      (err: any, stdout: string, _stderr: string) => {
        if (err) { resolve(null); return }
        try {
          const resp = JSON.parse(stdout.trim())
          if (resp.success && resp.base64) resolve(resp.base64)
          else resolve(null)
        } catch (e) { resolve(null) }
      })
  })
}

/** Capture photo from Sony camera */
export async function captureSonyPhoto(camIndex: number): Promise<string | null> {
  if (!isWindows()) return null
  return new Promise((resolve) => {
    const cliPath = getSonyCliPath()
    if (!fs.existsSync(cliPath)) { resolve(null); return }
    execFile(cliPath, ['capture', String(camIndex)], { timeout: 30000, maxBuffer: 50 * 1024 * 1024 },
      (err: any, stdout: string, _stderr: string) => {
        if (err) { resolve(null); return }
        try {
          const resp = JSON.parse(stdout.trim())
          if (resp.success && resp.base64) resolve(resp.base64)
          else resolve(null)
        } catch (e) { resolve(null) }
      })
  })
}

/** Detect cameras via Canon FC.SDK (C# script) */
export async function listCanonCameras(): Promise<SdkCamera[]> {
  if (!isWindows()) return []
  return new Promise((resolve) => {
    const scriptPath = getCanonScriptPath()
    if (!fs.existsSync(scriptPath)) { resolve([]); return }
    execFile('powershell',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath, 'list'],
      { timeout: 20000, maxBuffer: 10 * 1024 * 1024 },
      (err: any, stdout: string, _stderr: string) => {
        if (err) { console.warn('[Canon CLI] list error:', err.message); resolve([]); return }
        try {
          const resp = JSON.parse(stdout.trim())
          if (resp.success && resp.cameras) {
            resolve(resp.cameras.map((c: any, i: number) => ({
              id: `canon:${i}`,
              brand: 'Canon',
              model: c.model || '',
              name: c.name || 'Canon Camera',
            })))
          } else { resolve([]) }
        } catch (e) { resolve([]) }
      })
  })
}

/** Get live view frame from Canon camera */
export async function getCanonLiveViewFrame(camIndex: number): Promise<string | null> {
  if (!isWindows()) return null
  return new Promise((resolve) => {
    const scriptPath = getCanonScriptPath()
    if (!fs.existsSync(scriptPath)) { resolve(null); return }
    execFile('powershell',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath, 'liveview', String(camIndex)],
      { timeout: 30000, maxBuffer: 30 * 1024 * 1024 },
      (err: any, stdout: string, _stderr: string) => {
        if (err) { resolve(null); return }
        try {
          const resp = JSON.parse(stdout.trim())
          if (resp.success && resp.base64) resolve(resp.base64)
          else resolve(null)
        } catch (e) { resolve(null) }
      })
  })
}

/** List ALL cameras (Sony + Canon combined) */
export async function listAllCameras(): Promise<SdkCamera[]> {
  const [sony, canon] = await Promise.all([
    listSonyCameras().catch(() => []),
    listCanonCameras().catch(() => []),
  ])
  return [...sony, ...canon]
}

// ── IPC registration ──────────────────────────────────────────────
export function registerCameraSdkIpc(ipcMain: any, getAllWindows?: () => any[]) {
  ipcMain.handle('camera-sdk:list', async () => {
    return await listAllCameras()
  })

  ipcMain.handle('camera-sdk:liveview', async (_e: any, cameraId: string) => {
    if (cameraId.startsWith('sony:')) {
      const idx = parseInt(cameraId.slice('sony:'.length), 10)
      return await getSonyLiveViewFrame(idx)
    }
    if (cameraId.startsWith('canon:')) {
      const idx = parseInt(cameraId.slice('canon:'.length), 10)
      return await getCanonLiveViewFrame(idx)
    }
    return null
  })

  ipcMain.handle('camera-sdk:capture', async (_e: any, cameraId: string) => {
    if (cameraId.startsWith('sony:')) {
      const idx = parseInt(cameraId.slice('sony:'.length), 10)
      return await captureSonyPhoto(idx)
    }
    // Canon capture not yet implemented
    return null
  })

  // Install Sony camera driver (libusbK) — one UAC prompt, then persistent
  ipcMain.handle('camera-sdk:install-sony-driver', async () => {
    if (!isWindows()) return { success: false, message: 'Windows only' }
    return new Promise((resolve) => {
      const driverScript = path.join(__dirname, 'sony-sdk', 'driver', 'install-driver.ps1')
      if (!fs.existsSync(driverScript)) {
        resolve({ success: false, message: 'Driver script not found: ' + driverScript })
        return
      }
      execFile('powershell',
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', driverScript, 'install'],
        { timeout: 120000, maxBuffer: 1024 * 1024 },
        (err: any, stdout: string, _stderr: string) => {
          if (err) {
            resolve({ success: false, message: 'Driver install error: ' + err.message })
            return
          }
          try {
            const resp = JSON.parse(stdout.trim())
            resolve(resp)
          } catch (e) {
            resolve({ success: false, message: 'Parse error: ' + stdout.substring(0, 200) })
          }
        })
    })
  })
}
