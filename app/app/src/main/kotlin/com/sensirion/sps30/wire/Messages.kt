package com.sensirion.sps30.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decoded server -> client messages. Mirrors the `MSG_*` payloads documented in
 * `wire.py`. Parsing is tolerant (never throws): a malformed or unknown frame
 * yields `null` or [ServerMessage.Unknown] rather than crashing the read loop.
 */
sealed interface ServerMessage {

    data class Hello(
        val product: String,
        val serial: String,
        val firmware: String,
        val state: String,
        val csv: String?,
        val index: String,
        val detail: String = "",
        val phaseSeconds: Double? = null,
        val profile: AcquisitionProfile? = null,
        val power: PowerStatus? = null,
        /** False when the sensor firmware predates Sleep/Wake (< 2.0), so the UI
         *  can disable low-power mode instead of offering an impossible option. */
        val supportsSleep: Boolean = true,
        /** This connection's id in the roster, so the client can spot itself. */
        val clientId: Int? = null,
        val maxClients: Int = 0,
    ) : ServerMessage

    /** The daemon's active acquisition profile (mirrors `wire.DEFAULT_PROFILE`). */
    data class AcquisitionProfile(
        val mode: String,
        val intervalSeconds: Double,
        val cycleSeconds: Double,
        val warmupSeconds: Double,
        val averageSeconds: Double,
    ) : ServerMessage {
        val dutyCycling: Boolean get() = mode == Wire.MODE_DUTY_CYCLE
    }

    /**
     * Host power telemetry. On the mains-powered Pi most fields are null and the
     * useful signal is [undervoltage]; a battery ESP32-C6 host fills in the rest.
     */
    data class PowerStatus(
        val source: String,
        val charging: Boolean = false,
        val percent: Double? = null,
        val voltageV: Double? = null,
        val runtimeHours: Double? = null,
        /** "gauge" (a fuel-gauge IC) or "modelled" (duty-cycle arithmetic). */
        val method: String = "none",
        /** Modelled average sensor draw for the active profile, mA. */
        val averageMa: Double? = null,
        /** Hours of measurement before the next scheduled fan clean. */
        val cleanDueInHours: Double? = null,
        val undervoltage: Boolean = false,
    ) : ServerMessage {
        val onBattery: Boolean get() = source == "battery"
    }

    /** A live measurement. `values` maps each present [Wire.FIELDS] name to its number. */
    data class Reading(
        val csv: String?,
        val index: String,
        val timestamp: String,
        val values: Map<String, Double>,
        /** Non-blank => disturbed/unreliable settling spike (see [Wire.COL_EVENT]). */
        val event: String = "",
    ) : ServerMessage

    /** [phaseSeconds] is how long this phase is expected to last, when the daemon
     *  knows - the duty-cycle phases. The client counts it down locally rather
     *  than the daemon ticking once a second, which would defeat the point of a
     *  mode whose whole purpose is that nothing runs between measurements. */
    data class StateMsg(
        val state: String,
        val detail: String,
        val phaseSeconds: Double? = null,
    ) : ServerMessage

    data class RawResponse(val request: String, val response: String) : ServerMessage

    data class FileEntry(val name: String, val rows: Int, val bytes: Long, val start: String?)

    data class FileList(val files: List<FileEntry>) : ServerMessage

    data class FileData(
        val name: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val done: Boolean,
    ) : ServerMessage

    /** [needsControl] marks a refusal caused by someone else holding the lock,
     *  so the UI can offer to take control instead of just showing an error. */
    data class ErrorMsg(
        val message: String,
        val needsControl: Boolean = false,
        val atCapacity: Boolean = false,
    ) : ServerMessage

    /** Who currently may change the sensor's state. Null holder means nobody. */
    data class Control(
        val holder: Int?,
        val holderName: String?,
        val expiresIn: Double,
    ) : ServerMessage

    data class Watcher(val id: Int, val name: String)

    /** Everyone connected to the sensor right now. */
    data class Clients(val clients: List<Watcher>, val max: Int) : ServerMessage

    /** Header for a session-archive transfer: what is coming and how much. */
    data class BackupMeta(
        val name: String,
        val bytes: Long,
        val sessions: Int,
        val rows: Long,
    ) : ServerMessage

    /** One base64 slice of the archive. [done] marks the end of the stream. */
    data class BackupChunk(
        val seq: Int,
        val data: String,
        val done: Boolean,
    ) : ServerMessage

    /** A well-formed frame whose `type` this client does not model. */
    data class Unknown(val type: String) : ServerMessage
}

/**
 * Messages that carry file content and so must never be dropped.
 *
 * The live stream is deliberately lossy - a slow UI must never stall the socket,
 * and a skipped reading is invisible a second later. A skipped archive chunk is
 * not: it silently corrupts the file. The daemon already sorts its own queue
 * this way; this is the client half of the same contract.
 */
fun ServerMessage.mustNotBeDropped(): Boolean =
    this is ServerMessage.BackupChunk ||
        this is ServerMessage.BackupMeta ||
        this is ServerMessage.FileData

object Messages {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Parse one JSONL frame into a [ServerMessage], or `null` if it is not usable. */
    fun parse(line: String): ServerMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return null
        }
        return fromObject(obj)
    }

    /** Decode a nested acquisition-profile object, tolerating missing fields. */
    private fun JsonElement.asProfile(): ServerMessage.AcquisitionProfile? {
        val o = this as? JsonObject ?: return null
        fun num(key: String, fallback: Double) =
            o[key]?.jsonPrimitive?.doubleOrNull ?: fallback
        return ServerMessage.AcquisitionProfile(
            mode = o["mode"]?.jsonPrimitive?.contentOrNull ?: Wire.MODE_CONTINUOUS,
            intervalSeconds = num("interval", 1.0),
            cycleSeconds = num("cycle_s", 900.0),
            warmupSeconds = num("warmup_s", 30.0),
            averageSeconds = num("average_s", 30.0),
        )
    }

    private fun JsonElement.asPower(): ServerMessage.PowerStatus? {
        val o = this as? JsonObject ?: return null
        fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull
        fun flag(key: String) = o[key]?.jsonPrimitive?.booleanOrNull ?: false
        return ServerMessage.PowerStatus(
            source = o["source"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            charging = flag("charging"),
            percent = num("percent"),
            voltageV = num("voltage_v"),
            runtimeHours = num("runtime_hours"),
            method = o["method"]?.jsonPrimitive?.contentOrNull ?: "none",
            averageMa = num("average_ma"),
            cleanDueInHours = num("clean_due_in_hours"),
            undervoltage = flag("undervoltage"),
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strOr(key: String, default: String = ""): String = str(key) ?: default

    private fun fromObject(obj: JsonObject): ServerMessage? {
        return when (val type = obj.str("type")) {
            Wire.MSG_HELLO -> ServerMessage.Hello(
                product = obj.strOr("product"),
                serial = obj.strOr("serial"),
                firmware = obj.strOr("firmware"),
                state = obj.strOr("state"),
                csv = obj.str("csv"),
                index = obj.strOr("index"),
                detail = obj.strOr("detail"),
                phaseSeconds = obj["seconds"]?.jsonPrimitive?.doubleOrNull,
                profile = obj["profile"]?.asProfile(),
                power = obj["power"]?.asPower(),
                // Absent on older daemons; assume capable rather than hiding the
                // feature from a sensor that supports it.
                supportsSleep = obj["supports_sleep"]?.jsonPrimitive?.booleanOrNull ?: true,
                clientId = obj["client_id"]?.jsonPrimitive?.intOrNull,
                maxClients = obj["max_clients"]?.jsonPrimitive?.intOrNull ?: 0,
            )

            Wire.MSG_PROFILE -> obj["profile"]?.asProfile()

            Wire.MSG_POWER -> obj["power"]?.asPower()

            Wire.MSG_READING -> {
                val values = LinkedHashMap<String, Double>()
                for (field in Wire.FIELDS) {
                    obj[field]?.jsonPrimitive?.doubleOrNull?.let { values[field] = it }
                }
                ServerMessage.Reading(
                    csv = obj.str("csv"),
                    index = obj.strOr("index"),
                    timestamp = obj.strOr("timestamp"),
                    values = values,
                    event = obj.strOr(Wire.COL_EVENT),
                )
            }

            Wire.MSG_STATE -> ServerMessage.StateMsg(
                state = obj.strOr("state"),
                detail = obj.strOr("detail"),
                phaseSeconds = obj["seconds"]?.jsonPrimitive?.doubleOrNull,
            )

            Wire.MSG_RAW -> ServerMessage.RawResponse(
                request = obj.strOr("request"),
                response = obj.strOr("response"),
            )

            Wire.MSG_FILES -> {
                val files = obj["files"]?.jsonArray?.mapNotNull { element ->
                    val fileObj = element as? JsonObject ?: return@mapNotNull null
                    ServerMessage.FileEntry(
                        name = fileObj.strOr("name"),
                        rows = fileObj["rows"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = fileObj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        start = fileObj.str("start"),
                    )
                } ?: emptyList()
                ServerMessage.FileList(files)
            }

            Wire.MSG_FILE_DATA -> {
                val columns = obj["columns"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { rowElement ->
                    rowElement.jsonArray.map { cell -> cell.jsonPrimitive.contentOrNull ?: "" }
                } ?: emptyList()
                ServerMessage.FileData(
                    name = obj.strOr("name"),
                    columns = columns,
                    rows = rows,
                    done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            Wire.MSG_BACKUP_META -> ServerMessage.BackupMeta(
                name = obj.strOr("name"),
                bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                sessions = obj["sessions"]?.jsonPrimitive?.intOrNull ?: 0,
                rows = obj["rows"]?.jsonPrimitive?.longOrNull ?: 0L,
            )

            Wire.MSG_BACKUP_CHUNK -> ServerMessage.BackupChunk(
                seq = obj["seq"]?.jsonPrimitive?.intOrNull ?: 0,
                data = obj.strOr("data"),
                done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            )

            Wire.MSG_ERROR -> ServerMessage.ErrorMsg(
                message = obj.strOr("message"),
                needsControl = obj["needs_control"]?.jsonPrimitive?.booleanOrNull ?: false,
                atCapacity = obj["at_capacity"]?.jsonPrimitive?.booleanOrNull ?: false,
            )

            Wire.MSG_CONTROL -> ServerMessage.Control(
                holder = obj["holder"]?.jsonPrimitive?.intOrNull,
                holderName = obj.str("holder_name"),
                expiresIn = obj["expires_in"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )

            Wire.MSG_CLIENTS -> {
                val list = obj["clients"]?.jsonArray?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    ServerMessage.Watcher(id, o["name"]?.jsonPrimitive?.contentOrNull ?: "client")
                } ?: emptyList()
                ServerMessage.Clients(list, obj["max"]?.jsonPrimitive?.intOrNull ?: 0)
            }

            null -> null
            else -> ServerMessage.Unknown(type)
        }
    }
}
