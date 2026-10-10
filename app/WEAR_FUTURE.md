# Galaxy Watch 7 - groundwork & plan

A Wear OS client is a **separate future module**, not built here, but the phone
app was structured so most of it is reusable. This records what is ready and the
recommended path, so a watch client is a small, well-scoped project when wanted.

## What is already reusable (no rewrite)

The core is pure Kotlin with **no Android/UI dependencies**, so a Wear module can
depend on it directly:

- **`wire/`** - the protocol (`Wire`, `Messages`, `Commands`). Unchanged.
- **`data/TimeSeriesHistory`** and **`data/ChartView`** - the store and the
  moving-window/decimation logic, for a tiny on-watch sparkline.
- **`net/Discovery`** and **`net/DaemonClient`** - LAN discovery + the coroutine
  client. These work on Wear OS too (it is Android), assuming the watch has
  Wi-Fi and is on the same LAN.

A Wear module would reuse all of the above and add only a small Wear UI.

## Recommended shape

The Galaxy Watch 7 (Wear OS 5) has Wi-Fi, so the simplest design is
**direct-to-daemon**, mirroring the phone app:

1. **A Tile** (glanceable, no app open) showing the current **PM2.5** big, a
   1-hour **sparkline** (from `TimeSeriesHistory` + a stripped-down `ChartView`),
   and a color band for air-quality level. Tiles refresh periodically; the tile
   service does a short-lived `DaemonClient` connect (or reads the last value
   cached by a lightweight service) rather than holding a socket open.
2. **A complication** (`SHORT_TEXT` / `RANGED_VALUE`) for watch faces: current
   PM2.5 as a number or a 0-100 ring.
3. Optional **full app** on the watch: a minimal Compose-for-Wear screen with the
   readout + sparkline + pause/resume, reusing `DaemonClient` directly.

### Connectivity choice
- **Direct (recommended):** watch connects to the daemon over Wi-Fi like the
  phone. Simplest; no phone dependency. Works when the watch is on home Wi-Fi.
- **Phone-relayed (fallback):** if the watch is off Wi-Fi (LTE/BT only), relay
  the latest reading from the phone via the **Wearable Data Layer**
  (`MessageClient`/`DataClient`). More moving parts; only needed for
  away-from-home glances. Defer unless required.

## New work required (the actual watch project)
- A `:wear` Gradle module (`com.google.android.gms:play-services-wearable`,
  `androidx.wear.tiles`, `androidx.wear.compose`, `androidx.wear.watchface` for a
  complication), `minSdk 30`, Wear OS target.
- A Tile service + a small `ChartView`-backed sparkline renderer (Canvas, like
  `PmChart` but tiny and non-interactive).
- Battery-conscious refresh (tiles/complications update on a schedule; avoid a
  persistent socket - connect briefly, read the latest, disconnect).
- Watch-specific discovery caching (the sweep is heavier on a watch; cache the
  last-good host aggressively).

## Out of scope note
This is genuinely its own effort (Wear build/emulator/on-watch testing, tile +
complication lifecycles). It is **not** required for the phone/tablet app and is
listed here only so the protocol/data reuse is understood and the path is clear.
