# Android direct-connect - design & contract

Goal: let an Android device on the LAN connect **directly** to the Pi daemon
(tcp 8730, JSONL over TCP) and run the existing Textual dashboard client, with a
one-command launch after a one-time setup. Built to stay compatible with a
future full Android app (.apk) without doing APK work now.

Branch/worktree: `android` at `../SENSIRION-android` (based on `dashboard`).
All work lives under `android/` plus a small launcher module; the existing
client (`sps30_gui.py`, `chartview.py`, `wire.py`) is reused unchanged where
possible.

## Key facts (verified)
- The client runs with ONLY `textual textual-plotext plotext tzdata` - no
  pyserial, no serial hardware (the `sps30` import is already guarded). So
  Termux installs just those four wheels (all pure-Python / Termux-friendly).
  `tzdata` is required because `wire.py` does `ZoneInfo("America/New_York")` at
  import and Android/Termux ships no system IANA zoneinfo DB (real-device
  finding: without it the TUI crashes on launch with ZoneInfoNotFoundError).
- The client already accepts `--host` / `--tcp-port`; the daemon needs NO change.
- The Pi IP is DHCP and changes. Prefer the mDNS name `pi.local` (Raspberry Pi
  OS runs avahi); fall back to a remembered/last-good IP.
- Target devices: Samsung Tab S7+, Galaxy S24 Ultra (primary). Galaxy Watch 7 is
  aspirational - a TUI in Termux is impractical on a watch; that is future-app
  territory. Dev/test devices available: Galaxy Note8, Note5.

## Deliverables
### Owned by Agent B - `android/sps30_launch.py` (the brains; pure + testable)
A small Python entrypoint that resolves the target, preflights the connection,
remembers what worked, then launches the Textual app. Standard library only
(plus importing `sps30_gui`); no new deps.

Public, unit-testable surface (keep I/O-free logic in pure functions):
- `load_config(path) -> dict` / `save_config(path, cfg) -> None`: read/write a
  simple `KEY=VALUE` file (host, port, last_good_host). Tolerate missing file.
- `resolve_candidates(cli_host, env, cfg) -> list[str]`: ordered, de-duplicated
  host candidates. Priority: explicit CLI host > `$SPS30_HOST` > cfg `host` >
  cfg `last_good_host` > `pi.local`. Never crash on missing pieces.
- `preflight(host, port, timeout=3.0) -> bool`: TCP connect test (real socket;
  keep it a thin wrapper so tests can monkeypatch).
- `pick_host(candidates, port, connect=preflight) -> str | None`: first
  candidate that connects (`connect` injectable for tests).
- CLI (`main(argv)`), stdlib `argparse`:
  - `sps30` (no args): resolve -> preflight -> on success save `last_good_host`
    and launch `sps30_gui.main(["--host", H, "--tcp-port", P, ...])`; on failure
    print a friendly, actionable message (what it tried, how to set the host)
    and exit non-zero.
  - `--host H` / `--port P`: override for this run.
  - `--set-host H`: persist host to config and exit.
  - `--doctor`: print resolved candidates + which connect, config path/contents,
    and the client dep check; exit 0. No TUI.
  - `--no-launch`: do everything except starting the TUI (for tests/CI).
- Config path: `$SPS30_CONFIG` else `${XDG_CONFIG_HOME:-$HOME/.config}/sps30/config`.
- Defaults: port 8730, host `pi.local`.
Tests in `android/tests/test_launch.py` (pytest): config round-trip, candidate
ordering/priority + de-dup, `pick_host` with an injected connect fn, and
`--doctor`/`--set-host`/`--no-launch` via `main` with monkeypatched preflight
and a stubbed `sps30_gui.main` (never open a real socket or TUI in tests).

### Owned by Agent A - packaging + docs under `android/`
- `android/sps30` (thin bash wrapper, installed onto PATH): execs
  `python "$INSTALL_DIR/sps30_launch.py" "$@"` using the client's Python. Keep it
  a few lines; all logic is in sps30_launch.py.
- `android/setup.sh` (idempotent; works in Termux AND generic Linux for on-box
  testing - detect Termux via `$PREFIX`/`command -v pkg`):
  1. Ensure Python (Termux: `pkg install -y python`; generic: assume present).
  2. Create an isolated env for the client deps (Termux: `pip install --user` or
     a venv; generic-Linux test mode: a `.venv`), install
     `textual textual-plotext plotext tzdata` (NOT pyserial).
  3. Install the client into `$INSTALL_DIR` (default Termux `$PREFIX/opt/sps30`
     or `$HOME/sps30`; generic: a chosen dir): copy `sps30_gui.py`,
     `chartview.py`, `wire.py` (from the repo root, i.e. `$SCRIPT_DIR/..`) and
     `sps30_launch.py`.
  4. Install the `sps30` launcher onto PATH (Termux `$PREFIX/bin`, generic
     `$HOME/.local/bin`), `chmod +x`, pointing at the installed launcher + env.
  5. Write a default config (host `pi.local`, port 8730) if none exists.
  6. Print next steps ("run: sps30", "set host: sps30 --set-host <ip>").
  Support `SPS30_INSTALL_DIR`, `SPS30_HOST` env overrides; a `--uninstall` flag;
  and a `--dry-run`/testable path so it can be exercised on this box without a
  real Termux.
- `android/sps30.config.example`: documented template.
- `android/README.md`: install Termux from **F-Droid** (not Play Store), get the
  files onto the device (git over LAN / `scp` from the Pi or this box /
  `termux-setup-storage`), run `bash setup.sh`, then daily use = open Termux +
  `sps30`. Cover: `pi.local` vs IP, `sps30 --doctor`, `--set-host`, and the
  **Termux:Widget** home-screen shortcut (tap-to-launch, the closest thing to an
  app without an APK) and Termux:Boot notes. Note the Watch is out of scope.

### Owned by the orchestrator (me)
- `android/ANDROID_APP_FUTURE.md`: the path to a real APK (ranked by effort:
  Termux:Widget now -> textual-serve/textual-web in a WebView -> native rewrite
  reusing chartview/wire), and why we do Termux first. Integration, on-box
  testing, and commits.

## Code-review triage (applied)
- ACCEPTED: `pi.local`/mDNS is NOT reliable on Android/Termux (Bionic getaddrinfo
  does not resolve `.local`). Added stdlib LAN subnet discovery (concurrent sweep
  for a host that returns a daemon `HELLO`) as the IP-agnostic, DHCP-proof
  fallback; docs/config no longer present `pi.local` as the reliable default.
  Verified end-to-end: `discover(8730, base="192.168.1.")` found the live daemon.
- ACCEPTED: config fidelity - `last_good_host` is now written to a SEPARATE
  `state` file so a normal launch never rewrites the user's commented config;
  `--set-host` edits the config comment-preservingly; the config `port` is now
  honored (precedence: --port > $SPS30_PORT > config > 8730); `--doctor` reuses
  its preflight loop results instead of double-probing.
- ACCEPTED (docs): the daemon is unauthenticated on `0.0.0.0:8730`; the README
  has a Security/trust section (trusted LAN only; tunnel for untrusted access;
  PSK handshake noted as a future daemon enhancement). No daemon change made.
- ADDED for portability/testability: `setup.sh` prefers `uv` for the venv when
  present (falls back to `python -m venv` + pip, which Termux has natively), so
  it works on minimal Pythons lacking `ensurepip`.

## Do NOT (now)
- No APK/buildozer/python-for-android build yet (substantially more work than a
  Termux launcher; deferred by design and documented).
- No changes to `sps30_daemon.py` or the wire protocol.
- No new runtime dependency beyond the three client wheels.
