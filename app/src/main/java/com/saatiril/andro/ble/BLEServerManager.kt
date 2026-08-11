package com.saatiril.andro.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * BLE GATT Server — runs on the Admin device (HP/Laptop).
 *
 * Acts as the BLE peripheral that the MC's HP connects to.
 *
 * Exposes 5 characteristics:
 *  - next_student (READ + NOTIFY): current/next student JSON
 *  - trigger (WRITE): MC sends {action, studentId}
 *  - status (NOTIFY): {phase, studentId}
 *  - queue_data (READ + NOTIFY): queue summary + next 10 students
 *  - project_info (READ): {projectName, mode, ratio}
 *
 * The server is managed as a singleton — call [start] to begin advertising
 * and serving, [stop] to shut down.
 */
class BLEServerManager(private val context: Context) {

    companion object {
        private const val TAG = "BLEServer"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var isRunning = false

    // Connected devices (MC clients)
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    // Current state data (set by AdminViewModel)
    private var nextStudentJson: String = "{}"
    private var statusJson: String = """{"phase":"standby"}"""
    private var queueDataJson: String = """{"total":0,"pending":0,"done":0,"students":[]}"""
    private var projectInfoJson: String = """{"projectName":"Saatiril","mode":"single"}"""

    // Characteristics (for notifications)
    private var nextStudentChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var queueDataChar: BluetoothGattCharacteristic? = null

    // Callback for trigger actions from MC
    var onTriggerReceived: ((action: String, studentId: String?) -> Unit)? = null

    /** Check if Bluetooth is supported and enabled. */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    /** Check if the GATT server is running. */
    fun isRunning(): Boolean = isRunning

    /**
     * Start the BLE GATT server + advertising.
     * @return true if started successfully, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Server already running")
            return true
        }

        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return false
        }

        return try {
            startGattServer()
            startAdvertising()
            isRunning = true
            Log.i(TAG, "BLE Server started — advertising service ${BLEProtocol.SERVICE_UUID}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE server: ${e.message}", e)
            false
        }
    }

    /** Stop the BLE GATT server + advertising. */
    fun stop() {
        if (!isRunning) return

        try {
            stopAdvertising()
            gattServer?.let { server ->
                connectedDevices.forEach { device ->
                    try { server.cancelConnection(device) } catch (_: Exception) {}
                }
                try { server.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE server: ${e.message}")
        }

        gattServer = null
        connectedDevices.clear()
        isRunning = false
        Log.i(TAG, "BLE Server stopped")
    }

    // ── Data updaters (called by AdminViewModel) ──

    /** Update the next/current student data and notify MC. */
    fun updateNextStudent(studentJson: String) {
        nextStudentJson = studentJson
        notifyCharacteristic(nextStudentChar, studentJson)
    }

    /** Update the status phase and notify MC. */
    fun updateStatus(phase: String, studentId: String? = null) {
        val json = JSONObject().apply {
            put("phase", phase)
            studentId?.let { put("studentId", it) }
        }.toString()
        statusJson = json
        notifyCharacteristic(statusChar, json)
    }

    /** Update the queue data and notify MC. */
    fun updateQueueData(queueJson: String) {
        queueDataJson = queueJson
        notifyCharacteristic(queueDataChar, queueJson)
    }

    /** Update project info. */
    fun updateProjectInfo(projectName: String, mode: String, ratio: String) {
        projectInfoJson = JSONObject().apply {
            put("projectName", projectName)
            put("mode", mode)
            put("ratio", ratio)
        }.toString()
    }

    // ── GATT Server setup ──

    private fun startGattServer() {
        val server = bluetoothManager.adapter?.let { adapter ->
            bluetoothManager.openGattServer(context, gattServerCallback)
        }

        if (server == null) {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        // Create service
        val service = android.bluetooth.BluetoothGattService(
            java.util.UUID.fromString(BLEProtocol.SERVICE_UUID),
            android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Characteristic: next_student (READ + NOTIFY)
        nextStudentChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_NEXT_STUDENT),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = nextStudentJson.toByteArray(StandardCharsets.UTF_8)
            addDescriptor(createCCCD())
        }

        // Characteristic: trigger (WRITE)
        val triggerChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_TRIGGER),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Characteristic: status (NOTIFY)
        statusChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_STATUS),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = statusJson.toByteArray(StandardCharsets.UTF_8)
            addDescriptor(createCCCD())
        }

        // Characteristic: queue_data (READ + NOTIFY)
        queueDataChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_QUEUE_DATA),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = queueDataJson.toByteArray(StandardCharsets.UTF_8)
            addDescriptor(createCCCD())
        }

        // Characteristic: project_info (READ)
        val projectInfoChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_PROJECT_INFO),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = projectInfoJson.toByteArray(StandardCharsets.UTF_8)
        }

        // Add characteristics to service
        service.addCharacteristic(nextStudentChar)
        service.addCharacteristic(triggerChar)
        service.addCharacteristic(statusChar)
        service.addCharacteristic(queueDataChar)
        service.addCharacteristic(projectInfoChar)

        server.addService(service)
        gattServer = server
    }

    private fun createCCCD(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            java.util.UUID.fromString(BLEProtocol.DESC_CCCD),
            BluetoothGattDescriptor.PERMISSION_READ or
                BluetoothGattDescriptor.PERMISSION_WRITE
        ).apply {
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
    }

    // ── Advertising ──

    private var advertiseCallback: AdvertiseCallback? = null

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(BLEProtocol.ADVERTISE_TIMEOUT_MS)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(java.util.UUID.fromString(BLEProtocol.SERVICE_UUID)))
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE advertising failed: errorCode=$errorCode")
            }
        }

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        advertiseCallback?.let { cb ->
            try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
        }
        advertiseCallback = null
    }

    // ── GATT Server Callback ──

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    Log.i(TAG, "MC connected: ${device.address} (total=${connectedDevices.size})")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    Log.i(TAG, "MC disconnected: ${device.address} (total=${connectedDevices.size})")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val uuid = characteristic.uuid.toString()
            val data: String = when (uuid) {
                BLEProtocol.CHAR_NEXT_STUDENT -> nextStudentJson
                BLEProtocol.CHAR_STATUS -> statusJson
                BLEProtocol.CHAR_QUEUE_DATA -> queueDataJson
                BLEProtocol.CHAR_PROJECT_INFO -> projectInfoJson
                else -> "{}"
            }

            val bytes = data.toByteArray(StandardCharsets.UTF_8)
            // Handle offset for long reads
            val response = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)

            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response
            )
            Log.d(TAG, "Characteristic read: $uuid (${response.size} bytes)")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val uuid = characteristic.uuid.toString()

            if (uuid == BLEProtocol.CHAR_TRIGGER) {
                val jsonStr = String(value, StandardCharsets.UTF_8)
                Log.i(TAG, "Trigger received: $jsonStr")

                try {
                    val json = JSONObject(jsonStr)
                    val action = json.optString("action", "")
                    val studentId = if (json.has("studentId")) json.getString("studentId") else null

                    onTriggerReceived?.invoke(action, studentId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse trigger JSON: ${e.message}")
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid.toString() == BLEProtocol.DESC_CCCD) {
                // Client enabling/disabling notifications
                Log.i(TAG, "MC ${device.address} subscribed to notifications")
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                )
            }
        }
    }

    // ── Notification helper ──

    private fun notifyCharacteristic(characteristic: BluetoothGattCharacteristic?, value: String) {
        if (characteristic == null || gattServer == null) return

        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        characteristic.value = bytes

        connectedDevices.forEach { device ->
            try {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify ${device.address}: ${e.message}")
            }
        }
    }

    /** Get count of connected MC clients. */
    fun getConnectedClientCount(): Int = connectedDevices.size
}
