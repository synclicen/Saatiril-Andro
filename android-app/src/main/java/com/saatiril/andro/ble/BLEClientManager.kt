package com.saatiril.andro.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * BLE GATT Client — runs on the MC's HP.
 *
 * Scans for and connects to the Admin's BLE GATT Server.
 *
 * Flow:
 *  1. [startScan] — scan for devices advertising the Saatiril service
 *  2. [connect] — connect to the found device
 *  3. Discover services + subscribe to notifications
 *  4. Receive next_student, status, queue_data notifications
 *  5. [sendTrigger] — write trigger commands (PANGGIL, NEXT, RESET, etc.)
 */
class BLEClientManager(private val context: Context) {

    companion object {
        private const val TAG = "BLEClient"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isConnected = false

    // Characteristics (for reading/writing after discovery)
    private var nextStudentChar: BluetoothGattCharacteristic? = null
    private var triggerChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var queueDataChar: BluetoothGattCharacteristic? = null
    private var projectInfoChar: BluetoothGattCharacteristic? = null

    // Callbacks for UI
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null
    var onNextStudentReceived: ((json: String) -> Unit)? = null
    var onStatusReceived: ((json: String) -> Unit)? = null
    var onQueueDataReceived: ((json: String) -> Unit)? = null
    var onProjectInfoReceived: ((json: String) -> Unit)? = null
    var onDeviceFound: ((device: BluetoothDevice) -> Unit)? = null

    /** Check if Bluetooth is available and enabled. */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    /** Check if connected to Admin. */
    fun isConnected(): Boolean = isConnected

    // ── Scanning ──

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.i(TAG, "Found Saatiril device: ${device.name} (${device.address})")
            onDeviceFound?.invoke(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
        }
    }

    /**
     * Start scanning for devices advertising the Saatiril BLE service.
     * Results are delivered via [onDeviceFound].
     */
    fun startScan(): Boolean {
        if (isScanning) return true
        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth not available")
            return false
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available")
            return false
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(java.util.UUID.fromString(BLEProtocol.SERVICE_UUID)))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.i(TAG, "BLE scan started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan: ${e.message}")
            return false
        }
    }

    /** Stop scanning. */
    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        Log.i(TAG, "BLE scan stopped")
    }

    // ── Connection ──

    /**
     * Connect to a discovered device.
     * @param device The BluetoothDevice to connect to.
     */
    fun connect(device: BluetoothDevice): Boolean {
        if (!isBluetoothAvailable()) return false

        stopScan()

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.i(TAG, "Connecting to ${device.address}...")
        return true
    }

    /** Disconnect from the Admin device. */
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            try { gatt.disconnect() } catch (_: Exception) {}
        }
    }

    /** Close the GATT client completely. */
    fun close() {
        disconnect()
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        isConnected = false
    }

    // ── GATT Callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    Log.i(TAG, "Connected to Admin — discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    connectedDevicesClear()
                    Log.i(TAG, "Disconnected from Admin")
                    onConnectionStateChanged?.invoke(false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status")
                return
            }

            val service = gatt.getService(java.util.UUID.fromString(BLEProtocol.SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Saatiril service not found")
                return
            }

            // Get characteristics
            nextStudentChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_NEXT_STUDENT))
            triggerChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_TRIGGER))
            statusChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_STATUS))
            queueDataChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_QUEUE_DATA))
            projectInfoChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_PROJECT_INFO))

            // Subscribe to notifications
            subscribeToCharacteristic(gatt, nextStudentChar)
            subscribeToCharacteristic(gatt, statusChar)
            subscribeToCharacteristic(gatt, queueDataChar)

            // Read initial values
            projectInfoChar?.let { gatt.readCharacteristic(it) }
            queueDataChar?.let { gatt.readCharacteristic(it) }
            nextStudentChar?.let { gatt.readCharacteristic(it) }

            Log.i(TAG, "Services discovered — subscribed to notifications")
            onConnectionStateChanged?.invoke(true)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Read failed for ${characteristic.uuid}: status=$status")
                return
            }

            val uuid = characteristic.uuid.toString()
            val value = String(characteristic.value, StandardCharsets.UTF_8)

            when (uuid) {
                BLEProtocol.CHAR_NEXT_STUDENT -> onNextStudentReceived?.invoke(value)
                BLEProtocol.CHAR_STATUS -> onStatusReceived?.invoke(value)
                BLEProtocol.CHAR_QUEUE_DATA -> onQueueDataReceived?.invoke(value)
                BLEProtocol.CHAR_PROJECT_INFO -> onProjectInfoReceived?.invoke(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val uuid = characteristic.uuid.toString()
            val value = String(characteristic.value, StandardCharsets.UTF_8)

            when (uuid) {
                BLEProtocol.CHAR_NEXT_STUDENT -> {
                    Log.d(TAG, "Next student update: $value")
                    onNextStudentReceived?.invoke(value)
                }
                BLEProtocol.CHAR_STATUS -> {
                    Log.d(TAG, "Status update: $value")
                    onStatusReceived?.invoke(value)
                }
                BLEProtocol.CHAR_QUEUE_DATA -> {
                    Log.d(TAG, "Queue data update: $value")
                    onQueueDataReceived?.invoke(value)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Trigger sent successfully")
            } else {
                Log.e(TAG, "Trigger write failed: status=$status")
            }
        }
    }

    // ── Write trigger ──

    /**
     * Send a trigger command to the Admin.
     * @param action One of [BLEProtocol.Action] constants (PANGGIL, NEXT, RESET, etc.)
     * @param studentId Optional student ID for the action.
     * @return true if the write was initiated, false if not connected.
     */
    fun sendTrigger(action: String, studentId: String? = null): Boolean {
        if (!isConnected || triggerChar == null) {
            Log.w(TAG, "Cannot send trigger — not connected")
            return false
        }

        val json = JSONObject().apply {
            put("action", action)
            studentId?.let { put("studentId", it) }
        }.toString()

        triggerChar!!.value = json.toByteArray(StandardCharsets.UTF_8)
        triggerChar!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val result = bluetoothGatt?.writeCharacteristic(triggerChar) ?: false
        Log.i(TAG, "Sending trigger: $json (result=$result)")
        return result
    }

    // ── Helpers ──

    private fun subscribeToCharacteristic(gatt: BluetoothGatt, char: BluetoothGattCharacteristic?) {
        if (char == null) return
        gatt.setCharacteristicNotification(char, true)

        val cccd = char.getDescriptor(java.util.UUID.fromString(BLEProtocol.DESC_CCCD))
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
    }

    private fun connectedDevicesClear() {
        nextStudentChar = null
        triggerChar = null
        statusChar = null
        queueDataChar = null
        projectInfoChar = null
    }
}
