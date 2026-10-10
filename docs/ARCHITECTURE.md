# SPS30 system - architecture and component contract

**As built, 2026-07-31.**
This describes how the system **is** structured and where the boundaries are.
For *what* it does and *why*, see `PRD.md`.

> An earlier version of this file was the pre-build design contract for the
> dashboard, written 2026-07-23 before the Android app, BLE, acquisition profiles,
> control arbitration, power telemetry or archive transfer existed. It had been
> carried forward unchanged into eight later worktrees and had become actively
> misleading: its CSV column list was missing `event`, and its message and command
> lists were roughly half the current set.
> The original is in the frozen ancestor worktrees, where it is now labelled as
> the historical design contract it was.

Every component builds to the shared definitions in **`wire.py`**.
Do not redefine field names, message types, CSV columns or the filename format
anywhere else; import them.
The Kotlin mirror in `apk/.../wire/` exists because Kotlin cannot import Python,
and it is a **mirror**, not a second source of truth: `wire.py` wins.

---

## 1. System overview

```
        SENSOR                    CONTROLLER                       CLIENTS
  +----------------+      +--------------------------+      +------------------+
  |     SPS30      |      |     sps30_daemon.py      |      |  Android app     |
  |  UART / SHDLC  |<====>|  owns the serial port    |<====>|  (phone/tablet)  |
  |  115200 8N1    |      |  acquisition FSM         | TCP  +------------------+
  +----------------+      |  writes CSV (the record) | 8730 +------------------+
                          |  broadcasts to N clients |<====>|  Textual TUI     |
                          +--------------------------+ JSONL+------------------+
                                    ^                              ^
                                    |  local TCP                   | Data Layer
                          +--------------------------+      +------------------+
                          |      sps30_ble.py        |      |  Wear OS         |
                          |  BLE peripheral (NUS)    |<====>|  complication+tile|
                          +--------------------------+ BLE  +------------------+
```

The layering that matters:

- The **sensor** speaks only SHDLC and has no notion of anything above it.
- The **controller** is the sole owner of the serial port and the sole author of
  the record. It is the only stateful tier.
- **Clients are pure subscribers.** They may request state changes; they never
  hold anything the controller does not.
- The **watch is a client of the phone**, not of the controller.
- **`sps30_ble.py` is itself a client of the daemon** (`127.0.0.1:8730`) that
  re-presents the same JSONL over a Nordic UART Service. The daemon does not know
  BLE exists.

## 2. Hard requirements on the controller

These four are load-bearing. Everything else can be renegotiated.

1. **Acquisition is never blocked by clients.**
   Reading the sensor and writing the CSV happen on a dedicated thread. Client
   connect, disconnect, error, slowness, or a client count of zero must not pause
   or corrupt acquisition.
2. **Single owner of the serial port.**
   Only the acquisition thread touches it. Control commands are enqueued and run
   by that thread between reads, never by opening the port concurrently.
3. **Auto-detect and auto-recover.**
   Poll for the sensor when absent; start measuring when it appears; return to
   polling if it stops answering; resume when it returns. Tolerate 5 consecutive
   read failures before declaring loss, and **continue the same session file** on
   recovery.
4. **The CSV is the record.**
   Flush every row, fsync every `--fsync-every` rows (default 30). A process crash
   loses at most the row being written; a power loss at most `fsync-every` rows.

## 3. The record on disk

- Filename `SPS30-YYYYmmdd_HHMMSS.csv`, from `wire.csv_filename(start)`, local
  zone. Collisions are disambiguated rather than appended to.
- Columns are `wire.CSV_COLUMNS`:

  ```
  index, timestamp, pm1_0, pm2_5, pm4_0, pm10,
  nc0_5, nc1_0, nc2_5, nc4_0, nc10, typical_size, event
  ```

- `index` is zero-padded to width 7. `timestamp` is ISO 8601 with offset.
- **`event`** is blank for a normal reading, and `startup` / `resume` / `restart` /
  `clean` for one taken while the measurement was disturbed. Clients exclude
  flagged rows from plots by default.
  Older CSVs without the column read back as unflagged.

## 4. The protocol

JSONL, one object per line, framed by `wire.iter_messages`.
The **identical byte stream** runs over TCP (port 8730) and over BLE (NUS write +
notify). Adding BLE required no protocol change at all, which is the return on
having chosen a text protocol.

**Controller to client:** `hello`, `reading`, `state`, `profile`, `power`,
`control`, `clients`, `file_list`, `file_data`, `backup_meta`, `backup_chunk`,
`raw_response`, `error`.

**Client to controller:** `pause`, `resume`, `clean`, `restart`, `raw`,
`list_files`, `get_file {name, since_index?}`, `set_profile`, `shutdown`,
`identify`, `claim`, `release`, `backup`.

Two rules that are easy to break:

- **Mutating commands require the control claim** (`wire.MUTATING_COMMANDS`), which
  expires after 120 s so a departing phone cannot lock everyone out.
- **Every command constant must be routed by the dispatcher**, which has its own
  allowlist separate from the state machine. A test asserts this, because a
  command once shipped defined, documented, handled and unreachable.

## 5. Controller internals (`sps30_daemon.py`)

Threads:

1. **Acquisition and FSM**, plus CSV writing and command execution.
2. **TCP accept loop.**
3. **One writer per client**, fed by a bounded queue.

Per-client queues are bounded at 1000 messages and messages are classed:

- **lossy** (readings, state): drop oldest under pressure, so a slow phone cannot
  stall acquisition or other clients;
- **lossless** (file content, archive chunks): apply backpressure, because a
  dropped chunk silently corrupts an archive the user believes they have.

The acquisition FSM has two shapes behind one interface:

- **continuous**: read every `interval` seconds.
- **duty cycle**: wake, warm up, average, record one point, sleep. Guarded by a
  **generation counter** so a profile change mid-cycle abandons the in-flight
  cycle instead of stranding it.

The controller also accrues **measurement seconds itself** and triggers a fan
clean weekly, because the sensor's own auto-clean counter resets whenever it
sleeps, and under duty cycling it is asleep ~93% of the time.

## 6. Client internals (`apk/`)

Layered `wire` -> `net` -> `data` -> `ui`:

| Layer | Contents |
|---|---|
| `wire/` | `Wire`, `Messages`, `Commands` - the mirror of `wire.py` |
| `net/` | `Transport` and its two implementations, `DaemonClient`, `Discovery`, `JsonlReassembler`, `TransportAvailability`, `WifiNetworkBinder` |
| `data/` | `TimeSeriesHistory`, `ChartView`, `Alerts`, `AppConfig`, `SessionBackup`, `Durations` |
| `ui/` | Compose screens, `PmChart`, `OptionsSheet`, and `MonitorSession` |
| `service/` | Foreground service and notifications |
| `wear/` | The phone half of the watch relay |

**`MonitorSession` is the spine.** It is a process-lifetime singleton so the
stream survives Activity recreation and can be shared with the foreground
service, and **all non-thread-safe model mutation is confined to a single-threaded
dispatcher**, so the message collector, the 1 Hz recompute ticker and UI control
calls cannot race.

**`Transport` is the seam that made BLE cheap.** Both `TcpTransport` and
`BleTransport` emit identical `ServerMessage`s, so everything above them - message
folding, history, chart - is transport-agnostic. `JsonlReassembler` is shared by
both and is essential for BLE, where notifications are MTU-capped: one line spans
several notifications, and one notification can carry parts of two lines.

**`TimeSeriesHistory` is columnar**: parallel primitive arrays with interned csv
and event ids, a head offset making retention-trimming O(1), and decimation
performed **during** extraction so allocation is independent of window length.
The y range is still computed over the full window, so autoscale sees spikes that
decimation stepped over.

De-duplication is on `(csv, index)`, which is what makes backfill idempotent and
lets overlapping live and backfilled rows merge safely.

## 7. What the controller port changes

The sensor tier and the protocol are unchanged by moving off the Pi.
What changes is the controller's internals: queue sizes (1000 messages is ~200 KB
per client, unaffordable on a 512 KB part), connection limits (lwIP sockets,
NimBLE's 3 connections), and above all **storage** - a constrained controller
becomes a ring buffer and the phone becomes the store of record, which is a
different architecture from the one in section 3, not a smaller one.

See the ESP32 companion repositories for the microcontroller port's architecture and storage format.

## 8. Testing

- **252 Python tests**, no hardware required, plus a hardware-in-the-loop set run
  on the Pi (`./run_tests.sh hardware`).
- **121 JVM tests** for the app.
- Both suites are expected green before any commit. The chart carries a
  performance regression test over a full retention window; keep it.
