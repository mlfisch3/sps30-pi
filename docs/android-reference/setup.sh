#!/usr/bin/env bash
#
# setup.sh - one-time installer for the SPS30 Textual dashboard client.
#
# Packages the existing Textual client so an Android device (Termux) - or a
# generic Linux box, for testing - can connect directly to the Pi daemon
# (tcp 8730) and launch with a single `sps30` command.
#
# Idempotent and safe to re-run. Works in Termux and in generic Linux; Termux
# is auto-detected via $PREFIX and `pkg`.
#
# Usage:
#   bash setup.sh              install (or update) the client
#   bash setup.sh --dry-run    print what would happen; touch nothing
#   bash setup.sh --uninstall  remove launcher + install dir (keeps config)
#   bash setup.sh --uninstall --purge   also remove the config file
#
# Env overrides:
#   SPS30_INSTALL_DIR   where the client is installed
#   SPS30_HOST          host written into the default config (else pi.local)
#
set -eu

# --------------------------------------------------------------------------
# Locate ourselves and the repo root (repo root is one level above android/).
# --------------------------------------------------------------------------
SCRIPT_DIR="$(cd -P "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -P "$SCRIPT_DIR/.." && pwd)"

# --------------------------------------------------------------------------
# Parse args.
# --------------------------------------------------------------------------
DRY_RUN=0
DO_UNINSTALL=0
PURGE=0
for arg in "$@"; do
  case "$arg" in
    --dry-run)   DRY_RUN=1 ;;
    --uninstall) DO_UNINSTALL=1 ;;
    --purge)     PURGE=1 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      printf 'setup.sh: unknown argument: %s\n' "$arg" >&2
      printf 'try: bash setup.sh --help\n' >&2
      exit 2
      ;;
  esac
done

# --------------------------------------------------------------------------
# Detect environment.
# --------------------------------------------------------------------------
IS_TERMUX=0
if [ -n "${PREFIX:-}" ] && command -v pkg >/dev/null 2>&1 && \
   printf '%s' "${PREFIX:-}" | grep -q 'com.termux'; then
  IS_TERMUX=1
fi

# --------------------------------------------------------------------------
# Resolve target paths.
# --------------------------------------------------------------------------
if [ "$IS_TERMUX" -eq 1 ]; then
  DEFAULT_INSTALL_DIR="$PREFIX/opt/sps30"
  BIN_DIR="$PREFIX/bin"
else
  DEFAULT_INSTALL_DIR="$HOME/sps30"
  BIN_DIR="$HOME/.local/bin"
fi

INSTALL_DIR="${SPS30_INSTALL_DIR:-$DEFAULT_INSTALL_DIR}"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/sps30"
CONFIG_FILE="$CONFIG_DIR/config"
LAUNCHER="$BIN_DIR/sps30"
VENV_DIR="$INSTALL_DIR/.venv"
VENV_PY="$VENV_DIR/bin/python"
HOST="${SPS30_HOST:-pi.local}"
CLIENT_FILES="sps30_gui.py chartview.py wire.py"
# tzdata: pure-Python IANA time-zone DB. `wire.py` uses ZoneInfo("America/New_York")
# at import; Android/Termux ships no system zoneinfo, so without this the client
# crashes on launch (ZoneInfoNotFoundError). Harmless where a system DB exists.
DEPS="textual textual-plotext plotext tzdata"

# --------------------------------------------------------------------------
# Small helpers.
# --------------------------------------------------------------------------
say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }

# run: execute a command, or in dry-run mode just print it.
run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '    [dry-run] %s\n' "$*"
  else
    "$@"
  fi
}

if [ "$IS_TERMUX" -eq 1 ]; then
  say "Environment: Termux (Android)"
else
  say "Environment: generic Linux (test/dev mode)"
fi
say "Install dir: $INSTALL_DIR"
say "Launcher:    $LAUNCHER"
say "Config:      $CONFIG_FILE"
[ "$DRY_RUN" -eq 1 ] && say "Mode:        DRY RUN (no changes will be made)"

# ==========================================================================
# Uninstall path.
# ==========================================================================
if [ "$DO_UNINSTALL" -eq 1 ]; then
  step "Uninstalling"
  if [ -e "$LAUNCHER" ]; then
    run rm -f "$LAUNCHER"
    say "  removed launcher: $LAUNCHER"
  else
    say "  launcher not present: $LAUNCHER"
  fi
  if [ -d "$INSTALL_DIR" ]; then
    run rm -rf "$INSTALL_DIR"
    say "  removed install dir: $INSTALL_DIR"
  else
    say "  install dir not present: $INSTALL_DIR"
  fi
  if [ "$PURGE" -eq 1 ]; then
    if [ -e "$CONFIG_FILE" ]; then
      run rm -f "$CONFIG_FILE"
      run rmdir "$CONFIG_DIR" 2>/dev/null || true
      say "  removed config: $CONFIG_FILE"
    else
      say "  config not present: $CONFIG_FILE"
    fi
  else
    say "  kept config: $CONFIG_FILE (use --purge to remove)"
  fi
  step "Uninstall complete."
  exit 0
fi

# ==========================================================================
# Install path.
# ==========================================================================

# --- 1. Ensure Python -----------------------------------------------------
step "Ensuring Python is available"
if [ "$IS_TERMUX" -eq 1 ]; then
  run pkg install -y python
fi
BOOT_PY=""
if command -v python3 >/dev/null 2>&1; then
  BOOT_PY="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
  BOOT_PY="$(command -v python)"
fi
if [ -z "$BOOT_PY" ] && [ "$DRY_RUN" -eq 0 ]; then
  say "ERROR: no python3/python found on PATH." >&2
  if [ "$IS_TERMUX" -eq 1 ]; then
    say "       try: pkg install python" >&2
  else
    say "       install Python 3 and re-run." >&2
  fi
  exit 1
fi
[ -z "$BOOT_PY" ] && BOOT_PY="python3"   # dry-run placeholder
say "  using: $BOOT_PY"

# --- 2. Create isolated env + install deps --------------------------------
# Prefer uv when present: it creates venvs without needing the stdlib `ensurepip`
# (missing on some minimal Python builds) and installs fast. Otherwise use the
# stock `python -m venv` + pip, which works out of the box on Termux.
step "Creating isolated environment and installing deps ($DEPS)"
run mkdir -p "$INSTALL_DIR"
HAVE_UV=0
command -v uv >/dev/null 2>&1 && HAVE_UV=1
if [ -x "$VENV_PY" ]; then
  say "  venv already exists: $VENV_DIR"
elif [ "$HAVE_UV" -eq 1 ]; then
  say "  creating venv with uv"
  run uv venv "$VENV_DIR"
else
  say "  creating venv with python -m venv"
  run "$BOOT_PY" -m venv "$VENV_DIR"
fi
# In dry-run the venv python does not exist yet; fall back to boot python so the
# printed command is meaningful.
# shellcheck disable=SC2086
if [ "$HAVE_UV" -eq 1 ]; then
  TARGET_PY="$VENV_PY"
  [ -x "$VENV_PY" ] || TARGET_PY="$BOOT_PY"
  run uv pip install --python "$TARGET_PY" --upgrade $DEPS
else
  PIP_PY="$VENV_PY"
  [ -x "$VENV_PY" ] || PIP_PY="$BOOT_PY"
  run "$PIP_PY" -m pip install --upgrade pip
  run "$PIP_PY" -m pip install --upgrade $DEPS
fi

# --- 3. Copy client files into the install dir ----------------------------
step "Installing client into $INSTALL_DIR"
for f in $CLIENT_FILES; do
  if [ -f "$REPO_ROOT/$f" ]; then
    run cp "$REPO_ROOT/$f" "$INSTALL_DIR/$f"
    say "  copied $f"
  else
    say "  WARNING: missing source file: $REPO_ROOT/$f" >&2
  fi
done
# sps30_launch.py lives under android/ (created by the other agent).
if [ -f "$SCRIPT_DIR/sps30_launch.py" ]; then
  run cp "$SCRIPT_DIR/sps30_launch.py" "$INSTALL_DIR/sps30_launch.py"
  say "  copied sps30_launch.py"
else
  say "  WARNING: $SCRIPT_DIR/sps30_launch.py not found." >&2
  say "           The launcher will fail until it is installed there." >&2
fi

# --- 4. Install the sps30 launcher onto PATH ------------------------------
step "Installing launcher onto PATH: $LAUNCHER"
run mkdir -p "$BIN_DIR"
if [ "$DRY_RUN" -eq 1 ]; then
  printf '    [dry-run] generate %s from template %s\n' "$LAUNCHER" "$SCRIPT_DIR/sps30"
  printf '    [dry-run]   @INSTALL_DIR@ -> %s\n' "$INSTALL_DIR"
  printf '    [dry-run]   @PYTHON@      -> %s\n' "$VENV_PY"
else
  sed -e "s|@INSTALL_DIR@|$INSTALL_DIR|g" \
      -e "s|@PYTHON@|$VENV_PY|g" \
      "$SCRIPT_DIR/sps30" > "$LAUNCHER"
  chmod +x "$LAUNCHER"
fi
say "  launcher points at: $VENV_PY $INSTALL_DIR/sps30_launch.py"

# --- 5. Write a default config if none exists -----------------------------
step "Writing default config (if absent)"
if [ -e "$CONFIG_FILE" ]; then
  say "  config already exists, leaving it untouched: $CONFIG_FILE"
else
  if [ "$DRY_RUN" -eq 1 ]; then
    if [ -n "${SPS30_HOST:-}" ]; then
      printf '    [dry-run] write %s with host=%s port=8730\n' "$CONFIG_FILE" "$HOST"
    else
      printf '    [dry-run] write %s with port=8730 (host unset -> auto-discovery)\n' "$CONFIG_FILE"
    fi
  else
    mkdir -p "$CONFIG_DIR"
    {
      printf '# SPS30 dashboard client config (auto-generated by setup.sh)\n'
      printf '# Pin a host with:  sps30 --set-host <ip-or-name>\n'
      printf '# If no host connects, the launcher auto-discovers the daemon on\n'
      printf '# your subnet (DHCP-proof). pi.local is best-effort and often does\n'
      printf '# NOT resolve on Android - prefer a raw IP or auto-discovery.\n'
      if [ -n "${SPS30_HOST:-}" ]; then
        printf 'host=%s\n' "$HOST"
      else
        printf '#host=192.168.1.159\n'
      fi
      printf 'port=8730\n'
    } > "$CONFIG_FILE"
    if [ -n "${SPS30_HOST:-}" ]; then
      say "  wrote $CONFIG_FILE (host=$HOST, port=8730)"
    else
      say "  wrote $CONFIG_FILE (host unset -> auto-discovery, port=8730)"
    fi
  fi
fi

# --- 6. Next steps --------------------------------------------------------
step "Done."
say ""
say "Next steps:"
case ":${PATH}:" in
  *":$BIN_DIR:"*) : ;;
  *)
    say "  * $BIN_DIR is not on your PATH. Add this to your shell rc:"
    say "        export PATH=\"$BIN_DIR:\$PATH\""
    say "    (then restart the shell)"
    ;;
esac
say "  * Launch the dashboard:      sps30"
say "  * Pin your Pi's IP:          sps30 --set-host <ip>"
say "  * Find the Pi on the LAN:    sps30 --discover"
say "  * Diagnose connectivity:     sps30 --doctor"
say ""
say "The client connects to the daemon on tcp 8730. With no host pinned it"
say "auto-discovers the daemon on your subnet, so it keeps working across the"
say "Pi's DHCP changes. (mDNS 'pi.local' is tried too but often fails on Android.)"
