# Path to a real Android app (.apk) - design notes

We are shipping the **Termux** approach now (see `README.md`): the existing
Textual client runs as plain CPython in Termux and connects directly to the Pi
daemon over LAN TCP. A true `.apk` is deliberately deferred - it is substantially
more work than a Termux launcher - but the code is kept so that path stays open.
This doc records why, and the ranked options for later.

## Why the client is already app-ready
The client is layered so a different front-end can reuse almost everything:
- **Pure, UI-agnostic logic** (`chartview.py` - `TimeSeriesHistory`, `ChartView`,
  `parse_duration`; `wire.py` - the JSONL/TCP protocol + CSV schema;
  `DashboardState` in `sps30_gui.py`) is standard-library only and has no Textual
  or socket dependency in its core.
- The **Textual widgets are a thin adapter** over that logic.
- The **daemon** exposes a clean, versionable line protocol (`wire.py`) over TCP;
  any client in any language can speak it. No server change is needed for any of
  the options below.

Keep it that way: don't let a UI couple to Textual internals beyond the thin
adapter, keep `wire.py` stdlib-only and stable, and keep new runtime deps out of
the pure modules.

## Options, ranked by effort (least first)

### 1. Termux + Termux:Widget  (shipping now; no APK)
Home-screen shortcut that launches `sps30` in one tap - the closest thing to an
app without building one. Add Termux:Boot to auto-start on device boot if
desired. Zero new code beyond the launcher; works today on the Tab S7+ / S24
Ultra. This is the recommended interim experience.

### 2. textual-serve on the Pi + a thin WebView APK  (moderate; reuses the whole app)
`textual-serve` runs a Textual app and serves it to a browser over HTTP/WebSocket.
Run it **on the Pi** (the Pi already hosts the daemon and code) so the Pi serves
the same dashboard UI to any device's browser - Android then needs only a browser
(or a ~100-line WebView APK pointing at `http://pi.local:PORT`). Pros: reuses the
entire existing UI unchanged; no per-device Python install; trivially covers
every Samsung device. Cons: adds a small always-on web process on the Pi and a
new dep (`textual-serve`); the "client" now runs on the Pi, not the phone (fine
for a LAN appliance). This is the most attractive path to a real "app" and should
be evaluated before any native rewrite. A minimal WebView wrapper can later be a
PWA (installable, offline shell) with no store friction.

### 3. Native / hybrid rewrite reusing chartview + wire  (most effort; full APK)
A native Android UI (Kivy or BeeWare/Toga packaged with briefcase, or Flutter/
Kotlin with a small embedded Python or a re-implemented `wire` client) that reuses
the `chartview` computation and `wire` protocol. Full control over touch UX,
notifications, background service, and Play-Store distribution - but it is a real
app project (build toolchain, signing, device testing) and duplicates the UI.
Only worth it if the Termux/WebView experiences prove insufficient.

## Galaxy Watch 7
Neither Termux nor a terminal TUI is viable on Wear OS. A watch experience would
be a purpose-built Wear OS tile/complication showing current PM (a tiny client
that speaks `wire` and renders one number/spark) - its own small future project,
out of scope here. Noted so the protocol stability above is understood to also
serve a future watch client.

## Recommendation
Ship Termux (option 1) now. If/when a real "app" is wanted, prototype option 2
(textual-serve on the Pi + browser/WebView) first - it reuses the whole app for
little code - and reserve option 3 for when native-only capabilities are needed.
