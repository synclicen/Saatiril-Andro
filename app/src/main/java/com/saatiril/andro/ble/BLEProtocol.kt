package com.saatiril.andro.ble

/**
 * BLE Protocol definitions for Saatiril MC Remote.
 *
 * Architecture:
 *   Admin (Laptop/HP) = BLE GATT Server (peripheral)
 *   MC (HP)           = BLE GATT Client (central)
 *
 * The Admin runs the full Saatiril app (server + camera + photo saving).
 * The MC HP acts as a remote trigger — receives queue data, sends
 * PANGGIL/NEXT/RESET commands, and receives "foto selesai" notifications.
 *
 * NO WiFi is needed for the MC↔Operator communication.
 * Photos are saved directly to the Admin's local folder.
 * Google Drive backup runs async (if internet available).
 *
 * BLE Service UUID: e7810a71-73ae-499d-8c15-fa8f6072e919
 *
 * Characteristics:
 *  1. next_student (READ + NOTIFY) — current/next student to call
 *  2. trigger (WRITE) — MC sends action commands
 *  3. status (NOTIFY) — admin sends capture phase updates
 *  4. queue_data (READ + NOTIFY) — queue summary + next 10 students
 */
object BLEProtocol {

    /** Custom BLE Service UUID for Saatiril MC Remote. */
    const val SERVICE_UUID = "e7810a71-73ae-499d-8c15-fa8f6072e919"

    /** Characteristic: current/next student (READ + NOTIFY). */
    const val CHAR_NEXT_STUDENT = "e7810a71-73ae-499d-8c15-fa8f6072e91a"

    /** Characteristic: trigger commands from MC (WRITE). */
    const val CHAR_TRIGGER = "e7810a71-73ae-499d-8c15-fa8f6072e91b"

    /** Characteristic: capture status updates (NOTIFY). */
    const val CHAR_STATUS = "e7810a71-73ae-499d-8c15-fa8f6072e91c"

    /** Characteristic: queue data — summary + next 10 students (READ + NOTIFY). */
    const val CHAR_QUEUE_DATA = "e7810a71-73ae-499d-8c15-fa8f6072e91d"

    /** Characteristic: connection info — project name, mode, etc (READ). */
    const val CHAR_PROJECT_INFO = "e7810a71-73ae-499d-8c15-fa8f6072e91e"

    /** Descriptor UUID for enabling notifications (standard CCCD). */
    const val DESC_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** BLE advertisement timeout (ms). 0 = advertise forever. */
    const val ADVERTISE_TIMEOUT_MS = 0 // Int — AdvertiseSettings.setTimeout() requires int

    /** Maximum BLE characteristic value size (bytes). */
    const val MAX_CHAR_SIZE = 512

    // ── Trigger Actions (MC → Admin) ──
    object Action {
        const val PANGGIL = "PANGGIL"     // Call the next pending student
        const val NEXT = "NEXT"           // Skip to next without calling
        const val PREV = "PREV"           // Go back to previous
        const val RESET = "RESET"         // Reset current student
        const val SKIP = "SKIP"           // Skip current student
        const val DONE = "DONE"           // Mark current as done manually
    }

    // ── Status Phases (Admin → MC) ──
    object Phase {
        const val STANDBY = "standby"         // No active student
        const val READY = "ready"             // Student called, waiting for photo
        const val CAPTURING = "capturing"      // Photo being taken
        const val SENDING = "sending"          // Photo being saved/sent
        const val DONE = "done"               // Photo saved, ready for next
    }
}
