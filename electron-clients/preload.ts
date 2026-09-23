/**
 * SAATIRIL — Electron Clients Shared Preload Script
 *
 * Used by both `mc-main.ts` and `operator-main.ts`.
 * Exposes a minimal `saatirilAPI` to the connection screen renderer:
 *   - isElectron: true   (so the HTML knows it is running inside Electron)
 *   - connectToServer(url): send a loadURL request to the main process
 *   - getConnectionInfo(): return command-line args (host/port/channel/password)
 *                          so the connection screen can auto-fill + auto-connect
 *                          when the app is launched from a QR code / link.
 *   - getLanInfo(): return localhost LAN info (used by some HTML pages)
 *   - savePhoto(): no-op in client apps (photos are uploaded via socket to admin)
 *
 * NOTE: contextIsolation is enabled, so these are exposed through contextBridge.
 */

import { contextBridge, ipcRenderer } from 'electron'

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
   * server, so we just return localhost info.
   */
  getLanInfo: (): Promise<{
    httpPort: number
    socketPort: number
    ips: { name: string; address: string }[]
  }> => {
    return ipcRenderer.invoke('get-lan-info')
  },

  /**
   * Save photo — NO-OP in client apps.
   * Photos are uploaded via socket.io to the admin server, which saves
   * them locally and optionally to cloud backup. This stub is here so the
   * operator.html page (which calls it) doesn't crash when running in
   * Electron client mode.
   */
  savePhoto: (_data: {
    base64Data: string
    filename: string
    targetFolder: string
  }): Promise<null> => {
    return Promise.resolve(null)
  },
})

// Expose command-line args as a direct property for easy detection
// by the connection screen (window.electronConnectionInfo).
const _info = ipcRenderer.sendSync('get-connection-info')
;(contextBridge as any).exposeInMainWorld('electronConnectionInfo', _info)
