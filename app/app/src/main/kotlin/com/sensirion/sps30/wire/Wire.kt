package com.sensirion.sps30.wire

/**
 * Kotlin port of the constants in `wire.py` - the single source of truth for the
 * SPS30 daemon/client line protocol. Keep this in lockstep with `wire.py`; the
 * round-trip tests in `WireTest` guard against drift.
 */
object Wire {
    const val DEFAULT_PORT = 8730

    /** The ten SPS30 outputs, in fixed order (matches `wire.FIELDS`). */
    val FIELDS: List<String> = listOf(
        "pm1_0", "pm2_5", "pm4_0", "pm10",
        "nc0_5", "nc1_0", "nc2_5", "nc4_0", "nc10",
        "typical_size",
    )

    /** Non-blank flags a reading disturbed by a (re)start/clean spike (see wire.py). */
    const val COL_EVENT = "event"

    val CSV_COLUMNS: List<String> = listOf("index", "timestamp") + FIELDS + COL_EVENT

    const val INDEX_WIDTH = 7

    // Server -> client message "type" values.
    const val MSG_HELLO = "hello"
    const val MSG_READING = "reading"
    const val MSG_STATE = "state"
    const val MSG_RAW = "raw_response"
    const val MSG_FILES = "file_list"
    const val MSG_FILE_DATA = "file_data"
    const val MSG_ERROR = "error"
    const val MSG_PROFILE = "profile"
    const val MSG_POWER = "power"
    const val MSG_CONTROL = "control"
    const val MSG_CLIENTS = "clients"
    const val MSG_BACKUP_META = "backup_meta"
    const val MSG_BACKUP_CHUNK = "backup_chunk"

    // Client -> server command "cmd" values.
    const val CMD_PAUSE = "pause"
    const val CMD_RESUME = "resume"
    const val CMD_CLEAN = "clean"
    const val CMD_RESTART = "restart"
    const val CMD_RAW = "raw"
    const val CMD_LIST_FILES = "list_files"
    const val CMD_GET_FILE = "get_file"
    const val CMD_SET_PROFILE = "set_profile"
    const val CMD_SHUTDOWN = "shutdown"
    const val CMD_IDENTIFY = "identify"
    const val CMD_CLAIM = "claim"
    const val CMD_RELEASE = "release"
    const val CMD_BACKUP = "backup"

    // Daemon acquisition states.
    const val STATE_CHECKING = "checking"
    const val STATE_MEASURING = "measuring"
    const val STATE_PAUSED = "paused"
    const val STATE_CLEANING = "cleaning"
    const val STATE_SLEEPING = "sleeping"
    const val STATE_WARMUP = "warmup"
    const val STATE_SHUTDOWN = "shutting_down"

    // Acquisition modes (see `wire.py`). CONTINUOUS measures non-stop; raising its
    // interval saves data, not power. DUTY_CYCLE is Sensirion's low-power
    // recommendation: wake, warm up, average, sleep.
    const val MODE_CONTINUOUS = "continuous"
    const val MODE_DUTY_CYCLE = "duty_cycle"

    /** Zero-padded measurement counter, e.g. 1 -> "0000001" (matches `format_index`). */
    fun formatIndex(n: Int): String = n.toString().padStart(INDEX_WIDTH, '0')

    /** The PM mass-concentration fields shown as the primary chart series. */
    val PM_FIELDS: List<String> = listOf("pm1_0", "pm2_5", "pm4_0", "pm10")
}
