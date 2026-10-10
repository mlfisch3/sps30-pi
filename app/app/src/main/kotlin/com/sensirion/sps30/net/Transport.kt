package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/**
 * Where a connection is in its lifecycle. `endpoint` is a transport-neutral label
 * (e.g. "192.168.1.159:8730" for Wi-Fi, or a device name/MAC for Bluetooth) so the
 * same UI works for both transports.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val endpoint: String) : ConnectionState
    data class Connected(val endpoint: String) : ConnectionState
    data class Reconnecting(val detail: String) : ConnectionState
}

/**
 * A source of the daemon's JSONL stream, independent of *how* the bytes move.
 * Both [TcpTransport] (Wi-Fi) and [BleTransport] (Bluetooth LE) implement this and
 * emit identical [ServerMessage]s, so all message-folding / history / chart code is
 * transport-agnostic. The payload is the same newline-delimited JSONL either way.
 */
interface Transport {
    val state: StateFlow<ConnectionState>
    /** Lossy (drop-oldest) - a slow collector must never back-pressure the link. */
    val messages: SharedFlow<ServerMessage>
    /** Queue an already-framed command line (see [com.sensirion.sps30.wire.Commands]). */
    fun send(line: String)
    /** Connect and stream until the coroutine is cancelled (with its own reconnect). */
    suspend fun run()

    /**
     * Force-release the underlying link *now*, so a transport switch tears the old
     * connection down promptly. Needed for the TCP path: `java.net.Socket` blocking
     * reads ignore thread interruption, so coroutine cancellation alone leaves the
     * socket half-open until the daemon drops it. The BLE path unwinds on cancel, so
     * it keeps the default no-op.
     */
    fun stop() {}
}

/**
 * Reassembles a byte stream of newline-delimited JSONL into [ServerMessage]s,
 * tolerating arbitrary chunk boundaries - a JSONL line may span several chunks and
 * one chunk may hold parts of two lines. Shared by TCP reads and BLE notifications
 * (BLE notifications are MTU-capped, so a reading routinely spans notifications).
 * Pure and unit-testable; keeps a partial trailing line for the next [feed].
 */
class JsonlReassembler(private val onMessage: (ServerMessage) -> Unit) {
    private val residual = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        residual.write(bytes, 0, len)
        val all = residual.toByteArray()
        var start = 0
        var i = 0
        while (i < all.size) {
            if (all[i] == NEWLINE) {
                val line = String(all, start, i - start, Charsets.UTF_8)
                Messages.parse(line)?.let(onMessage)
                start = i + 1
            }
            i++
        }
        residual.reset()
        if (start < all.size) residual.write(all, start, all.size - start)
    }

    fun reset() = residual.reset()

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
