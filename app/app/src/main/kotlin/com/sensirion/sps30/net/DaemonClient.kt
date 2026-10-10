package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.mustNotBeDropped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Coroutine TCP client for the SPS30 daemon. Frames the newline-delimited JSONL
 * stream into [ServerMessage]s on [messages], tracks [state], and accepts
 * already-framed command lines via [send].
 *
 * [connectLoop] runs until its coroutine is cancelled: it connects, streams,
 * and on any drop backs off and retries - optionally re-discovering the daemon
 * first (DHCP-friendly). Blocking socket reads run under [runInterruptible] so
 * cancellation promptly tears the connection down.
 */
class DaemonClient(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Live stream. Lossy on overflow (drop-oldest) - matches the daemon's own
    // per-client lossy reading queue; a slow UI must never stall the socket.
    private val _messages = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private val outbound = Channel<String>(capacity = 64)

    /** How long to hold the socket waiting for room before giving up on a
     *  message that must not be dropped. Generous: losing one corrupts a file. */
    private val DROP_GUARD_MS = 10_000

    // The live socket, exposed only so [stop] can force-close it. A blocking
    // socket read does not honour thread interruption, so closing the socket is
    // the only way to promptly unblock [readFramesBlocking] on cancellation.
    @Volatile private var activeSocket: Socket? = null

    /** Queue an already-framed command line (see [com.sensirion.sps30.wire.Commands]). */
    fun send(line: String) {
        outbound.trySend(line)
    }

    /** Close the current socket now, unblocking the reader so the coroutine can finish. */
    fun stop() {
        runCatching { activeSocket?.close() }
    }

    /**
     * Connect to [host]:[port] and keep the stream alive until cancelled.
     * @param rediscover optional: called before each reconnect to get a fresh
     *   host (e.g. after a DHCP change); return null to keep the current target.
     */
    suspend fun connectLoop(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 4000,
        rediscover: (suspend () -> String?)? = null,
    ) {
        var target = host
        var backoffMs = 500L
        while (currentCoroutineContext().isActive) {
            _state.value = ConnectionState.Connecting("$target:$port")
            // Tied to the Wi-Fi network from creation. A full-tunnel VPN would
            // otherwise swallow LAN traffic, and on a network with no internet
            // Android would route us out over mobile data instead.
            val socket = WifiNetworkBinder.createSocket()
            activeSocket = socket
            try {
                withContext(io) {
                    socket.connect(InetSocketAddress(target, port), connectTimeoutMs)
                }
                socket.soTimeout = 0
                _state.value = ConnectionState.Connected("$target:$port")
                backoffMs = 500L
                streamUntilClosed(socket)
                // Clean EOF (server closed).
                _state.value = ConnectionState.Reconnecting("connection closed")
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Reconnecting(e.message ?: "connection lost")
            } finally {
                runCatching { socket.close() }
            }

            if (!currentCoroutineContext().isActive) break
            if (rediscover != null) {
                runCatching { rediscover() }.getOrNull()?.let { target = it }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(8000L)
        }
    }

    /** Runs one connected session: a reader and a writer, until either ends. */
    private suspend fun streamUntilClosed(socket: Socket) = coroutineScope {
        val writer = launch(io) { pumpOutbound(socket) }
        try {
            runInterruptible(io) { readFramesBlocking(socket) }
        } finally {
            writer.cancel()
        }
    }

    /** Blocking read loop: byte-level newline framing via the shared reassembler. */
    private fun readFramesBlocking(socket: Socket) {
        val input = socket.getInputStream()
        val chunk = ByteArray(4096)
        val reassembler = JsonlReassembler { message ->
            if (!_messages.tryEmit(message) && message.mustNotBeDropped()) {
                // File content cannot be dropped the way a stale reading can.
                // This runs on the blocking reader thread, so waiting here simply
                // applies backpressure to the socket - which is the correct
                // response to a collector that has fallen behind.
                var waited = 0
                while (!_messages.tryEmit(message) && waited < DROP_GUARD_MS) {
                    Thread.sleep(2)
                    waited += 2
                }
            }
        }
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break // EOF
            reassembler.feed(chunk, n)
        }
    }

    /** Drains queued command lines to the socket until cancelled or the socket dies. */
    private suspend fun pumpOutbound(socket: Socket) {
        val output = socket.getOutputStream()
        for (line in outbound) {
            runInterruptible(io) {
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }
}
