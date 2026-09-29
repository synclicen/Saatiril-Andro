package com.saatiril.andro.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Manages a RAW WebSocket connection to the Saatiril LAN server.
 *
 * ─── Why raw WebSocket (not the socket.io-client library)? ──────────────
 * The previous implementation used `io.socket:socket.io-client:2.1.0`.
 * Despite the websocket-only-transport fix (transports = ["websocket"]),
 * the library still failed to connect on some devices (OkHttp version
 * conflicts / Engine.IO v3 handshake quirks with the custom SaatirilServer).
 * The phone BROWSER connects fine via raw WebSocket (see McHtml.kt), so
 * the server's WS path is sound — the library was the problem.
 *
 * This rewrite bypasses the socket.io-client library entirely and speaks
 * Engine.IO v3 + Socket.IO framing manually over an `okhttp3.WebSocket`.
 * This is the EXACT same approach the browser McHtml.kt uses (which works).
 *
 * ─── Protocol (mirrors McHtml.kt + SaatirilServer.handleWebSocket) ──────
 * Connect URL:  ws://<host>:<port>/?EIO=3&transport=websocket
 * On open:      (nothing — wait for server's Engine.IO OPEN packet).
 * On text frame `0...`  → Engine.IO OPEN.  Send `40` (Socket.IO CONNECT).
 * On text frame `2`     → Engine.IO PING.   Respond `3` (PONG).
 * On text frame `4`     → Engine.IO MESSAGE. Parse sub-type (Socket.IO):
 *   `40`  → CONNECT ack.   Send `42["identify", {role, channel, sessionPasswordHash?}]`.
 *   `42…` → EVENT.         Parse `42["name", arg]`. Dispatch by event name.
 *   `41`  → DISCONNECT.    Server closed the namespace.
 *   `43…` → ACK (unused).
 *   `44…` → ERROR.
 * Send event:   `42` + JSON.stringify([name, data])
 * Send raw:     just send the framed string (e.g. `40`, `3`, `2`).
 *
 * ─── Public API (UNCHANGED — callers need no changes) ──────────────────
 * - connect(serverUrl, role, channel, password?)
 * - disconnect(), destroy(), isConnected(), isAuthenticated(), getState()
 * - on(event, listener), off(event, listener)
 * - emitLanMessage(event, data)
 * - requestState(), requestFrame(projectId), resendWithPassword(password)
 * - sendStudentDone, sendPhotosSaved, sendOpProgress, sendMcCall,
 *   sendStudentReset, sendStudentDoneFromMc, sendSyncDb
 *
 * The `ConnectionState` enum, `notifyListenersOnUiThread` mechanism,
 * critical-event queue, and reconnection logic are all preserved.
 */
class SocketManager {
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    companion object {
        private const val TAG = "SocketManager"
        private const val IDENTIFY_TIMEOUT_MS = 30_000L
        private const val PING_INTERVAL_MS = 5_000L

        // Critical events that must be queued when disconnected
        private val CRITICAL_EVENTS = setOf(
            SocketEvents.PHOTOS_SAVED,
            SocketEvents.MC_CALL,
            SocketEvents.SYNC_DB,
            SocketEvents.STUDENT_DONE,
            SocketEvents.STUDENT_RESET
        )
        private const val MAX_QUEUE_SIZE = 2000  // raised for 4000+ participant ceremonies (mirrors Electron socket.ts MAX_QUEUE_SIZE)
        private const val MAX_RETRIES = 5        // was 3, more retries for crowded WiFi

        // Reconnection (mirrors the io.socket options we previously passed:
        // reconnection=true, reconnectionAttempts=Infinity, reconnectionDelay=500,
        // reconnectionDelayMax=5000, timeout=10000).
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L

        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    // Gson with the same field-naming strategy as before (camelCase default,
    // honors @SerializedName). Used to serialize outgoing payloads and to
    // parse incoming JSON into data classes (parseData<T>).
    private val gson: Gson = GsonBuilder()
        .setFieldNamingStrategy { f ->
            val annotation = f.getAnnotation(com.google.gson.annotations.SerializedName::class.java)
            if (annotation != null) annotation.value else f.name
        }
        .create()

    /**
     * A single OkHttpClient is built once and reused for every WebSocket
     * connection / reconnection. OkHttp is already a transitive dependency
     * of `io.socket:socket.io-client:2.1.0`, so this introduces NO new
     * dependencies.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)        // WebSocket = long-lived stream
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)            // OkHttp WS-level keepalive (defends against NAT timeouts)
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var hasConnectedOnce: Boolean = false

    @Volatile private var connectionState = ConnectionState.DISCONNECTED
    @Volatile private var passwordHash: String? = null
    @Volatile private var myChannel: Int = 1
    @Volatile private var myRole: String = Roles.OPERATOR
    @Volatile private var connectErrorCount: Int = 0

    // Reconnection state
    @Volatile private var isExplicitlyDisconnected: Boolean = false
    @Volatile private var reconnectAttempts: Int = 0
    @Volatile private var serverUrl: String = ""
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        if (!isExplicitlyDisconnected && webSocket == null) {
            Log.i(TAG, "Reconnect attempt ${reconnectAttempts + 1}")
            reconnectAttempts++
            doConnect()
        }
    }

    private var pingTimer: java.util.Timer? = null

    // Main thread handler for posting listener notifications
    private val mainHandler = Handler(Looper.getMainLooper())

    // Event listeners — CopyOnWriteArrayList for thread-safe iteration
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()

    // Critical event queue — synchronized access for thread safety
    private data class QueuedEvent(
        val event: String,
        val data: Any,
        var retries: Int = 0
    )
    private val eventQueue = mutableListOf<QueuedEvent>()
    private val eventQueueLock = Any()

    // ─── Connection ─────────────────────────────────────────────

    fun connect(serverUrl: String, role: String, channel: Int, password: String? = null) {
        // Validate URL BEFORE doing anything.
        val validatedUrl: String
        try {
            val uri = URI(serverUrl)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") {
                throw URISyntaxException(serverUrl, "Invalid scheme: must be http(s) or ws(s)")
            }
            if (uri.host.isNullOrBlank()) {
                throw URISyntaxException(serverUrl, "Host is empty")
            }
            validatedUrl = serverUrl
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL: $serverUrl — ${e.message}")
            connectionState = ConnectionState.DISCONNECTED
            notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
            notifyListenersOnUiThread("connection_error", "URL tidak valid: ${e.message}")
            return
        }

        // Tear down any existing socket, but preserve ViewModel listeners.
        cleanupSocket()

        this.serverUrl = validatedUrl
        myRole = role
        myChannel = channel
        passwordHash = password?.let { sha256(it) }
        connectErrorCount = 0
        reconnectAttempts = 0
        isExplicitlyDisconnected = false
        connectionState = ConnectionState.CONNECTING
        notifyListenersOnUiThread("state_changed", connectionState)

        doConnect()
        Log.i(TAG, "Connecting to $validatedUrl as $myRole channel $channel")
    }

    private fun doConnect() {
        val wsUrl = buildWsUrl(serverUrl)
        if (wsUrl == null) {
            connectionState = ConnectionState.DISCONNECTED
            notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
            notifyListenersOnUiThread("connection_error", "URL tidak valid")
            return
        }
        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = httpClient.newWebSocket(request, socketListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WebSocket: ${e.message}", e)
            connectionState = ConnectionState.DISCONNECTED
            notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
            notifyListenersOnUiThread("connection_error", "Gagal membuat koneksi: ${e.message}")
            scheduleReconnect()
        }
    }

    private val socketListener: WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // TCP+HTTP upgraded to a WebSocket. Do NOT send anything yet —
            // wait for the server's Engine.IO OPEN packet (text frame "0{...}").
            Log.d(TAG, "WebSocket transport open — waiting for Engine.IO OPEN")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                onEngineMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onMessage: ${e.message}", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Acknowledge the close handshake.
            try { webSocket.close(1000, null) } catch (_: Exception) {}
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closed: code=$code reason=$reason")
            handleSocketClosed("onClosed")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            handleSocketClosed("onFailure")
        }
    }

    private fun handleSocketClosed(source: String) {
        // Null out the dead WS so reconnectRunnable (which gates on
        // `webSocket == null`) will fire + doConnect() can build a fresh one.
        webSocket = null
        connected = false
        connectionState = ConnectionState.DISCONNECTED
        stopPingInterval()
        notifyListenersOnUiThread("state_changed", connectionState)
        onDisconnected?.invoke()

        if (!isExplicitlyDisconnected) {
            connectErrorCount++
            if (connectErrorCount >= 3 && !hasConnectedOnce) {
                // Repeated failures during the very first connect — surface a
                // useful message instead of silently retrying forever.
                Log.w(TAG, "Connection failed $connectErrorCount times — showing DISCONNECTED state")
                notifyListenersOnUiThread(
                    "connection_error",
                    "Tidak dapat terhubung ke server. Pastikan:\n" +
                    "1. IP & Port benar\n" +
                    "2. Server berjalan di jaringan yang sama\n" +
                    "3. Tidak ada firewall yang memblokir"
                )
            } else {
                notifyListenersOnUiThread(
                    "connection_error",
                    "Koneksi terputus. Mencoba menyambung ulang otomatis... (percobaan $connectErrorCount)"
                )
            }
            scheduleReconnect()
        } else {
            // Explicit user disconnect — do NOT schedule a reconnect.
            // hasConnectedOnce stays as-is so a future connect() works.
        }
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        // Exponential backoff capped at 5s: 500, 1000, 2000, 4000, 5000, 5000, ...
        val exp = (RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(10)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        Log.i(TAG, "Scheduling reconnect in ${exp}ms (attempt ${reconnectAttempts + 1})")
        reconnectHandler.postDelayed(reconnectRunnable, exp)
    }

    private fun cleanupSocket() {
        reconnectHandler.removeCallbacksAndMessages(null)
        stopPingInterval()
        try {
            webSocket?.let { ws ->
                // Politely tell the server we're going away (Engine.IO CLOSE = "1").
                try { ws.send("1") } catch (_: Exception) {}
                try { ws.close(1000, "cleanup") } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old socket: ${e.message}")
        }
        webSocket = null
        connected = false
    }

    fun disconnect() {
        isExplicitlyDisconnected = true
        cleanupSocket()
        connectionState = ConnectionState.DISCONNECTED
        notifyListenersOnUiThread("state_changed", connectionState)
        synchronized(eventQueueLock) {
            eventQueue.clear()
        }
    }

    fun destroy() {
        disconnect()
        listeners.clear()
        mainHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        try { httpClient.dispatcher.executorService.shutdown() } catch (_: Exception) {}
        try { httpClient.connectionPool.evictAll() } catch (_: Exception) {}
    }

    fun isConnected(): Boolean = connected

    fun isAuthenticated(): Boolean = connectionState == ConnectionState.AUTHENTICATED ||
            connectionState == ConnectionState.WAITING_FOR_DATA

    fun getState(): ConnectionState = connectionState

    // ─── Engine.IO + Socket.IO packet handling ─────────────────

    private fun onEngineMessage(raw: String) {
        if (raw.isEmpty()) return
        val type = raw[0]
        val payload = if (raw.length > 1) raw.substring(1) else ""
        when (type) {
            '0' -> {
                // Engine.IO OPEN — server hello. The payload is a JSON blob
                // {"sid":"...","upgrades":[],"pingInterval":5000,"pingTimeout":15000,...}.
                // We don't actually need the sid because we connected via
                // ws-only (sid=null path on the server) — the server tracks us
                // by the WS frame, not by sid. Just send the Socket.IO CONNECT.
                Log.i(TAG, "Engine.IO OPEN received — sending socket.io CONNECT (40)")
                connectErrorCount = 0
                if (hasConnectedOnce) {
                    // This is a reconnect — let listeners know we're back.
                    notifyListenersOnUiThread("reconnected", null)
                }
                hasConnectedOnce = true
                sendRaw("40")  // Socket.IO CONNECT packet
            }
            '2' -> {
                // Engine.IO PING (heartbeat). Server expects a PONG back.
                sendRaw("3")
            }
            '3' -> {
                // Engine.IO PONG — in our protocol the client doesn't send
                // Engine.IO PINGs (OkHttp's WS-level keepalive + the
                // saatiril-ping app event keep the session alive); ignore.
            }
            '4' -> {
                // Engine.IO MESSAGE — carries a Socket.IO packet in `payload`.
                handleSioMessage(payload)
            }
            '1' -> {
                // Engine.IO CLOSE — server is closing the connection.
                Log.i(TAG, "Engine.IO CLOSE from server — closing WS")
                try { webSocket?.close(1000, "server close") } catch (_: Exception) {}
            }
            '5' -> {
                // Engine.IO UPGRADE — not used (we never do polling→WS upgrade;
                // we connect directly via WS).
            }
            '6' -> {
                // Engine.IO NOOP — used by polling transport to flush a long-poll;
                // irrelevant for WS. Ignore.
            }
            else -> {
                Log.d(TAG, "Unknown EIO type '$type' — payload preview: ${payload.take(80)}")
            }
        }
    }

    private fun handleSioMessage(payload: String) {
        if (payload.isEmpty()) return
        val sioType = payload[0]
        val rest = if (payload.length > 1) payload.substring(1) else ""
        when (sioType) {
            '0' -> {
                // Socket.IO CONNECT ack — server accepted our namespace
                // connection. This is the equivalent of the io.socket library's
                // `Socket.EVENT_CONNECT`. Fire the same flow the old code did:
                // set state → notify → identify → onConnected.
                Log.i(TAG, "socket.io CONNECT ack (40) — invoking identify()")
                connected = true
                connectionState = ConnectionState.CONNECTED
                notifyListenersOnUiThread("state_changed", connectionState)
                identify()
                onConnected?.invoke()
            }
            '2' -> {
                // Socket.IO EVENT: 42["eventName", arg1, arg2, ...]
                try {
                    val arr = JSONArray(rest)
                    if (arr.length() == 0) return
                    val eventName = arr.optString(0)
                    // The Saatiril protocol only ever sends one arg (the data
                    // object). Use opt(1) which returns the typed Java object
                    // (JSONObject / JSONArray / String / Number / Boolean / null).
                    val arg = if (arr.length() > 1) arr.opt(1) else null
                    dispatchSioEvent(eventName, arg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse SIO EVENT payload: ${e.message}", e)
                }
            }
            '1' -> {
                // Socket.IO DISCONNECT — namespace disconnect.
                Log.i(TAG, "socket.io DISCONNECT received")
                // Server-initiated disconnect — close the WS + reconnect.
                try { webSocket?.close(1000, "sio disconnect") } catch (_: Exception) {}
            }
            '3' -> {
                // Socket.IO ACK (43<id>[args]) — unused by Saatiril. Ignore.
                Log.d(TAG, "socket.io ACK received (ignored)")
            }
            '4' -> {
                // Socket.IO ERROR (44{...})
                Log.e(TAG, "socket.io ERROR from server: $rest")
            }
            else -> {
                Log.w(TAG, "Unknown SIO packet type '$sioType' — rest: ${rest.take(80)}")
            }
        }
    }

    private fun dispatchSioEvent(name: String, arg: Any?) {
        try {
            when (name) {
                SocketEvents.AUTH_REQUIREMENT -> {
                    val json = arg as? JSONObject
                    val passwordRequired = json?.optBoolean("passwordRequired") ?: false
                    Log.i(TAG, "Auth requirement: passwordRequired=$passwordRequired")
                    if (passwordRequired) {
                        connectionState = ConnectionState.AUTHENTICATING
                        notifyListenersOnUiThread("password_required", null)
                    } else {
                        if (connectionState == ConnectionState.AUTH_FAILED ||
                            connectionState == ConnectionState.AUTHENTICATING
                        ) {
                            passwordHash = null
                            identify()
                        }
                    }
                    notifyListenersOnUiThread("state_changed", connectionState)
                }

                SocketEvents.AUTH_SUCCESS -> {
                    val json = arg as? JSONObject
                    Log.i(TAG, "Auth success: $json")
                    connectionState = ConnectionState.AUTHENTICATED
                    notifyListenersOnUiThread("auth_success", json?.toString())
                    notifyListenersOnUiThread("state_changed", connectionState)
                    startPingInterval()
                    flushEventQueue()
                    requestState()
                }

                SocketEvents.AUTH_FAILED -> {
                    val json = arg as? JSONObject
                    val reason = json?.optString("reason") ?: "unknown"
                    Log.w(TAG, "Auth failed: $reason")
                    connectionState = ConnectionState.AUTH_FAILED
                    notifyListenersOnUiThread("auth_failed", reason)
                    notifyListenersOnUiThread("state_changed", connectionState)
                }

                // ── Session password lifecycle ───────────────────────────────
                SocketEvents.SET_SESSION_PASSWORD -> {
                    Log.i(TAG, "Session password set by admin")
                }

                SocketEvents.CLEAR_SESSION_PASSWORD -> {
                    Log.i(TAG, "Session password cleared by admin")
                    if (connectionState == ConnectionState.AUTH_FAILED ||
                        connectionState == ConnectionState.AUTHENTICATING
                    ) {
                        passwordHash = null
                        identify()
                    }
                }

                // ── Latency measurement ──────────────────────────────────────
                SocketEvents.SAATIRIL_PONG -> {
                    try {
                        val timestamp = when (arg) {
                            is Long -> arg
                            is Int -> arg.toLong()
                            is Double -> arg.toLong()
                            is Number -> arg.toLong()
                            is String -> arg.toLongOrNull() ?: return
                            else -> return
                        }
                        val latency = System.currentTimeMillis() - timestamp
                        notifyListenersOnUiThread("latency", latency)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling saatiril-pong: ${e.message}", e)
                    }
                }

                // ── LAN messages (main communication channel) ────────────────
                SocketEvents.LAN_MESSAGE -> {
                    val json = arg as? JSONObject
                    if (json == null) {
                        Log.w(TAG, "LAN_MESSAGE: arg is null or not JSONObject — arg type: ${arg?.javaClass?.simpleName}")
                        return
                    }
                    handleLanMessage(json)
                }

                else -> {
                    Log.d(TAG, "Unhandled SIO event '$name' — arg type: ${arg?.javaClass?.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SIO event handler for '$name': ${e.message}", e)
        }
    }

    // ─── LAN Message Handler (UNCHANGED from previous version) ────────────

    private fun handleLanMessage(json: JSONObject) {
        val event = json.optString("event")
        val data = json.opt("data")

        Log.d(TAG, "LAN message received: event=$event, dataType=${data?.javaClass?.simpleName}")

        when (event) {
            SocketEvents.MC_CALL -> {
                Log.i(TAG, "MC_CALL received — raw data type: ${data?.javaClass?.simpleName}")
                Log.d(TAG, "MC_CALL raw data: ${data?.toString()?.take(300)}")

                // Strategy 1: Try Gson parsing
                val mcCallData = parseData<McCallData>(data)
                if (mcCallData != null && mcCallData.student.nim.isNotBlank()) {
                    Log.i(TAG, "MC_CALL parsed (Gson): student=${mcCallData.student.nama}, nim=${mcCallData.student.nim}, ch=${mcCallData.channel}, status=${mcCallData.student.status}, assignedCh=${mcCallData.student.assignedChannel}")
                    notifyListenersOnUiThread(SocketEvents.MC_CALL, mcCallData)
                    return
                }

                // Strategy 2: Manual JSONObject extraction
                Log.w(TAG, "MC_CALL: Gson parsing failed or returned empty student — trying manual extraction")
                try {
                    val dataObj = data as? JSONObject ?: json.optJSONObject("data")
                    if (dataObj != null) {
                        val studentObj = dataObj.optJSONObject("student")
                        if (studentObj != null) {
                            val fallbackStudent = parseStudentFromJson(studentObj)
                            val fallbackMcCall = McCallData(
                                student = fallbackStudent,
                                channel = dataObj.optInt("channel", 1)
                            )
                            Log.i(TAG, "MC_CALL parsed (manual): student=${fallbackStudent.nama}, nim=${fallbackStudent.nim}, ch=${fallbackMcCall.channel}, status=${fallbackStudent.status}")
                            notifyListenersOnUiThread(SocketEvents.MC_CALL, fallbackMcCall)
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MC_CALL manual extraction also failed: ${e.message}")
                }

                Log.e(TAG, "MC_CALL: ALL parsing strategies failed — data dropped!")
            }

            SocketEvents.SYNC_DB -> {
                Log.i(TAG, "SYNC_DB received — raw data type: ${data?.javaClass?.simpleName}")
                Log.d(TAG, "SYNC_DB raw data length: ${data?.toString()?.length}")

                // Strategy 1: Gson parsing
                val syncData = parseData<SyncDbData>(data)
                if (syncData != null && syncData.project.name.isNotBlank()) {
                    Log.i(TAG, "SYNC_DB parsed (Gson): project=${syncData.project.name}, dbSize=${syncData.project.database.size}, mode=${syncData.project.config.mode}, targetFolder=${syncData.project.config.targetFolder}")
                    if (syncData.project.database.isNotEmpty()) {
                        Log.d(TAG, "SYNC_DB first student: ${syncData.project.database.first().nama} (status=${syncData.project.database.first().status}, ch=${syncData.project.database.first().assignedChannel})")
                    }
                    notifyListenersOnUiThread(SocketEvents.SYNC_DB, syncData)
                    return
                }

                // Strategy 2: Manual JSONObject extraction
                Log.w(TAG, "SYNC_DB: Gson parsing failed — trying manual extraction")
                try {
                    val dataObj = data as? JSONObject ?: json.optJSONObject("data")
                    if (dataObj != null) {
                        val projectObj = dataObj.optJSONObject("project")
                        if (projectObj != null) {
                            val manualProject = parseProjectFromJson(projectObj)
                            Log.i(TAG, "SYNC_DB parsed (manual): project=${manualProject.name}, dbSize=${manualProject.database.size}, mode=${manualProject.config.mode}, targetFolder=${manualProject.config.targetFolder}")
                            notifyListenersOnUiThread(SocketEvents.SYNC_DB, SyncDbData(project = manualProject))
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SYNC_DB manual extraction also failed: ${e.message}")
                }

                Log.e(TAG, "SYNC_DB: ALL parsing strategies failed — data dropped!")
            }

            SocketEvents.STUDENT_RESET -> {
                Log.d(TAG, "STUDENT_RESET received: $data")
                val resetData = parseData<StudentResetData>(data)
                if (resetData != null) {
                    Log.i(TAG, "STUDENT_RESET: studentId=${resetData.studentId}, channel=${resetData.channel}")
                    notifyListenersOnUiThread(SocketEvents.STUDENT_RESET, resetData)
                } else {
                    // Manual fallback
                    try {
                        val dataObj = data as? JSONObject
                        if (dataObj != null) {
                            val manualReset = StudentResetData(
                                studentId = dataObj.optString("studentId", dataObj.optString("student_id", "")),
                                channel = dataObj.optInt("channel", 1)
                            )
                            Log.i(TAG, "STUDENT_RESET (manual): studentId=${manualReset.studentId}, channel=${manualReset.channel}")
                            notifyListenersOnUiThread(SocketEvents.STUDENT_RESET, manualReset)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "STUDENT_RESET manual fallback failed: ${e.message}")
                    }
                }
            }

            SocketEvents.FRAME_DATA -> {
                Log.d(TAG, "FRAME_DATA received (length: ${data?.toString()?.length})")
                val frameData = parseData<FrameDataPayload>(data)
                if (frameData != null) {
                    notifyListenersOnUiThread(SocketEvents.FRAME_DATA, frameData)
                }
            }

            SocketEvents.PHOTOS_SAVED -> {
                val photosData = parseData<PhotosSavedData>(data)
                if (photosData != null) {
                    notifyListenersOnUiThread(SocketEvents.PHOTOS_SAVED, photosData)
                }
            }

            SocketEvents.STUDENT_DONE -> {
                val doneData = parseData<StudentDoneData>(data)
                if (doneData != null) {
                    notifyListenersOnUiThread(SocketEvents.STUDENT_DONE, doneData)
                }
            }

            SocketEvents.OP_PROGRESS -> {
                // Other operator's progress — informational only
            }

            SocketEvents.SERVER_SHUTDOWN -> {
                Log.w(TAG, "Server shutdown via LAN message: $data")
                notifyListenersOnUiThread("server_shutdown", data)
            }

            else -> {
                Log.d(TAG, "Unhandled LAN event: $event")
            }
        }
    }

    // ─── Manual JSON Parsing Helpers (UNCHANGED) ──────────────
    // These handle cases where Gson fails (e.g., field name mismatches,
    // unexpected JSON structure, etc.).

    private fun parseStudentFromJson(obj: JSONObject): Student {
        return Student(
            id = obj.optString("id", ""),
            nim = obj.optString("nim", ""),
            nama = obj.optString("nama", obj.optString("name", "")),
            status = obj.optString("status", "pending"),
            assignedChannel = obj.optInt("assignedChannel", obj.optInt("assigned_channel", 1))
        )
    }

    private fun parseProjectFromJson(obj: JSONObject): Project {
        val configObj = obj.optJSONObject("config")
        val config = if (configObj != null) {
            val frameValue = configObj.optString("frame", "")
            val sessionPasswordValue = configObj.optString("sessionPassword", configObj.optString("session_password", ""))
            ProjectConfig(
                mode = configObj.optString("mode", "single"),
                ratio = configObj.optString("ratio", "4:3"),
                preset = configObj.optString("preset", "original"),
                targetFolder = configObj.optString("targetFolder", configObj.optString("target_folder", "")),
                frame = frameValue.ifBlank { null },
                sessionPassword = sessionPasswordValue.ifBlank { null }
            )
        } else {
            ProjectConfig()
        }

        val dbArray = obj.optJSONArray("database")
        val students = mutableListOf<Student>()
        if (dbArray != null) {
            for (i in 0 until dbArray.length()) {
                val sObj = dbArray.optJSONObject(i)
                if (sObj != null) {
                    students.add(parseStudentFromJson(sObj))
                }
            }
        }

        val historyArray = obj.optJSONArray("photoHistory")
        val photoHistory = mutableListOf<PhotoHistoryItem>()
        if (historyArray != null) {
            for (i in 0 until historyArray.length()) {
                val hObj = historyArray.optJSONObject(i)
                if (hObj != null) {
                    val studentObj = hObj.optJSONObject("student")
                    val photoStudent = if (studentObj != null) parseStudentFromJson(studentObj) else Student()
                    val photosArray = hObj.optJSONArray("photos")
                    val photos = mutableListOf<String>()
                    if (photosArray != null) {
                        for (j in 0 until photosArray.length()) {
                            photos.add(photosArray.getString(j))
                        }
                    }
                    photoHistory.add(PhotoHistoryItem(
                        student = photoStudent,
                        photos = photos,
                        channel = hObj.optInt("channel", 1)
                    ))
                }
            }
        }

        val versionsObj = obj.optJSONObject("captureVersions")
        val captureVersions = mutableMapOf<String, Int>()
        if (versionsObj != null) {
            val keysIterator = versionsObj.keys()
            while (keysIterator.hasNext()) {
                val rawKey = keysIterator.next()
                val key = rawKey?.toString() ?: continue
                captureVersions[key] = versionsObj.optInt(key, 0)
            }
        }

        return Project(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            config = config,
            database = students,
            photoHistory = photoHistory,
            captureVersions = captureVersions
        )
    }

    // ─── Send helpers (RAW Engine.IO + Socket.IO framing) ──────

    /**
     * Send a raw Engine.IO packet string (e.g. "40" for socket.io CONNECT,
     * "3" for PONG, "2" for PING). Returns false if the WebSocket isn't open.
     */
    private fun sendRaw(msg: String): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "sendRaw('$msg'): no WebSocket")
            return false
        }
        return try {
            ws.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw('$msg') error: ${e.message}", e)
            false
        }
    }

    /**
     * Send a socket.io EVENT: `42` + JSON-encoded `[name, data]`.
     * Mirrors McHtml.kt's `sendRaw('42' + JSON.stringify([name, data]))`.
     *
     * `data` may be any of: a JSONObject, JSONArray, primitive (Int/Long/Double/
     * Boolean/String), or a Gson-serializable data class. The result is the
     * full wire string `42["name",<data>]` ready to be sent as one WS frame.
     */
    private fun sendEvent(name: String, data: Any?) {
        val arr = JSONArray()
        arr.put(name)
        if (data != null) {
            val dataObj = when (data) {
                is JSONObject -> data
                is JSONArray -> data
                is Int, is Long, is Double, is Boolean, is String -> data
                else -> {
                    // Serialize via Gson, then re-parse so org.json can wrap it.
                    val jsonStr = gson.toJson(data)
                    try {
                        when {
                            jsonStr.trimStart().startsWith("{") -> JSONObject(jsonStr)
                            jsonStr.trimStart().startsWith("[") -> JSONArray(jsonStr)
                            else -> jsonStr
                        }
                    } catch (_: Exception) {
                        jsonStr
                    }
                }
            }
            arr.put(dataObj)
        }
        val framed = "42" + arr.toString()
        if (!sendRaw(framed)) {
            Log.w(TAG, "sendEvent('$name'): WebSocket not open — packet dropped")
        }
    }

    // ─── Outgoing Events ────────────────────────────────────────

    private fun identify() {
        try {
            val payload = IdentifyPayload(
                role = myRole,
                channel = myChannel,
                sessionPasswordHash = passwordHash
            )
            sendEvent(SocketEvents.IDENTIFY, payload)
            Log.i(TAG, "Identifying as $myRole channel $myChannel, hasPassword=${passwordHash != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending identify: ${e.message}", e)
        }
    }

    fun resendWithPassword(password: String) {
        passwordHash = sha256(password)
        identify()
    }

    fun requestState() {
        connectionState = ConnectionState.WAITING_FOR_DATA
        notifyListenersOnUiThread("state_changed", connectionState)

        emitLanMessage(SocketEvents.REQUEST_STATE, RequestStateData(
            role = myRole,
            channel = myChannel
        ))
    }

    fun requestFrame(projectId: String) {
        emitLanMessage(SocketEvents.REQUEST_FRAME, RequestFrameData(
            projectId = projectId,
            requesterRole = Roles.OPERATOR
        ))
    }

    fun sendStudentDone(studentId: String) {
        Log.i(TAG, "sendStudentDone: studentId=$studentId, ch=$myChannel, connected=$connected, authenticated=${isAuthenticated()}")
        emitLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(
            studentId = studentId,
            channel = myChannel
        ))
    }

    fun sendPhotosSaved(student: Student, photos: List<String>, version: Int, filename: String) {
        val data = PhotosSavedData(
            student = student.copy(status = "done"),
            photos = photos,
            channel = myChannel,
            version = version,
            filename = filename
        )
        Log.i(TAG, "sendPhotosSaved: student=${student.nama} (id=${student.id}), photos=${photos.size}, ch=$myChannel, ver=$version, filename=$filename, connected=$connected, authenticated=${isAuthenticated()}")
        emitLanMessage(SocketEvents.PHOTOS_SAVED, data)
    }

    fun sendOpProgress(status: String) {
        emitLanMessage(SocketEvents.OP_PROGRESS, OpProgressData(
            channel = myChannel,
            status = status
        ))
    }

    /**
     * MC calls a student to the stage on a specific channel.
     * Broadcasts MC_CALL to all LAN clients (operators pick it up as their target).
     */
    fun sendMcCall(student: Student, channel: Int) {
        Log.i(TAG, "sendMcCall: student=${student.nama} (id=${student.id}), ch=$channel")
        emitLanMessage(SocketEvents.MC_CALL, McCallData(
            student = student,
            channel = channel
        ))
    }

    /**
     * MC/Admin resets a student back to pending (e.g. wrong student called).
     */
    fun sendStudentReset(studentId: String, channel: Int) {
        Log.i(TAG, "sendStudentReset: studentId=$studentId, ch=$channel")
        emitLanMessage(SocketEvents.STUDENT_RESET, StudentResetData(
            studentId = studentId,
            channel = channel
        ))
    }

    /**
     * MC/Admin marks a student as done (sent to stage, photographed).
     */
    fun sendStudentDoneFromMc(studentId: String, channel: Int) {
        Log.i(TAG, "sendStudentDoneFromMc: studentId=$studentId, ch=$channel")
        emitLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(
            studentId = studentId,
            channel = channel
        ))
    }

    fun sendSyncDb(project: Project) {
        // Strip frame and photos before sending
        val strippedProject = project.copy(
            config = project.config.copy(
                frame = if (project.config.frame != null) "__FRAME_SAVED__" else null,
                sessionPassword = if (project.config.sessionPassword != null) "__PASSWORD_SET__" else null
            ),
            photoHistory = project.photoHistory.map { it.copy(photos = emptyList()) }
        )
        emitLanMessage(SocketEvents.SYNC_DB, SyncDbData(project = strippedProject))
    }

    // ─── Critical Event Queue ────────────────────────────────────

    fun emitLanMessage(event: String, data: Any) {
        try {
            val dataJsonStr = gson.toJson(data)
            val payload = JSONObject().apply {
                put("event", event)
                put("data", if (dataJsonStr.trimStart().startsWith("[")) {
                    JSONArray(dataJsonStr)
                } else {
                    JSONObject(dataJsonStr)
                })
            }

            if (isConnected() && isAuthenticated()) {
                // Wire: 42["lan-message",{"event":"EVENT","data":{...}}]
                sendEvent(SocketEvents.LAN_MESSAGE, payload)
                Log.d(TAG, "Emitted LAN message: $event")
            } else if (event in CRITICAL_EVENTS) {
                synchronized(eventQueueLock) {
                    if (eventQueue.size >= MAX_QUEUE_SIZE) {
                        eventQueue.removeAt(0)
                    }
                    eventQueue.add(QueuedEvent(event, data))
                    Log.w(TAG, "Queued critical event: $event (disconnected, queue: ${eventQueue.size})")
                }
            } else {
                Log.w(TAG, "Dropped non-critical event while disconnected: $event")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting LAN message $event: ${e.message}", e)
        }
    }

    private fun flushEventQueue() {
        val toSend: List<QueuedEvent>
        synchronized(eventQueueLock) {
            if (eventQueue.isEmpty()) return
            toSend = eventQueue.toList()
            eventQueue.clear()
        }

        for (item in toSend) {
            if (item.retries >= MAX_RETRIES) {
                Log.w(TAG, "Dropping event after $MAX_RETRIES retries: ${item.event}")
                continue
            }
            item.retries++

            try {
                val dataJsonStr = gson.toJson(item.data)
                val payload = JSONObject().apply {
                    put("event", item.event)
                    put("data", if (dataJsonStr.trimStart().startsWith("[")) {
                        JSONArray(dataJsonStr)
                    } else {
                        JSONObject(dataJsonStr)
                    })
                }
                sendEvent(SocketEvents.LAN_MESSAGE, payload)
                Log.i(TAG, "Flushed queued event: ${item.event} (attempt ${item.retries})")
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing queued event ${item.event}: ${e.message}", e)
            }
        }
    }

    // ─── Ping / Latency ────────────────────────────────────────
    // App-level heartbeat: emits `saatiril-ping` socket.io event every 5s.
    // The server echoes back as `saatiril-pong` (same timestamp) and the
    // client measures round-trip latency. This ALSO keeps the server's
    // `lastSeen` fresh (which prevents the 90s session-timeout reap), so no
    // additional Engine.IO PING timer is needed.

    private fun startPingInterval() {
        stopPingInterval()
        pingTimer = java.util.Timer("SaatirilPing", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        if (connected) {
                            sendEvent(SocketEvents.SAATIRIL_PING, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending ping: ${e.message}", e)
                    }
                }
            }, PING_INTERVAL_MS, PING_INTERVAL_MS)
        }
    }

    private fun stopPingInterval() {
        pingTimer?.cancel()
        pingTimer = null
    }

    // ─── Event System ──────────────────────────────────────────

    fun on(event: String, listener: (Any?) -> Unit) {
        listeners.getOrPut(event) { CopyOnWriteArrayList() }.add(listener)
    }

    fun off(event: String, listener: (Any?) -> Unit) {
        listeners[event]?.remove(listener)
    }

    private fun notifyListeners(event: String, data: Any?) {
        listeners[event]?.forEach { listener ->
            try {
                listener(data)
            } catch (e: Exception) {
                Log.e(TAG, "Error in listener for event '$event': ${e.message}", e)
            }
        }
    }

    private fun notifyListenersOnUiThread(event: String, data: Any?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListeners(event, data)
        } else {
            mainHandler.post {
                notifyListeners(event, data)
            }
        }
    }

    // ─── Utility ───────────────────────────────────────────────

    private inline fun <reified T> parseData(data: Any?): T? {
        return try {
            val jsonString = when (data) {
                is JSONObject -> data.toString()
                is String -> data
                else -> gson.toJson(data)
            }
            if (jsonString.length > 500) {
                Log.d(TAG, "parseData<${T::class.java.simpleName}>: JSON length=${jsonString.length}, preview=${jsonString.take(200)}...")
            } else {
                Log.d(TAG, "parseData<${T::class.java.simpleName}>: JSON=$jsonString")
            }
            val result = gson.fromJson(jsonString, T::class.java)
            if (result == null) {
                Log.e(TAG, "parseData<${T::class.java.simpleName}>: GSON returned null!")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse data for ${T::class.java.simpleName}: ${e.message}")
            Log.e(TAG, "Raw data type: ${data?.javaClass?.simpleName}, data preview: ${data?.toString()?.take(200)}")
            null
        }
    }

    // ─── URL helper ────────────────────────────────────────────

    /**
     * Build a `ws://host:port/?EIO=3&transport=websocket` URL from the user
     * input (which may be `http://`, `https://`, `ws://`, `wss://`, or bare
     * `host:port`). Any path / query on the input is stripped — the server's
     * WS endpoint is at the root path.
     */
    private fun buildWsUrl(serverUrl: String): String? {
        var url = serverUrl.trim()
        if (url.isEmpty()) return null
        when {
            url.startsWith("https://", ignoreCase = true) -> url = "wss://" + url.substring(8)
            url.startsWith("http://", ignoreCase = true)  -> url = "ws://" + url.substring(7)
            url.startsWith("wss://", ignoreCase = true)  -> { /* keep */ }
            url.startsWith("ws://", ignoreCase = true)   -> { /* keep */ }
            else                                          -> url = "ws://$url"
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = schemeEnd + 3
        if (afterScheme >= url.length) return null
        // Strip path and query — keep only scheme://host[:port]
        val pathStart = url.indexOf('/', afterScheme)
        val qStart    = url.indexOf('?', afterScheme)
        val cut = when {
            pathStart < 0 && qStart < 0 -> url.length
            pathStart < 0               -> qStart
            qStart < 0                  -> pathStart
            else                        -> minOf(pathStart, qStart)
        }
        val hostPort = url.substring(0, cut)
        if (hostPort.length <= afterScheme) return null
        return "$hostPort/?EIO=3&transport=websocket"
    }
}
