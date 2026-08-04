package com.saatiril.andro.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.UUID

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Engine.IO v3 + Socket.IO packet codec
 * ═════════════════════════════════════════════════════════════════════════
 *
 * This implements the wire protocol used by `io.socket:socket.io-client:2.1.0`
 * (Engine.IO v3) so the existing Saatiril Operator APK can connect to the
 * Android Admin app acting as the LAN server.
 *
 * The Node `socket.io@4.8.3` server (used by the Electron app and the
 * `saatiril-socket` mini-service) enables `allowEIO3: true` for this client.
 * We replicate the same protocol here in pure Kotlin.
 *
 * ─── Engine.IO v3 packet types ──────────────────────────────
 *   0 open      — server→client, on connect: {"sid":...,"upgrades":["websocket"],"pingInterval":5000,"pingTimeout":15000}
 *   1 close     — either direction
 *   2 ping      — client→server (heartbeat)
 *   3 pong      — server→client (heartbeat response)
 *   4 message   — carries a socket.io packet in its data
 *   5 upgrade   — client→server (polling→websocket upgrade)
 *   6 noop      — server→client (to flush a long-poll when nothing to send)
 *
 * ─── Socket.IO packet types (carried inside Engine.IO type 4) ─
 *   0 CONNECT      — "40"
 *   1 DISCONNECT   — "41"
 *   2 EVENT        — "42[\"eventName\", arg1, arg2, ...]"
 *   3 ACK          — "43<id>[...args]"
 *   4 ERROR        — "44{\"message\":...}"
 *
 * ─── Polling payload framing (EIO3) ──────────────────────────
 *   Single packet  → sent RAW (no length prefix), matches Node engine.io-parser v3.
 *   Multiple pkts  → "<len1>:<pkt1><len2>:<pkt2>..." (length-prefixed).
 *   The decoder handles BOTH (falls back to single-packet if the length
 *   prefix doesn't parse / doesn't match).
 *
 * ─── WebSocket transport ─────────────────────────────────────
 *   Each Engine.IO packet is one WS text frame (no framing).
 *
 * Binary frames are NOT supported — photos travel as base64 strings inside
 * JSON text packets (maxHttpBufferSize 20MB), exactly like the Node server.
 */
object EngineIO {

    private const val TAG = "EngineIO"
    private val gson = Gson()

    // Engine.IO packet types
    const val TYPE_OPEN = 0
    const val TYPE_CLOSE = 1
    const val TYPE_PING = 2
    const val TYPE_PONG = 3
    const val TYPE_MESSAGE = 4
    const val TYPE_UPGRADE = 5
    const val TYPE_NOOP = 6

    // Socket.IO packet types (carried inside TYPE_MESSAGE)
    const val SIO_CONNECT = 0
    const val SIO_DISCONNECT = 1
    const val SIO_EVENT = 2
    const val SIO_ACK = 3
    const val SIO_ERROR = 4

    /** Generate a new Engine.IO session id (20-char base64-ish, like Node). */
    fun newSid(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder(20)
        repeat(20) { sb.append(chars[(Math.random() * chars.length).toInt()]) }
        return sb.toString()
    }

    // ─── Engine.IO packet encode/decode ──────────────────────────

    /** Encode an Engine.IO packet: `"$type$data"`. */
    fun encodeEioPacket(type: Int, data: String = ""): String = "$type$data"

    /** Decode an Engine.IO packet → (type, data). */
    fun decodeEioPacket(raw: String): Pair<Int, String> {
        if (raw.isEmpty()) return Pair(0, "")
        val type = raw[0].digitToIntOrNull()
            ?: run { Log.w(TAG, "Bad EIO packet type char: '${raw[0]}'"); return Pair(0, raw) }
        return Pair(type, if (raw.length > 1) raw.substring(1) else "")
    }

    // ─── Socket.IO packet encode/decode ──────────────────────────

    /**
     * Encode a socket.io EVENT as an Engine.IO message packet.
     * Result: `"42"+jsonArray` wrapped as EIO message: `"4"+that`.
     * Full wire string: `42["eventName",{...}]` preceded by EIO type 4 → `442["eventName",{...}]`.
     */
    fun encodeSioEvent(eventName: String, vararg args: Any?): String {
        val arr = JsonArray()
        arr.add(eventName)
        args.forEach { arg ->
            arr.add(gson.toJsonTree(arg))
        }
        // EIO message (type 4) + socket.io EVENT (type 2) + json
        return "4" + "2" + gson.toJson(arr)
    }

    /** Encode a socket.io CONNECT ack: EIO type 4 + SIO type 0 → "40". */
    fun encodeSioConnect(namespace: String = "/"): String {
        return if (namespace == "/") "40" else "40$namespace,"
    }

    /**
     * Decode a socket.io packet (the data part of an EIO message).
     * Returns a [SioPacket] or null on parse failure.
     */
    fun decodeSioPacket(data: String): SioPacket? {
        if (data.isEmpty()) return null
        val type = data[0].digitToIntOrNull() ?: run {
            Log.w(TAG, "Bad SIO packet type: '${data[0]}'"); return null
        }
        val rest = if (data.length > 1) data.substring(1) else ""
        return when (type) {
            SIO_CONNECT -> SioPacket.Connect
            SIO_DISCONNECT -> SioPacket.Disconnect
            SIO_EVENT -> {
                try {
                    val arr = JsonParser.parseString(rest).asJsonArray
                    if (arr.size() == 0) return null
                    val name = arr.get(0).asString
                    val args = if (arr.size() > 1) arr.get(1) else null
                    SioPacket.Event(name, args)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SIO EVENT: ${e.message}"); null
                }
            }
            SIO_ACK -> {
                // 43<id>[args] — not used by Saatiril, ignore
                null
            }
            SIO_ERROR -> SioPacket.Error(rest)
            else -> null
        }
    }

    sealed class SioPacket {
        object Connect : SioPacket()
        object Disconnect : SioPacket()
        data class Event(val name: String, val args: JsonElement?) : SioPacket()
        data class Error(val message: String) : SioPacket()
    }

    // ─── Polling payload framing (EIO3) ──────────────────────────

    /**
     * Encode a polling payload.
     * Single packet → raw. Multiple → length-prefixed framing.
     * Matches Node `engine.io-parser@3` `encodePayload`.
     */
    fun encodePollingPayload(packets: List<String>): String {
        if (packets.isEmpty()) return ""
        if (packets.size == 1) return packets[0]
        val sb = StringBuilder()
        for (p in packets) {
            sb.append(p.length).append(':').append(p)
        }
        return sb.toString()
    }

    /**
     * Decode a polling payload — handles both raw single packets and
     * length-prefixed multi-packet payloads (robust fallback).
     */
    fun decodePollingPayload(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val packets = mutableListOf<String>()
        var i = 0
        while (i < raw.length) {
            // Try to read a length prefix: digits followed by ':'
            var j = i
            while (j < raw.length && raw[j].isDigit()) j++
            if (j > i && j < raw.length && raw[j] == ':') {
                val len = raw.substring(i, j).toIntOrNull()
                if (len != null) {
                    val start = j + 1
                    if (start + len <= raw.length) {
                        packets.add(raw.substring(start, start + len))
                        i = start + len
                        continue
                    }
                }
            }
            // Not a valid length prefix → treat the rest as a single packet
            packets.add(raw.substring(i))
            break
        }
        return packets
    }
}
