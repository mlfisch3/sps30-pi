package com.sensirion.sps30.net

import com.sensirion.sps30.wire.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reassembler must recover whole JSONL lines from arbitrary byte chunking -
 * which is exactly what BLE notifications impose (MTU-capped, so a reading spans
 * several notifications and one notification can hold parts of two lines).
 */
class JsonlReassemblerTest {

    private fun collect(vararg chunks: String): List<ServerMessage> {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        for (c in chunks) r.feed(c.toByteArray(Charsets.UTF_8))
        return out
    }

    private val reading = """{"type":"reading","index":"0000001","timestamp":"t","pm2_5":1.5}"""
    private val hello = """{"type":"hello","product":"p","serial":"s","firmware":"f","state":"measuring","csv":"c","index":"1"}"""

    @Test fun wholeLineInOneChunk() {
        val m = collect(hello + "\n")
        assertEquals(1, m.size)
        assertTrue(m[0] is ServerMessage.Hello)
    }

    @Test fun lineSplitAcrossManyChunks() {
        // simulate BLE: a 200-byte line delivered in 20-byte notifications
        val full = reading + "\n"
        val chunks = full.chunked(20).toTypedArray()
        val m = collect(*chunks)
        assertEquals(1, m.size)
        assertEquals(1.5, (m[0] as ServerMessage.Reading).values["pm2_5"]!!, 1e-9)
    }

    @Test fun oneChunkHoldsPartsOfTwoLines() {
        // ...end of line A][newline][start of line B... within a single notification
        val stream = hello + "\n" + reading + "\n"
        val m = collect(stream.substring(0, 40), stream.substring(40))
        assertEquals(2, m.size)
        assertTrue(m[0] is ServerMessage.Hello)
        assertTrue(m[1] is ServerMessage.Reading)
    }

    @Test fun trailingPartialLineWaitsForMore() {
        val out = ArrayList<ServerMessage>()
        val r = JsonlReassembler { out.add(it) }
        r.feed((reading.substring(0, 30)).toByteArray())
        assertEquals(0, out.size) // no newline yet
        r.feed((reading.substring(30) + "\n").toByteArray())
        assertEquals(1, out.size)
    }

    @Test fun blankAndGarbageLinesAreSkipped() {
        val m = collect("\n\n", "not json\n", hello + "\n")
        assertEquals(1, m.size) // only the hello parses
    }

    @Test fun multipleReadingsBackToBack() {
        val stream = (1..5).joinToString("") {
            """{"type":"reading","index":"$it","timestamp":"t","pm2_5":$it.0}""" + "\n"
        }
        val m = collect(stream)
        assertEquals(5, m.size)
    }
}
