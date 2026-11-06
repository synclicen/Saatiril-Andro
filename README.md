# Saatiril Andro

The **Android version of the Saatiril Electron Admin app** — so an admin can
run an entire graduation-photography ceremony from a phone, with **no laptop**.

This is NOT the camera-operator app. The phone running **Saatiril Andro** IS
the LAN hub: it runs the Socket.io server, creates the project, imports the
student Excel list, picks the output folder, and saves photos as they arrive
from operator phones (which run the existing `android-operator` APK).

## What it does

The admin launches Saatiril Andro on a phone and:

1. **Activates a license** (30-day, machine-bound — same algorithm as the
   Electron app, so codes are interchangeable).
2. **Creates a project** (name, camera mode, aspect ratio, filter preset).
3. **Imports the student list** from an `.xlsx` / `.csv` file (Excel on the
   phone, via the file picker — no laptop needed).
4. **Picks the output folder** (Android Storage Access Framework — any folder
   on internal/external storage or an SD card).
5. **Optionally sets a session password** (MC/operators must enter it to join).
6. **Starts the server** — a foreground service runs a Socket.io (Engine.IO v3)
   server on port 3003, with a persistent notification so it survives
   backgrounding during the live ceremony.
7. **MCs call students** from the MC tab (or MCs/operators scan the QR code on
   the dashboard to join from their own phones).
8. **Operators** (running the existing `android-operator` APK with DSLR + USB
   capture card) connect to the admin phone over LAN Wi-Fi, capture photos,
   and stream them back.
9. **Photos are saved** automatically to the chosen output folder as
   `NIM_Nama_1_Toga.jpg` / `NIM_Nama_2_Ijazah.jpg` — exactly like the Electron
   app's `savePhoto` IPC.
10. **The dashboard** shows live progress, connected clients, the photo gallery,
    and the full student database with status filters.

## Architecture

```
app/src/main/java/com/saatiril/andro/
├── MainActivity.kt              # Routes: LICENSE → HUB → SETUP → MAIN
├── SaatirilApp.kt               # Application + crash handler
├── server/                      # ★ The LAN Socket.io hub (runs ON the phone)
│   ├── EngineIO.kt              # Engine.IO v3 + Socket.IO packet codec
│   ├── SaatirilServer.kt        # ktor CIO server: polling + websocket,
│   │                            #   session mgmt, message relay, heartbeat
│   └── ServerService.kt         # Foreground service (keeps server alive)
├── data/
│   ├── Models.kt                # Student, Project, SocketEvents, …
│   ├── AdminViewModel.kt        # Orchestrator: license, project, server,
│   │                            #   Excel import, photo save, MC actions
│   ├── LicenseManager.kt        # 30-day machine-bound license (port of
│   │                            #   electron/license.ts — verified bit-identical)
│   ├── ProjectStore.kt          # Persist projects to internal storage (JSON)
│   ├── SocketManager.kt         # (legacy client — unused by the admin app)
│   └── OperatorViewModel.kt     # (legacy operator client — unused)
├── util/
│   ├── ExcelImporter.kt         # Manual .xlsx (ZIP+XML) + .csv parser
│   ├── PhotoSaver.kt            # SAF DocumentFile photo writing
│   ├── FilenameUtils.kt         # NIM_Nama_N_Toga.jpg convention
│   └── Sha256.kt
├── ui/
│   ├── license/LicenseGateScreen.kt    # Activation UI + machine ID
│   ├── hub/ProjectHubScreen.kt         # List/resume/create projects
│   ├── setup/ProjectSetupScreen.kt     # Name, mode, Excel, folder, password
│   ├── admin/AdminDashboardScreen.kt   # Progress, QR, clients, gallery, DB
│   ├── mc/McScreen.kt                  # Call students to the stage
│   ├── main/MainScaffold.kt            # Top bar + Admin/MC bottom tabs
│   └── gridline/GridlineOverlay.kt     # (legacy — unused by admin)
└── camera/                      # (legacy camera stack — unused by admin;
                                #   operators use the separate android-operator APK)
```

## Socket.io server (Engine.IO v3)

The server is implemented in pure Kotlin on **ktor CIO** (no Node.js, no native
runtime). It speaks the Engine.IO v3 wire protocol so the existing
`io.socket:socket.io-client:2.1.0` operator APK connects cleanly — exactly the
same protocol the Electron app's bundled Node server speaks (with
`allowEIO3: true`, `path: "/"`, 20 MB max payload, 5 s ping interval, 15 s
ping timeout, 5-minute connection-state recovery).

Transports supported:
- **Polling** (HTTP GET long-poll + POST on path `/`) — for client
  compatibility and the polling→websocket upgrade path.
- **WebSocket** (direct or upgraded from polling).

Events relayed (matching the Node server): `identify`, `auth-success`,
`auth-failed`, `saatiril-ping`/`pong`, `lan-message` (wrapping `MC_CALL`,
`SYNC_DB`, `PHOTOS_SAVED`, `STUDENT_DONE`, `STUDENT_RESET`, `OP_PROGRESS`,
`REQUEST_STATE`, `REQUEST_FRAME`, `FRAME_DATA`), `SET_SESSION_PASSWORD`,
`CLEAR_SESSION_PASSWORD`, `server-stats`.

## Build

Requirements: JDK 17, Android SDK 34, Kotlin 1.9.22, AGP 8.2.2.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release-unsigned.apk
```

CI builds both debug + release APKs on every push to `main` and publishes
them to the `latest` GitHub Release. See
[.github/workflows/build-android.yml](.github/workflows/build-android.yml).

## Compatibility

- **minSdk 24** (Android 7.0) / **targetSdk 34** (Android 14)
- **ABI**: `arm64-v8a`, `armeabi-v7a`
- The server listens on `0.0.0.0:3003` (or the next free port up to 3010).
- Operators must be on the **same LAN Wi-Fi** as the admin phone.

## How to use

1. On the admin phone: open **Saatiril Andro**, activate the license, create a
   project, import the Excel student list, pick the output folder, tap
   **Mulai Server**.
2. Read the LAN IP + QR code on the dashboard. Operators (with the existing
   `android-operator` APK) and MCs connect to `http://<admin-ip>:3003`.
3. MC calls students from the MC tab (or from their own phone). Operators
   capture photos. Photos flow back to the admin phone and are saved to the
   chosen folder automatically.
4. Tap **Stop** to tear down the server and return to the project hub.

## License algorithm (interchangeable with the Electron app)

- `LICENSE_SECRET = "SAATIRIL-2026-HUMAS-UIN-ANTASARI-BANJARMASIN"`
- Admin key = `SHA-256(SECRET + ":admin-api-key")[:16].upper()`
- Activation code = `SHA-256(machineId + ":monthly:" + expiryHex + ":" + SECRET)[:16]`
  formatted as `XXXX-XXXX-XXXX-XXXX`
- Validity: 30 days, no grace period, machine-bound.
- Verified bit-for-bit against the Electron implementation (codes generated on
  one platform validate on the other).
