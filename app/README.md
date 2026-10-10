# SPS30 native Android app

A native **Kotlin / Jetpack Compose** app that connects directly over your LAN to
the SPS30 daemon on the Raspberry Pi (JSONL over TCP, port 8730) and shows the
live particulate-matter data with a real touch UI: color-coded readouts, a
native chart with pinch-zoom / drag-pan, the full window/pin/axis controls, the
daemon controls (pause / resume / clean / restart), and optional background
monitoring with a live notification.

The daemon and the wire protocol are unchanged - this is just another LAN client.

---

## Install

No Play Store build yet; install the APK directly.

**Via ADB (wireless or USB):**
```sh
adb install -r app-debug.apk
```

**By sideloading:** copy `app-debug.apk` to the device and tap it (allow
"install unknown apps" for your file manager once).

Build it yourself from source: see **Building** below.

---

## First run

1. Make sure the phone/tablet is on the **same Wi-Fi** as the Pi.
2. Open **SPS30**. It **auto-discovers** the daemon on your LAN (sweeping every
   local subnet, so it works even behind a VPN and across the Pi's DHCP changes)
   and starts streaming - no IP to type.
3. If you enable **Background** monitoring, Android asks for notification
   permission (grant it so the ongoing notification can show).

The banner turns green - `Connected <pi-ip> · measuring` - once it is live.

---

## Using it

- **Readouts:** the four cards show the current PM1.0 / PM2.5 / PM4.0 / PM10 in
  µg/m³, colored to match their chart lines (pale-yellow / green / red / cyan).
  In clean air PM2.5≈PM4.0≈PM10, so their lines render as one layered color band
  (drawn widest-first so all four stay visible). Readouts always show all four
  even if some series are hidden from the plot.
- **Chart gestures:** **pinch** horizontally to zoom the time span; **drag** left/
  right to pan. Panning a live view freezes it (the info line shows `frozen`).
- **Live / Pin / window chips:**
  - **Live** - follow the present; the window is the span you were viewing.
  - **window buttons** (customizable, default 1m/5m/30m/1h) - in Live set a clean
    `[now-W, now]` window; in **Pin** they re-pin the left edge at `now-W`.
  - **Pin** - a tap enters Pin (left edge fixed, right edge grows) from the
    current left edge; tapping again in Pin **cycles the N most-recent pins**
    (N configurable). **Long-press Pin**: in Live it *sets* a reference pin
    (without switching mode); in Pin it *snaps* to that reference pin.
- **Options menu** (the **⚙** in the info line): a slide-up sheet with everything
  else - **Pause/Resume**, **Clean**, **Restart**, **Clear plot**, **Y-autoscale**
  + **Set Y range**, **Background** monitoring, **Export CSV**, **Save PNG**, and
  the customizations below. Dismiss it to see the data.
- **Rotate to landscape** (or use a tablet) for a wider side-by-side layout.

### Customization (Options → Customize / Configuration)

- **Pin cycle length (N)** - how many recent pins the Pin button cycles through.
- **Series shown on plot** - hide any PM size from the chart (readouts still show
  it).
- **Window buttons** - type your own set, e.g. `10s, 30s, 1m, 10m, 1h, 5h, all`
  (`all` = the whole buffer).
- **Save / Load / Reset config** - name and save the current settings, reload a
  saved set, or reset to defaults. Settings persist across launches.

### Export

**Export CSV** writes every buffered point (Eastern timestamps + the four PM
values); **Save PNG** writes the current chart image. Both land in the app's
files dir - `Android/data/com.sensirion.sps30/files/exports/` - reachable over
USB or a file manager. A toast shows the full path.

---

## Background monitoring (keep streaming with the screen off)

Tap the **Background** chip. A foreground service keeps the stream running and
shows an ongoing notification with the live PM2.5 / PM10 and a **Stop** action.
Tapping the notification reopens the app.

For this to be **reliable 24/7**, Android's battery optimization must not doze
the app or drop Wi-Fi:

1. **Exempt the app from battery optimization.**
   Settings → Apps → **SPS30** → Battery → **Unrestricted**
   (on some Samsung builds: Settings → Battery → Background usage limits → make
   sure SPS30 is **not** in "Sleeping apps" / "Deep sleeping apps").
2. **Keep Wi-Fi on during sleep.** The app already holds a Wi-Fi lock while
   monitoring, but aggressive OEM power settings can still cut it - if the stream
   stalls with the screen off, check the battery/sleep settings above.

> Note: on Android 15, a `dataSync` foreground service has a daily background
> time budget; if it is exhausted the service stops itself and you can re-enable
> it from the app. For always-on wall-powered monitoring, exempting battery
> optimization (step 1) is what matters most.

---

## Requirements & permissions

- **Same LAN as the Pi.** The app talks to the daemon's own TCP protocol on port
  8730 - **not** SSH. `.local`/mDNS is not required (Android often can't resolve
  it); discovery uses a subnet sweep instead.
- **Trusted network.** The daemon is unauthenticated on the LAN - keep it on a
  trusted Wi-Fi and do not expose port 8730 to the internet.
- Permissions requested: `INTERNET` / `ACCESS_NETWORK_STATE` (connect),
  `FOREGROUND_SERVICE(+DATA_SYNC)` + `POST_NOTIFICATIONS` (background monitoring +
  its notification), `ACCESS_WIFI_STATE` + `WAKE_LOCK` (the Wi-Fi lock).

---

## Building

Toolchain (userspace, no root needed):
- JDK 17+, Android SDK with `platforms;android-35` + `build-tools;35.0.0`.
- Point Gradle at the SDK via `local.properties` (`sdk.dir=...`) and run with
  `JAVA_HOME` set to a JDK 17.

```sh
cd apk
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew :app:testDebugUnitTest
```

Architecture and the phased build history are in `APK_DESIGN.md`. The Kotlin
`wire` package is a faithful port of the repo's `wire.py` (guarded by round-trip
tests), and `data/TimeSeriesHistory`/`ChartView` port `chartview.py`, so the app
provably agrees with the Textual client and the daemon.
