package com.saatiril.andro.server

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * ═════════════════════════════════════════════════════════════════════════
 * SaatirilServer — the LAN Socket.io hub, running ON the Android phone.
 * ═════════════════════════════════════════════════════════════════════════
 *
 * This is the Android equivalent of the Electron app's bundled server
 * (`electron/main.ts` + `mini-services/saatiril-socket/index.ts`). It:
 *  - Listens on port 3003 (or the next free port up to 3010).
 *  - Speaks Engine.IO v3 (polling + websocket) so the existing
 *    `io.socket:socket.io-client:2.1.0` Operator APK connects cleanly.
 *  - Relays `lan-message` packets between authenticated clients (MC, Operator).
 *  - Enforces the admin-set session password.
 *  - Notifies the app (via [onLanMessage]) of app events (PHOTOS_SAVED, MC_CALL,
 *    REQUEST_STATE, …) so [com.saatiril.andro.data.AdminViewModel] can save
 *    photos, update the project DB, and respond with SYNC_DB / FRAME_DATA.
 *
 * The server runs as a foreground service ([ServerService]) so it survives
 * backgrounding during a live ceremony.
 */
object SaatirilServer {

    private const val TAG = "SaatirilServer"
    private const val DEFAULT_PORT = 3003
    private const val MAX_PORT_ATTEMPTS = 8
    private const val PING_INTERVAL_MS = 5000L
    private const val PING_TIMEOUT_MS = 15000L
    private const val SESSION_TIMEOUT_MS = 90_000L   // drop silent sessions
    private const val POLL_HOLD_MS = 25_000L         // long-poll hold
    private const val MAX_PAYLOAD = 20 * 1024 * 1024 // 20MB (matches Node server)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: io.ktor.server.engine.ApplicationEngine? = null
    private var cleanupJob: Job? = null

    // ─── Public reactive state ──────────────────────────────────
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _lanIp = MutableStateFlow<String?>(null)
    val lanIp: StateFlow<String?> = _lanIp.asStateFlow()

    private val _clients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val clients: StateFlow<List<ClientInfo>> = _clients.asStateFlow()

    private val _stats = MutableStateFlow(ServerStats())
    val stats: StateFlow<ServerStats> = _stats.asStateFlow()

    // ─── Internal session store ─────────────────────────────────
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    @Volatile private var totalConnections: Long = 0
    @Volatile private var totalMessagesRelayed: Long = 0
    @Volatile private var sessionPasswordHash: String? = null

    /** App-level callback for lan-message events (set by AdminViewModel). */
    @Volatile
    var onLanMessage: ((event: String, data: JsonElement?, senderSid: String?) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start the ktor server on the first free port from 3003 upward.
     * Safe to call repeatedly (no-op if already running).
     *
     * @throws RuntimeException if the server fails to bind any port or ktor
     *   initialization fails (e.g. missing class after R8 shrinking). The
     *   caller ([com.saatiril.andro.data.AdminViewModel]) catches this and
     *   surfaces it to the user instead of crashing.
     */
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (_running.value) {
            Log.w(TAG, "Server already running on port ${_port.value}")
            return
        }
        val ip = detectLanIp()
        _lanIp.value = ip
        Log.i(TAG, "Starting Saatiril LAN server. LAN IP: $ip")

        var boundPort = DEFAULT_PORT
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_PORT_ATTEMPTS) {
            val tryPort = DEFAULT_PORT + attempt
            try {
                // Use the CIO factory OBJECT directly (not the string-based
                // embeddedServer that relies on ServiceLoader). On Android with
                // R8, the ServiceLoader file
                // `META-INF/services/io.ktor.server.engine.ApplicationEngineFactory`
                // can be stripped/empty → "Array is empty" error when ktor calls
                // `ServiceLoader.iterator().next()` on an empty loader.
                // Passing `io.ktor.server.cio.CIO` directly bypasses that lookup.
                engine = io.ktor.server.engine.embeddedServer(
                    factory = io.ktor.server.cio.CIO,
                    port = tryPort,
                    host = "0.0.0.0",
                    watchPaths = emptyList()
                ) {
                    install(WebSockets)
                    install(ContentNegotiation) { gson() }
                    routing {
                        get("/") { handlePollingGet(call) }
                        post("/") { handlePollingPost(call) }
                        webSocket("/") { handleWebSocket(this) }
                    }
                }.also { it.start(wait = false) }
                boundPort = tryPort
                lastError = null
                break
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) so NoClassDefFoundError
                // and NoSuchMethodError (common after R8 shrinking) are caught
                // and reported, rather than crashing the app silently.
                Log.w(TAG, "Port $tryPort bind/init failed: ${e.javaClass.simpleName}: ${e.message}", e)
                lastError = e
            }
        }

        if (lastError != null && engine == null) {
            Log.e(TAG, "Failed to bind any port or init ktor", lastError)
            _running.value = false
            throw RuntimeException("SaatirilServer init failed: ${lastError.message}", lastError)
        }

        _port.value = boundPort
        _running.value = true
        startedAt = System.currentTimeMillis()
        startCleanupJob()
        Log.i(TAG, "Server listening on 0.0.0.0:$boundPort (LAN IP $ip)")
    }

    /** Stop the server and drop all sessions. */
    fun stop() {
        Log.i(TAG, "Stopping Saatiril LAN server")
        cleanupJob?.cancel()
        cleanupJob = null
        try { engine?.stop(1000, 3000) } catch (e: Exception) { Log.w(TAG, "stop error: ${e.message}") }
        engine = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        _running.value = false
        _clients.value = emptyList()
        updateStats()
    }

    /** Set / clear the session password (admin action). */
    fun setSessionPasswordHash(hash: String?) {
        sessionPasswordHash = hash
        updateStats()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Broadcast helpers (used by AdminViewModel for MC actions, SYNC_DB, …)
    // ═══════════════════════════════════════════════════════════════

    /** Broadcast a `lan-message` (wrapped socket.io event) to ALL authenticated clients. */
    fun broadcastLanMessage(event: String, data: Any?) {
        val payload = JsonObject().apply {
            addProperty("event", event)
            add("data", if (data == null) com.google.gson.JsonNull.INSTANCE
                else com.google.gson.Gson().toJsonTree(data))
        }
        val packet = EngineIO.encodeSioEvent("lan-message", payload)
        broadcastRaw(packet, excludeSid = null)
    }

    /** Send a `lan-message` to ONE client (used for REQUEST_STATE / FRAME_DATA responses). */
    fun sendLanMessageToClient(sid: String, event: String, data: Any?) {
        val session = sessions[sid] ?: return
        val payload = JsonObject().apply {
            addProperty("event", event)
            add("data", if (data == null) com.google.gson.JsonNull.INSTANCE
                else com.google.gson.Gson().toJsonTree(data))
        }
        val packet = EngineIO.encodeSioEvent("lan-message", payload)
        sendToSession(session, packet)
    }

    /** Broadcast a raw socket.io event to all authenticated clients (not wrapped in lan-message). */
    fun broadcastEvent(eventName: String, vararg args: Any?) {
        val packet = EngineIO.encodeSioEvent(eventName, *args)
        broadcastRaw(packet, excludeSid = null)
    }

    private fun broadcastRaw(eioPacket: String, excludeSid: String?) {
        sessions.values.toList().forEach { s ->
            if (s.sid != excludeSid && s.authenticated) {
                sendToSession(s, eioPacket)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Polling transport (HTTP GET + POST on path "/")
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handlePollingGet(call: ApplicationCall) {
        setCorsHeaders(call)
        val transport = call.request.queryParameters["transport"]
        val eio = call.request.queryParameters["EIO"]
        val sid = call.request.queryParameters["sid"]

        if (eio != "3" || transport != "polling") {
            call.respondText("Engine.IO v3 polling expected", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }

        if (sid == null) {
            // ── New session handshake ──
            val newSid = EngineIO.newSid()
            val session = ClientSession(newSid).apply { this.transport = "polling" }
            sessions[newSid] = session
            totalConnections++
            Log.i(TAG, "New polling session: $newSid (total=${sessions.size})")
            val openPacket = EngineIO.encodeEioPacket(EngineIO.TYPE_OPEN,
                """{"sid":"$newSid","upgrades":["websocket"],"pingInterval":$PING_INTERVAL_MS,"pingTimeout":$PING_TIMEOUT_MS,"maxPayload":$MAX_PAYLOAD}""")
            updateClients()
            updateStats()
            call.respondText(openPacket, ContentType.Text.Plain)
            return
        }

        // ── Long-poll for outgoing packets ──
        val session = sessions[sid]
        if (session == null) {
            call.respondText("unknown sid", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        session.lastSeen = System.currentTimeMillis()
        // Only one poll at a time per session
        if (session.activePoll) {
            call.respondText(EngineIO.encodeEioPacket(EngineIO.TYPE_NOOP), ContentType.Text.Plain)
            return
        }
        session.activePoll = true
        try {
            val first = withTimeoutOrNull(POLL_HOLD_MS) { session.outgoingQueue.receive() }
            val packets = mutableListOf<String>()
            if (first != null) packets.add(first)
            // drain any additional queued packets
            while (true) {
                val extra = session.outgoingQueue.tryReceive().getOrNull() ?: break
                packets.add(extra)
            }
            if (packets.isEmpty()) {
                // nothing to send — flush with noop
                call.respondText(EngineIO.encodeEioPacket(EngineIO.TYPE_NOOP), ContentType.Text.Plain)
            } else {
                call.respondText(EngineIO.encodePollingPayload(packets), ContentType.Text.Plain)
            }
        } finally {
            session.activePoll = false
        }
    }

    private suspend fun handlePollingPost(call: ApplicationCall) {
        setCorsHeaders(call)
        val transport = call.request.queryParameters["transport"]
        val eio = call.request.queryParameters["EIO"]
        val sid = call.request.queryParameters["sid"]

        if (eio != "3" || transport != "polling") {
            call.respondText("Engine.IO v3 polling expected", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        if (sid == null) {
            call.respondText("missing sid", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        val session = sessions[sid]
        if (session == null) {
            call.respondText("unknown sid", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        session.lastSeen = System.currentTimeMillis()
        val body = call.receiveText()  // reads the POST body (polling payload)
        val packets = EngineIO.decodePollingPayload(body)
        for (p in packets) {
            handleEnginePacket(session, p)
        }
        call.respondText("ok", ContentType.Text.Plain)
    }

    private fun setCorsHeaders(call: ApplicationCall) {
        call.response.headers.append("Access-Control-Allow-Origin", "*")
        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        call.response.headers.append("Access-Control-Allow-Headers", "Content-Type")
        call.response.headers.append("Cache-Control", "no-store, no-cache, must-revalidate")
    }

    // ═══════════════════════════════════════════════════════════════
    //  WebSocket transport (path "/")
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleWebSocket(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val call = ws.call
        val transport = call.request.queryParameters["transport"]
        val eio = call.request.queryParameters["EIO"]
        val sid = call.request.queryParameters["sid"]
        if (eio != "3" || transport != "websocket") {
            ws.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "EIO3 websocket expected"))
            return
        }

        val session: ClientSession = if (sid == null) {
            // ── New WS-only session (no polling) ──
            val newSid = EngineIO.newSid()
            val s = ClientSession(newSid).apply { this.transport = "websocket" }
            sessions[newSid] = s
            totalConnections++
            s.wsSession = ws
            s.upgraded = true  // WS is the active transport immediately
            // send open packet
            val openPacket = EngineIO.encodeEioPacket(EngineIO.TYPE_OPEN,
                """{"sid":"$newSid","upgrades":[],"pingInterval":$PING_INTERVAL_MS,"pingTimeout":$PING_TIMEOUT_MS,"maxPayload":$MAX_PAYLOAD}""")
            ws.send(Frame.Text(openPacket))
            Log.i(TAG, "New WS-only session: $newSid (total=${sessions.size})")
            updateClients(); updateStats()
            s
        } else {
            // ── Upgrade existing polling session to WS ──
            val existing = sessions[sid]
            if (existing == null) {
                ws.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "unknown sid"))
                return
            }
            existing.wsSession = ws
            existing.transport = "websocket"
            // Note: upgraded stays false until we receive the `5` upgrade packet.
            Log.i(TAG, "WS upgrade for session: $sid")
            existing
        }

        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()
                session.lastSeen = System.currentTimeMillis()
                handleEnginePacket(session, raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS session ${session.sid} ended: ${e.message}")
        } finally {
            sessions.remove(session.sid)
            Log.i(TAG, "Session ${session.sid} removed (total=${sessions.size})")
            updateClients(); updateStats()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Engine.IO + Socket.IO packet handling
    // ═══════════════════════════════════════════════════════════════

    private fun handleEnginePacket(session: ClientSession, raw: String) {
        val (eioType, eioData) = EngineIO.decodeEioPacket(raw)
        when (eioType) {
            EngineIO.TYPE_PING -> {
                // Heartbeat: client pings, server responds pong.
                if (eioData == "probe") {
                    // Polling→WS upgrade probe: respond with `3probe`
                    sendToSession(session, EngineIO.encodeEioPacket(EngineIO.TYPE_PONG, "probe"))
                } else {
                    sendToSession(session, EngineIO.encodeEioPacket(EngineIO.TYPE_PONG))
                }
            }
            EngineIO.TYPE_UPGRADE -> {
                // Client completed the polling→WS upgrade. WS is now active.
                session.upgraded = true
                Log.i(TAG, "Session ${session.sid} upgraded to websocket")
                // Flush any held polling GET with a noop so it returns immediately.
                session.outgoingQueue.trySend(EngineIO.encodeEioPacket(EngineIO.TYPE_NOOP))
            }
            EngineIO.TYPE_MESSAGE -> {
                // Socket.io packet inside.
                val sio = EngineIO.decodeSioPacket(eioData)
                if (sio == null) {
                    Log.w(TAG, "Unparseable SIO packet: $eioData")
                    return
                }
                handleSocketIoPacket(session, sio)
            }
            EngineIO.TYPE_CLOSE -> {
                Log.i(TAG, "Client ${session.sid} sent close")
                session.close()
                sessions.remove(session.sid)
                updateClients(); updateStats()
            }
            else -> { /* ignore unknown */ }
        }
    }

    private fun handleSocketIoPacket(session: ClientSession, sio: EngineIO.SioPacket) {
        when (sio) {
            is EngineIO.SioPacket.Connect -> {
                // Client connected to namespace "/". Acknowledge.
                sendToSession(session, EngineIO.encodeSioConnect())
                Log.i(TAG, "SIO connect from ${session.sid}")
            }
            is EngineIO.SioPacket.Disconnect -> {
                Log.i(TAG, "SIO disconnect from ${session.sid}")
                session.close()
                sessions.remove(session.sid)
                updateClients(); updateStats()
            }
            is EngineIO.SioPacket.Event -> handleSocketIoEvent(session, sio.name, sio.args)
            is EngineIO.SioPacket.Error -> Log.w(TAG, "SIO error from ${session.sid}: ${sio.message}")
        }
    }

    private fun handleSocketIoEvent(session: ClientSession, name: String, args: JsonElement?) {
        when (name) {
            "identify" -> handleIdentify(session, args)
            "saatiril-ping" -> {
                // App-level ping: echo back as pong with same timestamp
                val ts = args?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: System.currentTimeMillis()
                sendToSession(session, EngineIO.encodeSioEvent("saatiril-pong", ts))
            }
            "lan-message" -> handleLanMessage(session, args)
            "SET_SESSION_PASSWORD", "CLEAR_SESSION_PASSWORD" -> {
                // Only admin may set/clear. On Android the admin sets it directly
                // via setSessionPasswordHash(); ignore external requests.
                Log.i(TAG, "Ignoring $name from ${session.sid} (admin-only, direct API)")
            }
            "server-stats" -> {
                sendToSession(session, EngineIO.encodeSioEvent("server-stats", stats.value.toJsonObject()))
            }
            else -> Log.d(TAG, "Unhandled SIO event '$name' from ${session.sid}")
        }
    }

    private fun handleIdentify(session: ClientSession, args: JsonElement?) {
        val obj = args as? JsonObject ?: JsonObject()
        val role = obj.get("role")?.asString ?: "unknown"
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val clientPwHash = obj.get("sessionPasswordHash")?.takeIf { !it.isJsonNull }?.asString

        session.role = role
        session.channel = channel

        val pwRequired = sessionPasswordHash != null
        if (pwRequired && clientPwHash != sessionPasswordHash) {
            session.authenticated = false
            session.pendingAuth = true
            sendToSession(session, EngineIO.encodeSioEvent("auth-failed",
                JsonObject().apply { addProperty("reason", "session_password_required") }))
            Log.i(TAG, "Auth FAILED for ${session.sid} (role=$role ch=$channel) — wrong/no password")
        } else {
            session.authenticated = true
            session.pendingAuth = false
            sendToSession(session, EngineIO.encodeSioEvent("auth-success",
                JsonObject().apply {
                    addProperty("role", role)
                    addProperty("channel", channel)
                }))
            Log.i(TAG, "Auth OK for ${session.sid} (role=$role ch=$channel)")
            // Notify others of the new client
            broadcastEvent("server-stats", stats.value.toJsonObject())
        }
        updateClients(); updateStats()
    }

    private fun handleLanMessage(session: ClientSession, args: JsonElement?) {
        val obj = args as? JsonObject
        val event = obj?.get("event")?.asString ?: return
        val data = obj.get("data")
        totalMessagesRelayed++

        // 1) Relay to all OTHER authenticated clients (dumb relay, matches Node server)
        val relayPacket = EngineIO.encodeSioEvent("lan-message", obj)
        sessions.values.toList().forEach { s ->
            if (s.sid != session.sid && s.authenticated) {
                sendToSession(s, relayPacket)
            }
        }

        // 2) Notify the app (AdminViewModel) so it can save photos / update DB / respond
        try {
            onLanMessage?.invoke(event, data, session.sid)
        } catch (e: Exception) {
            Log.e(TAG, "onLanMessage callback error for $event: ${e.message}", e)
        }
        updateStats()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Send helpers
    // ═══════════════════════════════════════════════════════════════

    private fun sendToSession(session: ClientSession, eioPacket: String) {
        val ws = session.wsSession
        if (ws != null && session.upgraded) {
            // Send via websocket (must be on the WS session's coroutine context)
            scope.launch {
                try { ws.send(Frame.Text(eioPacket)) } catch (e: Exception) { Log.w(TAG, "WS send failed: ${e.message}") }
            }
        } else {
            // Enqueue for polling
            session.outgoingQueue.trySend(eioPacket)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cleanup & state
    // ═══════════════════════════════════════════════════════════════

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (true) {
                delay(30_000)
                val now = System.currentTimeMillis()
                val toRemove = sessions.values.filter { now - it.lastSeen > SESSION_TIMEOUT_MS }
                toRemove.forEach { s ->
                    Log.i(TAG, "Reaping inactive session ${s.sid} (idle ${now - s.lastSeen}ms)")
                    s.close()
                    sessions.remove(s.sid)
                }
                if (toRemove.isNotEmpty()) { updateClients(); updateStats() }
            }
        }
    }

    private fun updateClients() {
        _clients.value = sessions.values.map { s ->
            ClientInfo(
                sid = s.sid,
                role = s.role ?: "unknown",
                channel = s.channel,
                authenticated = s.authenticated,
                transport = s.transport,
                connectedAt = s.connectedAt
            )
        }
    }

    private fun updateStats() {
        _stats.value = ServerStats(
            uptimeMs = if (_running.value) System.currentTimeMillis() - startedAt else 0,
            connectedClients = sessions.count { it.value.authenticated },
            totalConnections = totalConnections,
            totalMessagesRelayed = totalMessagesRelayed,
            maxConnections = 10,
            sessionPasswordActive = sessionPasswordHash != null
        )
    }

    @Volatile private var startedAt: Long = 0

    /** Detect the device's LAN IPv4 address (for QR codes / display). */
    private fun detectLanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses.toList()
                for (a in addrs) {
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) {
                        return a.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Session model
// ═══════════════════════════════════════════════════════════════

internal class ClientSession(val sid: String) {
    @Volatile var role: String? = null
    @Volatile var channel: Int = 1
    @Volatile var authenticated: Boolean = false
    @Volatile var pendingAuth: Boolean = false
    @Volatile var transport: String = "polling"
    @Volatile var lastSeen: Long = System.currentTimeMillis()
    @Volatile var connectedAt: Long = System.currentTimeMillis()
    @Volatile var wsSession: io.ktor.websocket.WebSocketSession? = null
    @Volatile var upgraded: Boolean = false
    @Volatile var activePoll: Boolean = false
    val outgoingQueue = Channel<String>(Channel.UNLIMITED)

    fun close() {
        try { outgoingQueue.close() } catch (_: Exception) {}
        // Note: the WebSocketSession is closed by the handleWebSocket() finally block
        // when the channel completes; we just null the reference here.
        wsSession = null
    }
}

data class ClientInfo(
    val sid: String,
    val role: String,
    val channel: Int,
    val authenticated: Boolean,
    val transport: String,
    val connectedAt: Long
)

data class ServerStats(
    val uptimeMs: Long = 0,
    val connectedClients: Int = 0,
    val totalConnections: Long = 0,
    val totalMessagesRelayed: Long = 0,
    val maxConnections: Int = 10,
    val sessionPasswordActive: Boolean = false
) {
    fun toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("status", "ok")
        addProperty("uptime", uptimeMs)
        addProperty("connectedClients", connectedClients)
        addProperty("totalConnections", totalConnections)
        addProperty("totalMessagesRelayed", totalMessagesRelayed)
        addProperty("maxConnections", maxConnections)
        addProperty("sessionPasswordActive", sessionPasswordActive)
    }
}
