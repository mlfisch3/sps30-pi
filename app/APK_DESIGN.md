# SPS30 native Android app (.apk) - design & plan

A real native Android app (Kotlin + Jetpack Compose) that connects **directly**
to the Pi daemon over LAN TCP (JSONL on port 8730) and renders the live SPS30
particulate-matter data with a proper touch UI. Chosen over the Termux/WebView
paths (see `../android/ANDROID_APP_FUTURE.md`) because the standing priority is
quality / maintainability / long-term fit (touch UX, responsive layout,
notifications, and a clean path to a Galaxy Watch 7 client) over least effort.

The **daemon and wire protocol are unchanged**. This app is one more client of
the same stable line protocol; `wire.py` remains the single source of truth and
is ported to Kotlin verbatim (constants + message shapes), with a round-trip
test that guards the port against drift.

## Non-negotiables carried over from the rest of the project
- No daemon change, no new Pi dependency. The app speaks the existing protocol.
- DHCP-proof, VPN-aware LAN discovery (the S24 has a VPN; discovery must sweep
  every local /24, not just the default-route one - the exact bug fixed in the
  Termux client, re-implemented here in Kotlin).
- Eastern-time axis labels match the daemon's CSV timestamps.
- Trusted-LAN security posture: the daemon is unauthenticated; document it, do
  not expose it. (Same stance as the Termux README.)

## Toolchain (bootstrapped in userspace - no root)
- JDK 17 at `~/tools/jdk17` (Temurin). AGP 8.x needs 17+; the box's system JDK
  is 11, so the build pins `org.gradle.java.home` to this JDK in
  `gradle.properties`.
- Android SDK at `~/android-sdk` (platform-tools, `platforms;android-35`,
  `build-tools;35.0.0`). `local.properties` (git-ignored) points Gradle at it.
- Gradle via the wrapper (reproducible; independent of the box's gradle).
- Build: `apk/gradlew :app:assembleDebug`. Install: `adb install` (needs USB
  debugging) or `scp` the APK to the device and tap it. No device is currently
  attached over adb; the S24 is reachable over SSH-into-Termux for file push.

## Versions (pinned)
- Gradle 8.9 (wrapper) · AGP 8.6.1 · Kotlin 2.0.21 (K2) · Compose Compiler via
  the Kotlin `plugin.compose` · Compose BOM · Material 3.
- `compileSdk`/`targetSdk` 35 · `minSdk` 24 (Android 7.0 - covers the S24, Tab
  S7+, and the older Note test devices).

## Module / package layout (`apk/app/src/main/kotlin/com/sensirion/sps30/`)
- `wire/` - **Kotlin port of `wire.py`**: `Wire.kt` (FIELDS, message-type and
  command constants, states, default port), `Messages.kt` (sealed `ServerMessage`
  hierarchy: Hello, Reading, StateMsg, RawResponse, FileList, FileData, Error +
  `parse(line): ServerMessage?`), and `Commands.kt` (encode `{"cmd":...}`
  frames). JSON via `kotlinx.serialization`. Framing = newline-delimited, same
  as `wire.iter_messages`.
- `net/` - `DaemonClient.kt`: coroutine TCP client (connect, framed read loop
  emitting `ServerMessage` on a `Flow`, send commands, auto-reconnect with
  backoff). `Discovery.kt`: VPN-aware subnet sweep (probe one address per RFC1918
  block + default route via a UDP `connect`, collect every local /24, concurrent
  verify that a host speaks `hello`) - a direct port of the fixed Termux
  `local_subnet_bases`/`discover`.
- `data/` - `TimeSeriesHistory.kt` (sorted-by-epoch store, dedup by
  `(csv,index)`, windowing, retention) and `ChartView.kt` (visible range, moving
  vs pinned, y-autoscale) - ports of `chartview.py`'s pure logic.
- `ui/` - Compose: `DashboardScreen.kt` (status banner, live readout, chart,
  control buttons), `PmChart.kt` (native `Canvas` line chart - no external chart
  lib), `theme/`. `DashboardViewModel.kt` bridges `DaemonClient` <-> UI state.
- `MainActivity.kt` - single-activity Compose host.
- Later: `service/` foreground service + notifications; a Wear module.

## Phased plan (each phase builds, installs, and is verified on the S24)
1. **Pipeline** (this commit): Gradle/Compose scaffold that builds a signed-debug
   APK and runs a "Hello SPS30" screen on-device. De-risks the whole toolchain.
2. **Protocol**: `wire/` Kotlin port + JVM unit tests (round-trip against the
   exact frames `wire.py` emits) + `Discovery` + `DaemonClient`; a debug screen
   that discovers the Pi, connects, and shows the raw live reading stream.
3. **Chart + readout**: `TimeSeriesHistory`/`ChartView` ports + `PmChart` Canvas
   rendering the four PM series live, with the current values read out big.
4. **Controls + windows**: pause/resume/clean/restart buttons; duration window,
   pin/moving, y/x autoscale + manual range (parity with the TUI features).
5. **Resilience + service**: auto-reconnect UX, optional foreground service +
   notification for background streaming, DHCP-change handling.
6. **Polish + Watch groundwork**: responsive phone/tablet layout, theming, and a
   Wear OS tile spike reusing `wire` + `TimeSeriesHistory`.

## Testing
- JVM unit tests (`app/src/test`) for `wire` (round-trip), `Discovery` (injected
  probe/verify, VPN multi-subnet case), and `TimeSeriesHistory`/`ChartView` -
  mirroring the Python suites so behavior provably matches.
- On-device: build -> install on the S24 -> connect to the live Pi -> confirm
  live readings render. Cross-checked against the Textual client.
