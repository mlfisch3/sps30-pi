package com.sensirion.sps30.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Kotlin protocol port against drift from `wire.py`. Frames here are
 * byte-for-byte the shapes the daemon emits (captured from the live Pi).
 */
class WireTest {

    @Test
    fun parsesHello() {
        val line = """{"type":"hello","product":"00080000","serial":"E3164D2544338487",""" +
            """"firmware":"2.1","state":"measuring","csv":"SPS30-20260722_021827.csv","index":"0000124"}"""
        val msg = Messages.parse(line)
        assertTrue(msg is ServerMessage.Hello)
        msg as ServerMessage.Hello
        assertEquals("00080000", msg.product)
        assertEquals("E3164D2544338487", msg.serial)
        assertEquals("2.1", msg.firmware)
        assertEquals(Wire.STATE_MEASURING, msg.state)
        assertEquals("SPS30-20260722_021827.csv", msg.csv)
        assertEquals("0000124", msg.index)
    }

    @Test
    fun parsesReadingWithAllFields() {
        val line = """{"type":"reading","csv":"SPS30-20260722_021827.csv","index":"0000125",""" +
            """"timestamp":"2026-07-22T02:18:28-04:00","pm1_0":1.3548,"pm2_5":1.4327,"pm4_0":1.4327,""" +
            """"pm10":1.4327,"nc0_5":9.1,"nc1_0":10.7,"nc2_5":10.9,"nc4_0":10.9,"nc10":10.9,""" +
            """"typical_size":0.54}"""
        val msg = Messages.parse(line)
        assertTrue(msg is ServerMessage.Reading)
        msg as ServerMessage.Reading
        assertEquals("SPS30-20260722_021827.csv", msg.csv)
        assertEquals("0000125", msg.index)
        assertEquals("2026-07-22T02:18:28-04:00", msg.timestamp)
        // Every one of the ten fields present and typed as a number.
        assertEquals(Wire.FIELDS.size, msg.values.size)
        assertEquals(1.3548, msg.values["pm1_0"]!!, 1e-9)
        assertEquals(1.4327, msg.values["pm2_5"]!!, 1e-9)
        assertEquals(0.54, msg.values["typical_size"]!!, 1e-9)
    }

    @Test
    fun readingToleratesIntegerAndMissingFields() {
        // pm values as integers (JSON allows), and a couple of fields absent.
        val line = """{"type":"reading","index":"0000001","timestamp":"t","pm1_0":0,"pm2_5":2}"""
        val msg = Messages.parse(line) as ServerMessage.Reading
        assertEquals(0.0, msg.values["pm1_0"]!!, 1e-9)
        assertEquals(2.0, msg.values["pm2_5"]!!, 1e-9)
        assertNull(msg.values["pm10"]) // absent -> not in the map
        assertNull(msg.csv)            // absent -> null, not a crash
    }

    @Test
    fun parsesState() {
        val line = """{"type":"state","state":"paused","detail":"paused by user"}"""
        val msg = Messages.parse(line) as ServerMessage.StateMsg
        assertEquals(Wire.STATE_PAUSED, msg.state)
        assertEquals("paused by user", msg.detail)
    }

    @Test
    fun parsesRawResponse() {
        val line = """{"type":"raw_response","request":"7e0000...7e","response":"7e0100...7e"}"""
        val msg = Messages.parse(line) as ServerMessage.RawResponse
        assertEquals("7e0000...7e", msg.request)
        assertEquals("7e0100...7e", msg.response)
    }

    @Test
    fun parsesFileList() {
        val line = """{"type":"file_list","files":[""" +
            """{"name":"SPS30-a.csv","rows":120,"bytes":9001,"start":"2026-07-22T00:00:00-04:00"},""" +
            """{"name":"SPS30-b.csv","rows":5,"bytes":401,"start":null}]}"""
        val msg = Messages.parse(line) as ServerMessage.FileList
        assertEquals(2, msg.files.size)
        assertEquals("SPS30-a.csv", msg.files[0].name)
        assertEquals(120, msg.files[0].rows)
        assertEquals(9001L, msg.files[0].bytes)
        assertEquals("2026-07-22T00:00:00-04:00", msg.files[0].start)
        assertNull(msg.files[1].start)
    }

    @Test
    fun parsesFileData() {
        val line = """{"type":"file_data","name":"SPS30-a.csv",""" +
            """"columns":["index","timestamp","pm1_0"],""" +
            """"rows":[["0000001","t1","1.0"],["0000002","t2","2.0"]],"done":true}"""
        val msg = Messages.parse(line) as ServerMessage.FileData
        assertEquals("SPS30-a.csv", msg.name)
        assertEquals(listOf("index", "timestamp", "pm1_0"), msg.columns)
        assertEquals(2, msg.rows.size)
        assertEquals(listOf("0000002", "t2", "2.0"), msg.rows[1])
        assertTrue(msg.done)
    }

    @Test
    fun parsesError() {
        val msg = Messages.parse("""{"type":"error","message":"nope"}""") as ServerMessage.ErrorMsg
        assertEquals("nope", msg.message)
    }

    @Test
    fun unknownTypeBecomesUnknownNotCrash() {
        val msg = Messages.parse("""{"type":"future_thing","x":1}""")
        assertTrue(msg is ServerMessage.Unknown)
        assertEquals("future_thing", (msg as ServerMessage.Unknown).type)
    }

    @Test
    fun garbageAndBlankAreNull() {
        assertNull(Messages.parse(""))
        assertNull(Messages.parse("   "))
        assertNull(Messages.parse("not json"))
        assertNull(Messages.parse("{ this is broken"))
        assertNull(Messages.parse("""{"no":"type"}"""))
    }

    @Test
    fun commandsEncodeExactFrames() {
        assertEquals("{\"cmd\":\"pause\"}\n", Commands.pause())
        assertEquals("{\"cmd\":\"resume\"}\n", Commands.resume())
        assertEquals("{\"cmd\":\"clean\"}\n", Commands.clean())
        assertEquals("{\"cmd\":\"restart\"}\n", Commands.restart())
        assertEquals("{\"cmd\":\"list_files\"}\n", Commands.listFiles())
        assertEquals("{\"cmd\":\"raw\",\"hex\":\"7e007e\"}\n", Commands.raw("7e007e"))
        assertEquals("{\"cmd\":\"get_file\",\"name\":\"a.csv\"}\n", Commands.getFile("a.csv"))
        assertEquals(
            "{\"cmd\":\"get_file\",\"name\":\"a.csv\",\"since_index\":5}\n",
            Commands.getFile("a.csv", 5),
        )
    }

    @Test
    fun formatIndexMatchesPython() {
        assertEquals("0000001", Wire.formatIndex(1))
        assertEquals("0000124", Wire.formatIndex(124))
        assertEquals("1234567", Wire.formatIndex(1234567))
    }
}
