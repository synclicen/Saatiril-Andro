import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('saatirilAPI', {
  isElectron: true,
  connectToServer: (url: string) => ipcRenderer.send('connect-to-server', url),
  backToLogin: () => ipcRenderer.send('back-to-login'),
  getConnectionInfo: () => ipcRenderer.invoke('get-connection-info'),
  getLanInfo: () => Promise.resolve({ httpPort: 3000, socketPort: 3003, ips: [] }),
  // Saves photo to operator's local disk via IPC. Admin ALSO saves via PHOTOS_SAVED — redundancy.
  savePhoto: (data: { base64Data: string; filename: string; targetFolder: string }): Promise<string | null> => {
    return ipcRenderer.invoke('save-photo', data)
  },
  // Hot Folder (EOS Utility / Imaging Edge writes into a folder on the
  // OPERATOR PC — same machine as this client). The camera is attached here,
  // so the operator client reads it locally, exactly like the portable does.
  // readHotFolderPhoto only hands out COMPLETE files ('incomplete' = still
  // being written — the renderer retries; importing a half-written JPEG is
  // what produced the black ~39 KB photos).
  listHotFolderFiles: (folderPath: string): Promise<Array<{ filename: string; mtime: number }>> =>
    ipcRenderer.invoke('list-hot-folder-files', folderPath),
  readHotFolderPhoto: (
    folderPath: string,
    filename: string,
  ): Promise<
    | { status: 'complete'; base64: string; size: number }
    | { status: 'incomplete' }
    | { status: 'error'; message: string }
  > => ipcRenderer.invoke('read-hot-folder-photo', { folderPath, filename }),
  getLicenseStatus: () => Promise.resolve({ isValid: true, isGracePeriod: false, isExpired: false, daysRemaining: 999, graceDaysRemaining: 0, licenseType: 'electron', expiresAt: null, machineId: 'electron-client', displayMachineId: 'Electron-Client', firstRunDate: null }),
  activateLicense: () => Promise.resolve(true),
  getMachineId: () => 'electron-client',
  getDisplayMachineId: () => 'Electron-Client',
  selectFolder: () => Promise.resolve(null),
  createFolder: () => Promise.resolve(null),
})
