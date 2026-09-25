import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('saatirilAPI', {
  isElectron: true,
  connectToServer: (url: string) => ipcRenderer.send('connect-to-server', url),
  getConnectionInfo: () => ipcRenderer.invoke('get-connection-info'),
  getLanInfo: () => Promise.resolve({ httpPort: 3000, socketPort: 3003, ips: [] }),
  // Saves photo to operator's local disk via IPC. Admin ALSO saves via PHOTOS_SAVED — redundancy.
  savePhoto: (data: { base64Data: string; filename: string; targetFolder: string }): Promise<string | null> => {
    return ipcRenderer.invoke('save-photo', data)
  },
  getLicenseStatus: () => Promise.resolve({ isValid: true, isGracePeriod: false, isExpired: false, daysRemaining: 999, graceDaysRemaining: 0, licenseType: 'electron', expiresAt: null, machineId: 'electron-client', displayMachineId: 'Electron-Client', firstRunDate: null }),
  activateLicense: () => Promise.resolve(true),
  getMachineId: () => 'electron-client',
  getDisplayMachineId: () => 'Electron-Client',
  selectFolder: () => Promise.resolve(null),
  createFolder: () => Promise.resolve(null),
})
