package com.sensirion.sps30.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client -> server command frames. Each returns a complete newline-terminated
 * JSONL line ready to write to the socket, matching `wire.encode({"cmd": ...})`.
 */
object Commands {
    private fun frame(obj: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), obj) + "\n"

    private fun simple(cmd: String): String = frame(buildJsonObject { put("cmd", cmd) })

    fun pause(): String = simple(Wire.CMD_PAUSE)
    fun resume(): String = simple(Wire.CMD_RESUME)
    fun clean(): String = simple(Wire.CMD_CLEAN)
    fun restart(): String = simple(Wire.CMD_RESTART)
    fun listFiles(): String = simple(Wire.CMD_LIST_FILES)

    fun raw(hex: String): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_RAW)
        put("hex", hex)
    })

    fun getFile(name: String, sinceIndex: Int? = null): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_GET_FILE)
        put("name", name)
        if (sinceIndex != null) put("since_index", sinceIndex)
    })

    /** Switch acquisition profile. The daemon clamps anything out of range. */
    fun setProfile(
        mode: String,
        intervalSeconds: Double,
        cycleSeconds: Double,
        warmupSeconds: Double,
        averageSeconds: Double,
    ): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_SET_PROFILE)
        put("profile", buildJsonObject {
            put("mode", mode)
            put("interval", intervalSeconds)
            put("cycle_s", cycleSeconds)
            put("warmup_s", warmupSeconds)
            put("average_s", averageSeconds)
        })
    })

    /** Park the sensor, close the CSV and power the host down. */
    fun shutdown(): String = simple(Wire.CMD_SHUTDOWN)

    /** Announce a human-readable name so others can see who is connected. */
    fun identify(name: String): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_IDENTIFY)
        put("name", name)
    })

    /** Take the control lock, including from an absent holder. */
    fun claim(): String = simple(Wire.CMD_CLAIM)

    /** Give up the control lock. */
    fun release(): String = simple(Wire.CMD_RELEASE)

    /** Ask for every recorded session as one archive. Needs no control claim. */
    fun backup(): String = simple(Wire.CMD_BACKUP)
}
