/**
 * SAATIRIL — Electron Clients Shared Preload Script
 *
 * Used by both `mc-main.ts` and `operator-main.ts`.
 * Exposes a minimal `saatirilAPI` to the renderer (connection screen + the
 * Next.js page that gets loaded after connect):
 *
 *   - isElectron: true   (so the Next.js page knows it is in Electron)
 *   - connectToServer(url): send a loadURL request to the main process
 *   - getConnectionInfo(): return command-line args (host/port/channel/password)
 *                          so the connection screen can auto-fill + auto-connect
 *                          when the app is launched from a QR code / link.
 *   - getLanInfo(): return localhost LAN info (used by some HTML pages)
 *   - savePhoto(): fake success — the actual photo is sent to the admin via
 *                  the PHOTOS_SAVED socket event. We return a fake path so the
 *                  operator panel doesn't show "Gagal Simpan ke Disk" toasts.
 *   - getLicenseStatus(): always valid — client apps don't enforce license
 *   - activateLicense(): no-op stub
 *   - getMachineId(): returns a fixed client ID
 *   - openInBrowser(): opens URL in user's default browser
 *
 * NOTE: contextIsolation is enabled, so these are exposed through contextBridge.
 *       sandbox=false in main.ts so that ipcRenderer works in preload.
 */

import { contextBridge, ipcRenderer } from 'electron'

// ─── Fake path builder (so savePhoto looks like it succeeded) ──────────────
// The operator panel checks if the returned path is truthy; if null, it shows
// an error toast. Returning a fake path keeps the UX clean while the actual
// photo is uploaded via the PHOTOS_SAVED socket event to the admin (which
// saves it to disk on the admin side).
function fakePath(data: { filename: string; targetFolder: string }): string {
  const sep = process.platform === 'win32' ? '\\' : '/'
  return `${data.targetFolder}${sep}${data.filename}`
}

contextBridge.exposeInMainWorld('saatirilAPI', {
  isElectron: true,

  /**
   * Ask the main process to loadURL the given admin server URL.
   * The connection screen calls this after the user presses CONNECT.
   */
  connectToServer: (url: string): void => {
    ipcRenderer.send('connect-to-server', url)
  },

  /**
   * Get connection info if the app was launched with command-line args:
   *   --host=192.168.100.61 --port=3003 --channel=1 --password=xxx
   * Returns null if no args were provided.
   *
   * Also exposed as `window.electronConnectionInfo` for convenience.
   */
  getConnectionInfo: (): {
    host: string
    port: number
    channel: number
    password: string
  } | null => {
    return ipcRenderer.sendSync('get-connection-info')
  },

  /**
   * Get LAN info (local IP addresses + ports) — used by some HTML pages
   * that call `window.saatirilAPI.getLanInfo()`. Client apps don't run a
   * server, so we just return localhost info from the main process.
   */
  getLanInfo: (): Promise<{
    httpPort: number
    socketPort: number
    ips: { name: string; address: string }[]
  }> => {
    return ipcRenderer.invoke('get-lan-info')
  },

  /**
   * Save photo — FAKE success in client apps.
   *
   * The operator panel calls this to "save" a photo to disk. In client mode,
   * we DON'T actually write to disk — the photo is already being uploaded
   * to the admin via the PHOTOS_SAVED socket event (which the admin saves
   * to its own disk). We return a fake path so the operator panel logs
   * "Photo saved to disk" instead of showing the "Gagal Simpan ke Disk"
   * error toast.
   */
  savePhoto: (data: {
    base64Data: string
    filename: string
    targetFolder: string
  }): Promise<string> => {
    return Promise.resolve(fakePath(data))
  },

  // ── License API stubs (client apps always bypass license) ───────────────
  // The Next.js page.tsx checks `api.getLicenseStatus` — if it exists, it
  // calls it and expects `{ isValid: true }` to skip the license gate.
  // For MC/Operator clients we want to bypass license entirely, so we
  // return a valid status.
  getLicenseStatus: (): Promise<{
    isValid: boolean
    isGracePeriod: boolean
    isExpired: boolean
    daysRemaining: number
    graceDaysRemaining: number
    licenseType: string | null
    expiresAt: string | null
    machineId: string
    displayMachineId: string
    firstRunDate: string | null
  }> => {
    return Promise.resolve({
      isValid: true,
      isGracePeriod: false,
      isExpired: false,
      daysRemaining: 365,
      graceDaysRemaining: 0,
      licenseType: 'client',
      expiresAt: null,
      machineId: 'electron-client',
      displayMachineId: 'Electron-Client',
      firstRunDate: null,
    })
  },

  activateLicense: (_activationCode: string): Promise<{
    success: boolean
    error?: string
    licenseType?: string
  }> => {
    return Promise.resolve({ success: true, licenseType: 'client' })
  },

  getMachineId: (): Promise<{
    machineId: string
    displayMachineId: string
  }> => {
    return Promise.resolve({
      machineId: 'electron-client',
      displayMachineId: 'Electron-Client',
    })
  },

  // ── Folder pickers (no-op stubs — clients don't pick folders) ────────────
  selectFolder: (_defaultPath: string): Promise<string | null> => {
    return Promise.resolve(null)
  },

  createFolder: (_folderPath: string): Promise<{
    success: boolean
    path?: string
    error?: string
  }> => {
    return Promise.resolve({ success: true })
  },

  // ── Cloud backup stubs (no-op — clients don't do backup) ─────────────────
  selectBackupFolder: (): Promise<string | null> => Promise.resolve(null),
  getBackupFolder: (): Promise<string | null> => Promise.resolve(null),
  clearBackupFolder: (): Promise<boolean> => Promise.resolve(true),
  getBackupStats: (): Promise<{ connected: boolean; totalFiles: number }> =>
    Promise.resolve({ connected: false, totalFiles: 0 }),

  // ── Open URL in default browser (used for Web Bluetooth etc.) ──────────
  openInBrowser: (url: string): Promise<boolean> => {
    return ipcRenderer.invoke('open-in-browser', url)
  },
})

// Expose command-line args as a direct property for easy detection
// by the connection screen (window.electronConnectionInfo).
const _info = ipcRenderer.sendSync('get-connection-info')
;(contextBridge as any).exposeInMainWorld('electronConnectionInfo', _info)
