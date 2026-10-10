# SPS30 dashboard on Android (Termux)

Run the existing SPS30 Textual dashboard on an Android device. The phone/tablet
connects **directly over your LAN** to the Pi daemon (tcp 8730, JSONL) and
launches with a single command after a one-time setup. No APK, no serial
hardware on the phone - just a terminal UI.

- Primary devices: **Samsung Tab S7+** and **Galaxy S24 Ultra**.
- The **Galaxy Watch 7 is out of scope**: a Textual TUI in Termux is impractical
  on a watch screen. That is future-app territory, not this setup.

The daemon on the Pi is unchanged. Only four pure-Python wheels are installed
on the device: `textual`, `textual-plotext`, `plotext`, and `tzdata` (no
`pyserial`). `tzdata` supplies the IANA time-zone database that Android/Termux
does not ship, which the client needs for its local-time axis.

---

## 1. Install Termux from F-Droid (not the Play Store)

Install Termux from **F-Droid**: https://f-droid.org/packages/com.termux/
(or the APK from https://github.com/termux/termux-app/releases).

Do **not** use the Google Play build. The Play Store version of Termux is
frozen/deprecated: its packages are stale, `pkg` updates are broken, and the
add-on apps (Termux:Widget, Termux:Boot) will not talk to it. Install Termux and
any add-ons from the **same source** (all from F-Droid) or they refuse to
cooperate due to signature mismatches.

After installing, open Termux once and update the base system:

```sh
pkg update && pkg upgrade
```

---

## 2. Get the files onto the device

You need this `android/` directory plus the client files from the repo root
(`sps30_gui.py`, `chartview.py`, `wire.py`). `setup.sh` reads them from one level
above itself, so keep the repo layout intact. Pick whichever transfer method is
easiest:

**Option A - clone over the LAN (recommended).**
```sh
pkg install -y git openssh
git clone <your-repo-url> sensirion
cd sensirion/android
```

**Option B - scp from the Pi or your dev box.**
```sh
pkg install -y openssh
# from the OTHER machine, push the repo to the phone (find the phone IP with
# `ifconfig`/`ip a` in Termux, sshd started with `sshd`, default port 8022):
#   scp -P 8022 -r /path/to/SENSIRION user@<phone-ip>:~/sensirion
cd ~/sensirion/android
```

**Option C - shared storage + a file manager.**
```sh
termux-setup-storage        # grant storage access when prompted
# copy the repo into e.g. Download/ with any file manager, then:
cp -r ~/storage/downloads/SENSIRION ~/sensirion
cd ~/sensirion/android
```

---

## 3. Run the setup (one time)

```sh
bash setup.sh
```

This is idempotent - safe to re-run any time (e.g. after pulling client
updates). It:

1. Ensures Python is installed (`pkg install -y python`).
2. Creates an isolated virtualenv and installs `textual textual-plotext plotext tzdata`.
3. Copies the client into `$PREFIX/opt/sps30` (override with `SPS30_INSTALL_DIR`).
4. Installs an `sps30` launcher onto your PATH (`$PREFIX/bin`).
5. Writes a default config (`port=8730`, host left unset so the launcher
   auto-discovers the Pi) if none exists.

Useful variations:

```sh
SPS30_HOST=192.168.1.159 bash setup.sh   # seed the config with a raw IP
bash setup.sh --dry-run                   # show what it would do, change nothing
bash setup.sh --uninstall                 # remove launcher + install dir (keeps config)
bash setup.sh --uninstall --purge         # also remove the config file
```

---

## 4. Daily use

Open Termux and run:

```sh
sps30
```

That resolves the Pi, checks the connection, and launches the dashboard.

### Finding the Pi (DHCP-proof, no config needed)

The Pi's IP is handed out by DHCP and **changes**, so the launcher does not rely
on a fixed address. When no configured host connects, it **automatically sweeps
your local subnet** for the daemon (verifying each candidate actually speaks the
SPS30 protocol) and connects to it - then remembers that address for next time.
So a plain `sps30` keeps working across the Pi's IP changes with zero setup.

```sh
sps30 --discover                 # just print the Pi's current IP on your LAN
sps30 --set-host 192.168.1.159   # pin a specific IP (skips the sweep, fastest)
sps30 --host 192.168.1.159       # override for a single run
```

> **`pi.local` note:** the mDNS name `pi.local` is tried as a convenience, but
> **Android/Termux usually cannot resolve `.local` names** (Android's mDNS is not
> exposed to the C library the launcher uses). Do not rely on `pi.local` on the
> phone - use auto-discovery (default) or pin a raw IP with `--set-host`.

The last host that connected is remembered in a small state file (separate from
your config), so repeat launches connect instantly without re-sweeping.

### Diagnose problems

```sh
sps30 --doctor
```

`--doctor` prints the host candidates it will try, which ones actually connect,
the config file path and contents, and the client dependency check - all without
launching the TUI. Start here if `sps30` cannot reach the Pi.

The config file lives at `~/.config/sps30/config`. See `sps30.config.example`
in this directory for a documented template.

---

## 5. Persistence and adding more devices

### Does the `sps30` install survive?

**Yes - the installation persists.** `setup.sh` installs into
`$PREFIX/opt/sps30` plus the `$PREFIX/bin/sps30` launcher, which live in
Termux's private storage. They survive closing Termux, rebooting the phone,
and the app going to the background. You do **not** reinstall - just open Termux
and run `sps30` again. (To update the client later, re-run `bash setup.sh` from
an updated copy; it is idempotent.)

**The running dashboard does not persist.** The TUI is a foreground process:
closing Termux, or Android killing the backgrounded session, ends it. Relaunch
with `sps30`. To keep it alive while you switch apps, acquire a **wakelock**
(Termux notification -> "Acquire wakelock").

### Can the dashboard launch without Termux?

**Not at this stage.** The dashboard is a terminal (TUI) program; it needs a
terminal host with Python, and Termux is that host. There is no standalone
Android app yet.

- **Closest to an app icon today:** the **Termux:Widget** home-screen shortcut
  (section 6) - one tap, no manually opening Termux, but still Termux underneath.
- **A true tap-the-icon app** (no Termux at all) is the `.apk` path, deliberately
  deferred. See `ANDROID_APP_FUTURE.md`.

### Set it up on another Android device

Same one-time flow as the first device (~5 minutes), because each device is an
independent LAN client of the Pi daemon:

1. Install **Termux** (and optionally **Termux:Widget**) from **F-Droid**.
2. Get the files on: `pkg install -y git openssh` then
   `git clone <repo-url> sensirion` (or `scp` the repo over - see section 2).
3. `cd sensirion/android && bash setup.sh`
4. `sps30`

No per-device IP setup is needed: each device **auto-discovers** the Pi on its
own, and discovery sweeps *every* local subnet, so it works even if that device
has a VPN active (the S24 does). Nothing about one device's install affects
another.

> **The phone talks to the Pi over the daemon's own TCP protocol (port 8730),
> not SSH.** You do not SSH from the phone to the Pi to use the dashboard - the
> `sps30` client connects directly to the daemon. (SSH is only relevant for
> *administering* the Pi, or for pushing files onto a phone during setup.) The
> Pi daemon runs as a systemd service that **auto-starts on boot, auto-detects
> the sensor, begins streaming, and listens on `0.0.0.0:8730`** with
> `Restart=always`, so any device can connect at any time with no action on the
> Pi.

---

## 6. Make it feel like an app: Termux:Widget

The closest thing to a real app icon without building an APK is a **home-screen
shortcut** via **Termux:Widget**.

1. Install **Termux:Widget** from F-Droid (same source as Termux):
   https://f-droid.org/packages/com.termux.widget/
2. Create a shortcut script:
   ```sh
   mkdir -p ~/.shortcuts
   printf '#!/data/data/com.termux/files/usr/bin/bash\nsps30\n' > ~/.shortcuts/SPS30
   chmod +x ~/.shortcuts/SPS30
   ```
3. On the Android home screen, add the **Termux:Widget** widget, or long-press
   the home screen -> Widgets -> Termux -> pick the `SPS30` shortcut.

Tapping it opens Termux and launches the dashboard - a one-tap "app".

### Optional: launch on boot (Termux:Boot)

If you want the client (or an SSH server, etc.) to start when the device boots,
install **Termux:Boot** from F-Droid and drop a script in `~/.termux/boot/`. Most
people do **not** want the interactive TUI on boot; this is mainly useful for
background helpers. Keep it a one-liner if you use it at all.

---

## Security / trust

The daemon listens on `0.0.0.0:8730` with **no authentication**. Any device on
the same LAN can connect and:

- read the recorded PM data (`list files` / `get file`),
- pause / resume / clean / restart acquisition, and
- send **raw SHDLC frames** straight to the sensor.

That is fine for the intended use - a **trusted home Wi-Fi** - and this Android
client simply connects to it like any other LAN client. But treat the daemon as
an unauthenticated appliance:

- Do **not** expose port `8730` to untrusted networks or the public internet.
  Keep the Pi on a trusted LAN, and firewall `8730` if the network is shared.
- For remote/untrusted access, tunnel instead of exposing the port - e.g. SSH
  (`ssh -L 8730:localhost:8730 pi`) or a WireGuard VPN, then point the client at
  `localhost`.
- A lightweight pre-shared-key handshake in the daemon is a possible future
  enhancement (not implemented yet).

---

## Notes

- No pyserial and no serial hardware are needed on the device - the client is a
  pure network consumer of the daemon.
- Everything installed here is confined to the Termux prefix plus
  `~/.config/sps30`; `--uninstall` cleans it up.
- Building an actual `.apk` (Termux:Widget -> textual-web in a WebView -> native
  rewrite) is deliberately deferred; see `ANDROID_APP_FUTURE.md`.
