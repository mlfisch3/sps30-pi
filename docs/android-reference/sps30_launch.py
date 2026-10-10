#!/usr/bin/env python3
"""Launcher for the SPS30 Textual dashboard client (Android/Termux + generic).

Resolves the daemon host (CLI > $SPS30_HOST > config), preflights a TCP
connection, remembers what worked, then launches the existing Textual client
(`sps30_gui.main`). Standard library only; `sps30_gui` is imported lazily inside
the launch path so tests can stub it without pulling in the TUI.

Contract: see android/ANDROID_DESIGN.md ("Owned by Agent B").
"""
from __future__ import annotations

import argparse
import concurrent.futures
import os
import socket
import sys
from pathlib import Path

DEFAULT_PORT = 8730
DEFAULT_HOST = "pi.local"


# --------------------------------------------------------------------------- #
# Config path + persistence (simple KEY=VALUE lines)
# --------------------------------------------------------------------------- #
def config_path() -> Path:
    """Return the config file path.

    `$SPS30_CONFIG` if set, else `${XDG_CONFIG_HOME:-$HOME/.config}/sps30/config`.
    """
    explicit = os.environ.get("SPS30_CONFIG")
    if explicit:
        return Path(explicit)
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg else Path(os.path.expanduser("~")) / ".config"
    return base / "sps30" / "config"


def load_config(path) -> dict:
    """Read a simple KEY=VALUE config file into a dict.

    Missing file or unreadable file -> empty dict. Blank lines and lines
    starting with '#' are ignored. Values keep surrounding whitespace trimmed.
    """
    cfg: dict[str, str] = {}
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (FileNotFoundError, NotADirectoryError, IsADirectoryError, OSError):
        return cfg
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if not key:
            continue
        cfg[key] = value.strip()
    return cfg


def save_config(path, cfg) -> None:
    """Write a dict to a KEY=VALUE config file, creating parent dirs.

    Used for the program's own state file (see ``state_path``); the user-editable
    config is only ever touched through ``update_config_value`` so its comments
    survive.
    """
    p = Path(path)
    if p.parent and not p.parent.exists():
        p.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"{key}={value}" for key, value in cfg.items()]
    p.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def state_path() -> Path:
    """Path for program-written state (``last_good_host``), kept separate from the
    user-editable config so a normal launch never rewrites the config (which would
    destroy its comments)."""
    return config_path().parent / "state"


def update_config_value(path, key, value) -> None:
    """Set ``key=value`` in a KEY=VALUE file, preserving comments and other lines.

    Updates the first matching key line in place; appends if absent; creates the
    file (with just this line) if missing. This is how ``--set-host`` edits the
    user config without clobbering its template comments.
    """
    p = Path(path)
    if p.parent and not p.parent.exists():
        p.parent.mkdir(parents=True, exist_ok=True)
    try:
        lines = p.read_text(encoding="utf-8").splitlines()
    except (FileNotFoundError, NotADirectoryError, IsADirectoryError, OSError):
        lines = []
    new_line = f"{key}={value}"
    replaced = False
    for i, raw in enumerate(lines):
        stripped = raw.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            if stripped.split("=", 1)[0].strip() == key:
                lines[i] = new_line
                replaced = True
                break
    if not replaced:
        lines.append(new_line)
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")


def resolve_port(cli_port, env, cfg) -> int:
    """Effective port: explicit ``--port`` > ``$SPS30_PORT`` > config ``port`` >
    default. Invalid env/config values are ignored rather than fatal."""
    if cli_port is not None:
        return cli_port
    env = env or {}
    cfg = cfg or {}
    for source in (env.get("SPS30_PORT"), cfg.get("port")):
        if source:
            try:
                return int(str(source).strip())
            except (TypeError, ValueError):
                continue
    return DEFAULT_PORT


# --------------------------------------------------------------------------- #
# Host resolution
# --------------------------------------------------------------------------- #
def resolve_candidates(cli_host, env, cfg) -> list:
    """Ordered, de-duplicated host candidates.

    Priority: CLI host > env SPS30_HOST > cfg 'host' > cfg 'last_good_host' >
    'pi.local'. Blanks/None are dropped; order preserved; duplicates removed.
    """
    env = env or {}
    cfg = cfg or {}
    ordered = [
        cli_host,
        env.get("SPS30_HOST"),
        cfg.get("host"),
        cfg.get("last_good_host"),
        DEFAULT_HOST,
    ]
    result: list[str] = []
    seen: set[str] = set()
    for host in ordered:
        if host is None:
            continue
        host = host.strip()
        if not host or host in seen:
            continue
        seen.add(host)
        result.append(host)
    return result


# --------------------------------------------------------------------------- #
# Preflight / host selection
# --------------------------------------------------------------------------- #
def preflight(host, port, timeout=3.0) -> bool:
    """Thin real-socket TCP connect test. True if a connection succeeds."""
    try:
        conn = socket.create_connection((host, port), timeout=timeout)
    except OSError:
        return False
    try:
        conn.close()
    except OSError:
        pass
    return True


def pick_host(candidates, port, connect=preflight):
    """Return the first candidate for which connect(host, port) is truthy.

    `connect` is injectable for tests. Returns None if none connect.
    """
    for host in candidates:
        try:
            if connect(host, port):
                return host
        except OSError:
            continue
    return None


# --------------------------------------------------------------------------- #
# LAN subnet discovery (last-resort; IP-agnostic - pi.local/mDNS is unreliable
# on Android/Termux and the Pi IP is DHCP)
# --------------------------------------------------------------------------- #
# Representative probe destinations: one per RFC1918 private block, plus a
# public address for the default route. A device with a VPN active routes
# public traffic through a tunnel subnet while the real LAN is only reachable
# for private destinations, so probing 8.8.8.8 alone can miss the Wi-Fi LAN
# entirely (observed on a Galaxy S24 with Secure Wi-Fi: 8.8.8.8 -> 10.198.0.x,
# 192.168.1.159 -> 192.168.1.156). Probing every block finds them all.
_PROBE_TARGETS = ("192.168.0.1", "10.0.0.1", "172.16.0.1", "8.8.8.8")


def _source_ip_for(dst, timeout=0.5):
    """Local source IPv4 the OS would use to reach `dst`, or None.

    Uses a UDP connect(); no packets are actually sent. Never raises.
    """
    sock = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(timeout)
        sock.connect((dst, 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass


def _base_of(ip):
    """"a.b.c.d" -> "a.b.c." ; None for anything malformed."""
    if not ip:
        return None
    parts = ip.split(".")
    if len(parts) != 4 or not all(p.isdigit() for p in parts):
        return None
    return ".".join(parts[:3]) + "."


def _is_private_base(base):
    """True if `base` ("a.b.c.") sits in an RFC1918 private range."""
    parts = base.rstrip(".").split(".")
    try:
        o1, o2 = int(parts[0]), int(parts[1])
    except (ValueError, IndexError):
        return False
    if o1 == 10:
        return True
    if o1 == 192 and o2 == 168:
        return True
    if o1 == 172 and 16 <= o2 <= 31:
        return True
    return False


def local_subnet_bases():
    """Ordered, de-duplicated list of this device's local IPv4 /24 bases.

    Probes each RFC1918 block plus the default route (see ``_PROBE_TARGETS``)
    and collects the distinct /24 base of every local interface the OS would
    use. Private-LAN bases are listed before any public/tunnel base, since the
    daemon lives on a private LAN. Returns [] if nothing can be determined.
    """
    private, public, seen = [], [], set()
    for dst in _PROBE_TARGETS:
        base = _base_of(_source_ip_for(dst))
        if not base or base in seen:
            continue
        seen.add(base)
        (private if _is_private_base(base) else public).append(base)
    return private + public


def local_subnet_base():
    """This device's primary local /24 base (first of local_subnet_bases), or None."""
    bases = local_subnet_bases()
    return bases[0] if bases else None


def _is_hello_line(line) -> bool:
    """Pure check: does one raw JSONL frame decode to a daemon MSG_HELLO?

    Tolerant of bytes or str and of any decode error (returns False, never
    raises). Falls back to a raw substring probe if `wire` is unavailable.
    """
    if not line:
        return False
    try:
        import wire
        msg = wire.decode(line)
        return isinstance(msg, dict) and msg.get("type") == wire.MSG_HELLO
    except Exception:
        pass
    try:
        text = line.decode("utf-8") if isinstance(line, (bytes, bytearray)) else line
    except Exception:
        return False
    return '"type":"hello"' in text.replace(" ", "")


def verify_daemon(host, port, timeout=1.0) -> bool:
    """True only if `host:port` is the SPS30 daemon (sends MSG_HELLO on connect).

    Connects, reads the first JSONL line, and confirms it decodes to a hello
    message. Never raises.
    """
    conn = None
    try:
        conn = socket.create_connection((host, port), timeout=timeout)
        conn.settimeout(timeout)
        buf = bytearray()
        while b"\n" not in buf and len(buf) < 65536:
            chunk = conn.recv(4096)
            if not chunk:
                break
            buf.extend(chunk)
        nl = buf.find(b"\n")
        line = bytes(buf[:nl]) if nl >= 0 else bytes(buf)
        return _is_hello_line(line)
    except OSError:
        return False
    except Exception:
        return False
    finally:
        if conn is not None:
            try:
                conn.close()
            except OSError:
                pass


def discover(port, base=None, verify=verify_daemon, timeout=1.0, workers=64):
    """Sweep local /24 subnet(s) concurrently; return the first confirmed daemon.

    With `base` given, sweeps just that /24 (back-compat). Otherwise sweeps
    every detected local /24 (via local_subnet_bases) in a single pool, so a
    VPN default route cannot hide the real Wi-Fi LAN. `verify` is injectable for
    tests. Returns None if no host confirms (or no base is detectable). Bounded
    and quick: short per-host timeout, concurrent thread pool.
    """
    if base is not None:
        bases = [base]
    else:
        bases = local_subnet_bases()
    if not bases:
        return None
    hosts = [b + str(n) for b in bases for n in range(1, 255)]
    max_workers = max(1, min(workers, len(hosts)))
    found = None
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {pool.submit(verify, h, port, timeout): h for h in hosts}
        try:
            for fut in concurrent.futures.as_completed(futures):
                host = futures[fut]
                try:
                    ok = fut.result()
                except Exception:
                    ok = False
                if ok:
                    found = host
                    break
        finally:
            for fut in futures:
                fut.cancel()
    return found


# --------------------------------------------------------------------------- #
# Dependency check (used by --doctor)
# --------------------------------------------------------------------------- #
def _dep_status() -> dict:
    """Report whether each client dependency imports. No side effects on failure."""
    status: dict[str, bool] = {}
    for mod in ("textual", "textual_plotext", "plotext"):
        try:
            __import__(mod)
            status[mod] = True
        except Exception:
            status[mod] = False
    return status


# --------------------------------------------------------------------------- #
# Launch
# --------------------------------------------------------------------------- #
def _launch(host, port) -> int:
    """Import the Textual client lazily and run it. Returns its exit code."""
    import sps30_gui  # lazy: keeps the module import-free for tests/CI
    return sps30_gui.main(["--host", host, "--tcp-port", str(port)])


def _failure_message(candidates, port) -> str:
    tried = ", ".join(candidates) if candidates else "(none)"
    return (
        f"Could not reach the SPS30 daemon on any candidate host (port {port}).\n"
        f"  Tried: {tried}\n"
        "  Also swept the local /24 subnet for the daemon (no match).\n"
        "  Is the Pi powered on and on the same LAN? Is the daemon running?\n"
        "  Set the host explicitly:  sps30 --set-host <ip>\n"
        "  Or use the mDNS name:     sps30 --host pi.local"
    )


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="sps30",
        description="Launch the SPS30 Textual dashboard against the LAN daemon.")
    parser.add_argument("--host", default=None,
                        help="daemon host to use for this run (overrides config)")
    parser.add_argument("--port", type=int, default=None,
                        help=f"daemon TCP port (overrides config; default {DEFAULT_PORT})")
    parser.add_argument("--set-host", default=None, metavar="H",
                        help="persist H as the config host and exit")
    parser.add_argument("--doctor", action="store_true",
                        help="print resolution/config/dependency diagnostics and exit")
    parser.add_argument("--discover", action="store_true",
                        help="sweep the local subnet for the daemon, print it, and exit")
    parser.add_argument("--no-launch", action="store_true",
                        help="resolve and preflight but do not start the TUI (for CI)")
    return parser


def main(argv=None) -> int:
    args = build_arg_parser().parse_args(argv)
    cfg_path = config_path()

    # --set-host: persist to the user config (comment-preserving) and exit.
    if args.set_host is not None:
        host = args.set_host.strip()
        update_config_value(cfg_path, "host", host)
        print(f"Saved host {host!r} to {cfg_path}")
        return 0

    cfg = load_config(cfg_path)
    state = load_config(state_path())
    port = resolve_port(args.port, os.environ, cfg)
    # last_good_host lives in the state file (state wins; fall back to a legacy
    # value that may still be in the config).
    resolve_cfg = dict(cfg)
    resolve_cfg["last_good_host"] = (
        state.get("last_good_host") or cfg.get("last_good_host"))
    candidates = resolve_candidates(args.host, os.environ, resolve_cfg)

    # --discover: subnet sweep only, print the result, exit. No TUI.
    if args.discover:
        bases = local_subnet_bases()
        print(f"subnet bases: {', '.join(bases) if bases else '(could not determine)'}")
        found = discover(port)
        if found:
            print(f"found daemon: {found}")
            return 0
        print("found daemon: not found on the local subnet(s)")
        return 1

    # --doctor: diagnostics only, always exit 0, never launch.
    if args.doctor:
        print(f"config path: {cfg_path}")
        print(f"config exists: {Path(cfg_path).exists()}")
        print("config contents:")
        if cfg:
            for key, value in cfg.items():
                print(f"  {key}={value}")
        else:
            print("  (empty or missing)")
        print(f"last_good_host (state): {state.get('last_good_host', '(none)')}")
        print(f"port: {port}")
        print("candidates (in priority order):")
        results = []
        for host in candidates:
            ok = preflight(host, port)
            results.append((host, ok))
            print(f"  {host}  ->  {'CONNECT' if ok else 'no route'}")
        chosen = next((h for h, ok in results if ok), None)  # reuse loop results
        print(f"chosen host: {chosen if chosen else '(none reachable)'}")
        bases = local_subnet_bases()
        print(f"subnet bases: {', '.join(bases) if bases else '(could not determine)'}")
        found = discover(port)
        print(f"subnet discovery: {found if found else '(no daemon found)'}")
        print("client dependencies:")
        for mod, ok in _dep_status().items():
            print(f"  {mod}: {'ok' if ok else 'MISSING'}")
        return 0

    chosen = pick_host(candidates, port)
    if chosen is None:
        # Last resort: sweep the local /24 for a live daemon (DHCP-friendly).
        chosen = discover(port)
    if chosen is None:
        print(_failure_message(candidates, port), file=sys.stderr)
        return 1

    # Remember what worked - in the state file, never the user's config.
    state["last_good_host"] = chosen
    save_config(state_path(), state)

    if args.no_launch:
        print(chosen)
        return 0

    return _launch(chosen, port)


if __name__ == "__main__":
    raise SystemExit(main())
