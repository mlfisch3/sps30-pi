package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Integration tests: a real loopback TCP server stands in for the daemon. */
class DaemonClientTest {

    private val hello =
        """{"type":"hello","product":"00080000","serial":"E316","firmware":"2.1",""" +
            """"state":"measuring","csv":"c.csv","index":"0000001"}"""
    private val reading =
        """{"type":"reading","csv":"c.csv","index":"0000002","timestamp":"t","pm2_5":1.5}"""

    @Test
    fun connectsReachesConnectedAndStreamsFrames() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = DaemonClient(io = Dispatchers.IO)
        val received = CopyOnWriteArrayList<ServerMessage>()

        val collector = launch(Dispatchers.IO) { client.messages.collect { received.add(it) } }
        delay(150) // ensure the SharedFlow (replay=0) is subscribed before frames fly

        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                conn.getOutputStream().apply {
                    write((hello + "\n").toByteArray())
                    write((reading + "\n").toByteArray())
                    flush()
                }
                Thread.sleep(400) // hold the session open
                conn.close()
            }
        }

        val clientJob = launch { client.connectLoop("127.0.0.1", server.localPort) }

        withTimeout(5000) {
            while (received.size < 2) delay(20)
        }

        assertTrue("state should reach Connected",
            client.state.value is ConnectionState.Connected)
        assertTrue("hello parsed", received.any { it is ServerMessage.Hello })
        val r = received.filterIsInstance<ServerMessage.Reading>().firstOrNull()
        assertTrue("reading parsed", r != null)
        assertEquals(1.5, r!!.values["pm2_5"]!!, 1e-9)

        clientJob.cancel(); collector.cancel(); serverJob.cancel()
        server.close()
    }

    @Test
    fun sendDeliversFramedCommandToServer() = runBlocking {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = DaemonClient(io = Dispatchers.IO)
        val gotLine = CopyOnWriteArrayList<String>()

        val serverJob = launch(Dispatchers.IO) {
            server.use {
                val conn = it.accept()
                // send a hello so the client is happily connected
                conn.getOutputStream().apply { write((hello + "\n").toByteArray()); flush() }
                val line = conn.getInputStream().bufferedReader().readLine()
                if (line != null) gotLine.add(line)
                conn.close()
            }
        }

        val clientJob = launch { client.connectLoop("127.0.0.1", server.localPort) }
        // Queued before/around connect; the outbound channel buffers until connected.
        delay(200)
        client.send(Commands.pause())

        withTimeout(5000) {
            while (gotLine.isEmpty()) delay(20)
        }
        assertEquals("""{"cmd":"pause"}""", gotLine.first())

        clientJob.cancel(); serverJob.cancel()
        server.close()
    }
}
