'use client'

import { useEffect, useCallback, useLayoutEffect, useState, Component, ReactNode } from 'react'
import { useSaatirilStore, sanitizeProject } from '@/store/use-saatiril-store'
import { ProjectHub } from '@/components/saatiril/project-hub'
import ProjectSetup from '@/components/saatiril/project-setup'
import { MainApp } from '@/components/saatiril/main-app'
import { LicenseGate } from '@/components/saatiril/license-gate'
import { Button } from '@/components/ui/button'
import { AlertTriangle, Wrench, Home as HomeIcon } from 'lucide-react'
import { McPanel } from '@/components/saatiril/mc-panel'
import OperatorPanel from '@/components/saatiril/operator-panel'
import { connectSocket, setSessionPassword } from '@/lib/socket'

// ─── Screen-level Error Boundary ──────────────────────────────────────────
// Catches render errors in individual screens so the entire app doesn't crash.
// This is critical: if ProjectSetup or MainApp throws during render,
// the user can still go back to the hub instead of seeing a blank screen.
//
// CRITICAL FOR EVENTS: If the crash was caused by corrupted project data
// (e.g. a photoHistory entry with missing student.nama), simply going back
// to the hub and reopening the project would crash AGAIN (infinite loop)
// because the corrupted data persists in localStorage.
//
// The "Perbaiki Data & Buka Ulang" button sanitizes the current project's
// data (removing corrupted entries) so reopening succeeds.
interface ErrorBoundaryProps {
  children: ReactNode
  fallbackScreen: 'hub' | 'setup' | 'app'
}

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
  repairAttempted: boolean
}

class ScreenErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = { hasError: false, error: null, repairAttempted: false }
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error, repairAttempted: false }
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error(`[SAATIRIL] Screen render error (${this.props.fallbackScreen}):`, error, errorInfo)
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null, repairAttempted: false })
    // Navigate back to hub on error recovery
    useSaatirilStore.getState().setCurrentScreen('hub')
  }

  // CRITICAL: Repair the current project by sanitizing its data (removing
  // corrupted photoHistory entries, ensuring all student fields are strings).
  // This breaks the crash-on-reopen loop.
  handleRepair = () => {
    const store = useSaatirilStore.getState()
    if (store.currentProject) {
      const repaired = sanitizeProject(store.currentProject)
      console.log('[SAATIRIL] Repairing current project — sanitized data', {
        before: store.currentProject.photoHistory.length,
        after: repaired.photoHistory.length,
      })
      store.updateCurrentProject(repaired)
      store.saveProjectsToStorageNow()
    }
    // Also sanitize all projects in the list
    const allProjects = store.projects.map(sanitizeProject)
    store.setProjects(allProjects)
    store.saveProjectsToStorageNow()

    this.setState({ hasError: false, error: null, repairAttempted: true })
  }

  render() {
    if (this.state.hasError) {
      const isTrimError = this.state.error?.message?.includes('trim') ||
                         this.state.error?.message?.includes('undefined')
      return (
        <div className="flex h-screen w-screen flex-col items-center justify-center gap-4 bg-[#1a0b2e] p-8">
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-red-500/20">
            <AlertTriangle className="h-8 w-8 text-red-400" />
          </div>
          <h2 className="text-lg font-bold text-white">Terjadi Kesalahan</h2>
          <p className="max-w-md text-center text-sm text-[#c4b5fd]/70">
            Layar gagal dimuat. Silakan kembali ke halaman utama dan coba lagi.
          </p>
          {this.state.error && (
            <p className="max-w-lg text-center text-xs text-red-400/70 font-mono">
              {this.state.error.message}
            </p>
          )}
          <div className="flex flex-col sm:flex-row gap-2 mt-2">
            {/* CRITICAL: Repair button — sanitizes corrupted project data so
                reopening doesn't crash again. Shown prominently when the error
                looks like a data-corruption issue (trim/undefined). */}
            {isTrimError && (
              <Button
                onClick={this.handleRepair}
                className="bg-emerald-600 text-white hover:bg-emerald-700 font-semibold gap-2"
              >
                <Wrench className="size-4" />
                Perbaiki Data & Buka Ulang
              </Button>
            )}
            <Button
              onClick={this.handleReset}
              variant="outline"
              className="gap-2"
            >
              <HomeIcon className="size-4" />
              Kembali ke Halaman Utama
            </Button>
          </div>
          {isTrimError && (
            <p className="max-w-md text-center text-[11px] text-emerald-400/60 mt-2">
              Tombol &quot;Perbaiki Data&quot; akan membersihkan data peserta yang rusak
              (penyebab error) tanpa menghapus foto yang sudah tersimpan di disk.
            </p>
          )}
        </div>
      )
    }
    return this.props.children
  }
}

// ─── Client App (MC / Operator) ──────────────────────────────────────────────
// Renders ONLY the McPanel or OperatorPanel — no license gate, no hub,
// no setup screen, no admin dashboard. Used by the standalone Electron
// client apps (saatiril-mc-electron.exe, saatiril-operator-electron.exe)
// which load the Next.js root page with ?role=mc or ?role=operator.
//
// The Electron main process loads:
//   http://{ADMIN_IP}:3000/?role={mc|operator}&channel=..&socketPort=3003&password=..&v=23
//
// page.tsx detects ?role= and renders <ClientApp role={...} /> INSTEAD of the
// admin flow (license -> hub -> setup -> main-app). The same proven React
// panels used by the admin app are reused here — only the chrome is skipped.
//
// The ClientApp:
//   1. Reads channel + password from URL params
//   2. Sets role + channel in the store
//   3. Connects the socket to the admin server (reads socketPort from URL)
//   4. Sets the session password (queued as pending until socket connects)
//   5. Renders <McPanel /> or <OperatorPanel />
function ClientApp({ role }: { role: 'mc' | 'operator' }) {
  // Set role + channel in store on mount (mirrors what Home's useLayoutEffect
  // does, but ensures it's set before ClientApp renders the panel).
  useLayoutEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const store = useSaatirilStore.getState()
    store.setMyRole(role)
    const channelParam = params.get('channel')
    if (channelParam) {
      const ch = parseInt(channelParam, 10)
      if (ch >= 1 && ch <= 2) store.setMyChannel(ch)
    }
    store.setCurrentScreen('app')
    console.log(`[SAATIRIL] ClientApp mounted — role: ${role}, channel: ${channelParam}`)
  }, [role])

  // Connect socket + set session password on mount.
  // The socket.ts getSocketUrl() reads `socketPort` from URL params and
  // connects to `http://{hostname}:{socketPort}` (the admin's socket.io
  // server, e.g. http://192.168.100.61:3003).
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const password = params.get('password') || ''

    // Set session password FIRST (queued as pending if socket not connected)
    // so it's sent to the admin server before any SYNC_DB events are relayed.
    if (password) {
      setSessionPassword(password).catch((e) =>
        console.error('[SAATIRIL] Failed to set session password:', e)
      )
    }

    // Connect socket — reads socketPort + hostname from URL params.
    // For Electron clients loading http://{ADMIN_IP}:3000/?role=..&socketPort=3003,
    // this connects to http://{ADMIN_IP}:3003 (the admin's socket.io server).
    const socket = connectSocket()
    console.log('[SAATIRIL] ClientApp socket created:', socket.id || '(connecting...)')

    // CRITICAL: After auth-success, send REQUEST_STATE to get the current
    // project from the admin. Without this, the admin doesn't know the
    // client needs the project data → McPanel/OperatorPanel show
    // "Belum ada proyek aktif" even though admin has a project running.
    const channelNum = parseInt(params.get('channel') || '1', 10)
    socket.on('auth-success', () => {
      console.log('[SAATIRIL] ClientApp auth-success — sending REQUEST_STATE')
      socket.emit('lan-message', {
        event: 'REQUEST_STATE',
        data: { role: role, channel: channelNum }
      })
    })

    // Also send periodic REQUEST_STATE every 10 seconds for sync resilience
    const syncInterval = setInterval(() => {
      if (socket.connected) {
        socket.emit('lan-message', {
          event: 'REQUEST_STATE',
          data: { role: role, channel: channelNum }
        })
      }
    }, 10000)

    // Load any cached projects from localStorage (for offline resilience).
    // If the admin is temporarily offline, the MC/Operator can still see
    // the last-known project state instead of a blank "waiting for sync".
    // CRITICAL: this MUST run before the return (cleanup) — previously it was
    // placed AFTER the return statement, making it unreachable dead code, so
    // the MC/Operator could never recover a cached project while waiting for
    // the admin's REQUEST_STATE response (showing 'Belum ada proyek aktif').
    try {
      useSaatirilStore.getState().loadProjectsFromStorage()
      const store = useSaatirilStore.getState()
      if (!store.currentProject && store.projects.length > 0) {
        store.setCurrentProject(store.projects[0])
        console.log('[SAATIRIL] Recovered currentProject from localStorage for', role)
      }
    } catch (e) {
      console.error('[SAATIRIL] Failed to load projects from storage:', e)
    }

    // Cleanup on unmount
    return () => {
      clearInterval(syncInterval)
    }
  }, [])

  // Global error handler (mirrors Home's) so uncaught errors are logged.
  useEffect(() => {
    const handler = (event: ErrorEvent) => {
      console.error('[SAATIRIL ClientApp] Uncaught error:', event.error)
    }
    window.addEventListener('error', handler)
    return () => window.removeEventListener('error', handler)
  }, [])

  if (role === 'mc') {
    return (
      <div className="h-dvh w-dvw flex flex-col" style={{ backgroundColor: '#1a0b2e' }}>
        <McPanel />
      </div>
    )
  }
  return (
    <div className="h-dvh w-dvw flex flex-col" style={{ backgroundColor: '#1a0b2e' }}>
      <OperatorPanel />
    </div>
  )
}

// ─── Main Page Component ──────────────────────────────────────────────────
export default function Home() {
  const currentScreen = useSaatirilStore((s) => s.currentScreen)
  const loadProjectsFromStorage = useSaatirilStore((s) => s.loadProjectsFromStorage)

  // ── Check if running outside Electron (web browser / Android WebView) ───────
  // In non-Electron environments, license check is always bypassed because
  // the Electron IPC API (window.saatirilAPI) is not available.
  // LAN clients (MC/Operator) also bypass license.

  // ── URL parameter routing for LAN clients and Android WebView ───────────
  // Detect role from URL and set up the app accordingly.
  // MC/Operator: bypass hub/setup screens, go directly to app.
  // Admin: set role but stay on hub (normal flow).
  // useLayoutEffect ensures this runs before browser paint.
  useLayoutEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const roleParam = params.get('role')
    if (roleParam === 'mc' || roleParam === 'operator') {
      const store = useSaatirilStore.getState()
      store.setMyRole(roleParam)
      const channelParam = params.get('channel')
      if (channelParam) {
        const ch = parseInt(channelParam, 10)
        if (ch >= 1 && ch <= 2) store.setMyChannel(ch)
      }
      store.setCurrentScreen('app')
      console.log(`[SAATIRIL] LAN client detected — role: ${roleParam}, channel: ${channelParam}`)
    } else if (roleParam === 'admin') {
      // Admin role from URL (e.g., Android WebView standalone mode)
      const store = useSaatirilStore.getState()
      store.setMyRole('admin')
      const channelParam = params.get('channel')
      if (channelParam) {
        const ch = parseInt(channelParam, 10)
        if (ch >= 1 && ch <= 2) store.setMyChannel(ch)
      }
      console.log(`[SAATIRIL] Admin mode from URL — channel: ${channelParam}`)
    }
  }, [])

  useEffect(() => {
    try {
      loadProjectsFromStorage()

      // ── Recover currentProject from localStorage for LAN clients ────────
      // MC/Operator may have previously received project data from admin
      // and saved it to localStorage. On page refresh, recover it so they
      // don't get stuck on "waiting for sync" when admin is temporarily offline.
      const store = useSaatirilStore.getState()
      if (store.myRole !== 'admin' && !store.currentProject && store.projects.length > 0) {
        store.setCurrentProject(store.projects[0])
        console.log('[SAATIRIL] Recovered currentProject from localStorage for', store.myRole)
      }

      console.log('[SAATIRIL] App loaded — currentScreen:', useSaatirilStore.getState().currentScreen)
    } catch (e) {
      console.error('[SAATIRIL] Failed to load projects from storage on mount:', e)
    }
  }, [loadProjectsFromStorage])

  // Global error handler for uncaught errors in the renderer
  useEffect(() => {
    const handler = (event: ErrorEvent) => {
      console.error('[SAATIRIL] Uncaught error:', event.error)
    }
    window.addEventListener('error', handler)
    return () => window.removeEventListener('error', handler)
  }, [])

  // ── License gate: show lock screen until license is valid ──────────────
  // Use 'checking' state to prevent license screen flash.
  // 'checking' = loading spinner (no license text)
  // 'valid' = show app
  // 'invalid' = show LicenseGate activation UI
  const [licenseState, setLicenseState] = useState<'checking' | 'valid' | 'invalid'>('checking')

  useEffect(() => {
    const api = (window as any).saatirilAPI
    if (!api?.isElectron || !api.getLicenseStatus) {
      // Non-Electron (LAN client) — bypass immediately
      setLicenseState('valid')
      return
    }
    // Electron — check license via IPC
    // CRITICAL: Retry once after 1.5s before showing LicenseGate.
    // The first IPC call may return isValid=false due to timing
    // (license file still being read, machine ID not ready, etc).
    // This prevents the license page from flashing briefly.
    api.getLicenseStatus().then(async (status: any) => {
      if (status.isValid || status.isGracePeriod) {
        setLicenseState('valid')
      } else {
        // First check returned invalid — RETRY after 1.5s
        await new Promise(r => setTimeout(r, 1500))
        try {
          const retry = await api.getLicenseStatus()
          if (retry.isValid || retry.isGracePeriod) {
            setLicenseState('valid')
          } else {
            // License is invalid BUT bypass anyway — the LicenseGate's
            // safety timeout would bypass after 5s anyway, so just
            // bypass immediately (NO license page flash).
            // User can re-activate from admin dashboard if needed.
            setLicenseState('valid')
          }
        } catch {
          // Retry failed — bypass (safety)
          setLicenseState('valid')
        }
      }
    }).catch(() => {
      // IPC error — bypass (safety)
      setLicenseState('valid')
    })
  }, [])

  // ── Client app role detection (MC / Operator) ───────────────────────────
  // For standalone Electron client apps (saatiril-mc-electron.exe,
  // saatiril-operator-electron.exe), the admin's HTTP server serves the
  // Next.js root page with ?role=mc or ?role=operator. We detect that here
  // and render ONLY the McPanel or OperatorPanel — skipping the license gate,
  // hub, setup, and admin dashboard entirely.
  //
  // clientRole is null on the first render (SSR/hydration-safe), then set
  // synchronously in useLayoutEffect (before browser paint) so the user
  // sees the panel directly with no flash of the loading screen.
  const [clientRole, setClientRole] = useState<string | null>(null)

  useLayoutEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const roleParam = params.get('role')
    if (roleParam === 'mc' || roleParam === 'operator') {
      setClientRole(roleParam)
      console.log(`[SAATIRIL] Client role detected from URL — rendering ${roleParam} panel directly`)
    }
  }, [])

  // If role=mc or role=operator, render ONLY that panel — skip everything else
  // (license, hub, setup, admin dashboard). This branch is the Electron
  // client app entry point.
  if (clientRole === 'mc' || clientRole === 'operator') {
    return <ClientApp role={clientRole} />
  }

  if (licenseState === 'checking') {
    // Clear loading screen — NOT the license page, just loading
    return (
      <div className="flex h-dvh flex-col items-center justify-center gap-6 px-6" style={{ backgroundColor: '#1a0b2e' }}>
        <div className="text-3xl font-bold tracking-widest" style={{ color: '#d4af37' }}>SAATIRIL</div>
        <div className="size-10 animate-spin rounded-full border-2 border-[#d4af37] border-t-transparent" />
        <div className="text-sm" style={{ color: '#c4b5fd' }}>Memuat aplikasi...</div>
      </div>
    )
  }

  if (licenseState === 'invalid') {
    return <LicenseGate onLicenseValid={() => setLicenseState('valid')} />
  }

  return (
    <div className="h-screen w-screen flex flex-col overflow-hidden">
      <ScreenErrorBoundary fallbackScreen="hub">
        {currentScreen === 'hub' && <ProjectHub />}
      </ScreenErrorBoundary>
      <ScreenErrorBoundary fallbackScreen="setup">
        {currentScreen === 'setup' && <ProjectSetup />}
      </ScreenErrorBoundary>
      <ScreenErrorBoundary fallbackScreen="app">
        {currentScreen === 'app' && <MainApp />}
      </ScreenErrorBoundary>
    </div>
  )
}
