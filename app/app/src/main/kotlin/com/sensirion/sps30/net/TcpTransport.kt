package com.sensirion.sps30.net

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * Wi-Fi transport: VPN-aware LAN discovery of the daemon, then JSONL over TCP with
 * auto-reconnect and re-discovery. A thin adapter over [DaemonClient] + [Discovery].
 */
class TcpTransport(private val port: Int = Wire.DEFAULT_PORT) : Transport {
    private val client = DaemonClient()

    override val state: StateFlow<ConnectionState> get() = client.state
    override val messages: SharedFlow<ServerMessage> get() = client.messages
    override fun send(line: String) = client.send(line)
    override fun stop() = client.stop()

    override suspend fun run() {
        val host = Discovery.discover(port, dispatcher = Dispatchers.IO)
            ?: throw IOException("no daemon found on the LAN")
        client.connectLoop(
            host = host,
            port = port,
            rediscover = { Discovery.discover(port, dispatcher = Dispatchers.IO) },
        )
    }
}
