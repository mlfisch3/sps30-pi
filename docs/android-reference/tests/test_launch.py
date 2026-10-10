"""Unit tests for sps30_launch. No real sockets, no real TUI.

Every external effect is injected or monkeypatched:
- `preflight` / `pick_host` are replaced so no socket is opened.
- `sps30_gui` is stubbed via sys.modules so the TUI never starts.
"""
from __future__ import annotations

import sys
import types
from pathlib import Path

import pytest

# Make android/sps30_launch.py importable regardless of CWD.
ANDROID_DIR = Path(__file__).resolve().parent.parent
if str(ANDROID_DIR) not in sys.path:
    sys.path.insert(0, str(ANDROID_DIR))

import sps30_launch as L  # noqa: E402


# --------------------------------------------------------------------------- #
# config save + load round-trip
# --------------------------------------------------------------------------- #
def test_load_missing_file_returns_empty(tmp_path):
    assert L.load_config(tmp_path / "does-not-exist") == {}


def test_load_missing_nested_path(tmp_path):
    # Parent dir does not exist either -> still empty, no crash.
    assert L.load_config(tmp_path / "no" / "such" / "config") == {}


def test_save_load_round_trip(tmp_path):
    path = tmp_path / "config"
    cfg = {"host": "10.0.0.5", "port": "8730", "last_good_host": "pi.local"}
    L.save_config(path, cfg)
    assert L.load_config(path) == cfg


def test_save_creates_nested_parent_dirs(tmp_path):
    path = tmp_path / "a" / "b" / "c" / "config"
    assert not path.parent.exists()
    L.save_config(path, {"host": "x"})
    assert path.exists()
    assert L.load_config(path) == {"host": "x"}


def test_load_ignores_comments_and_blanks(tmp_path):
    path = tmp_path / "config"
    path.write_text("# a comment\n\nhost = 1.2.3.4 \nbad line no equals\n=novalue\n",
                    encoding="utf-8")
    cfg = L.load_config(path)
    assert cfg == {"host": "1.2.3.4"}


def test_save_empty_config_then_load(tmp_path):
    path = tmp_path / "config"
    L.save_config(path, {})
    assert L.load_config(path) == {}


# --------------------------------------------------------------------------- #
# resolve_candidates: priority / blank-drop / de-dup
# --------------------------------------------------------------------------- #
def test_resolve_full_priority_order():
    cands = L.resolve_candidates(
        "cli", {"SPS30_HOST": "env"},
        {"host": "cfghost", "last_good_host": "cfglast"})
    assert cands == ["cli", "env", "cfghost", "cfglast", "pi.local"]


def test_resolve_cli_wins_first():
    cands = L.resolve_candidates("cli", {"SPS30_HOST": "env"}, {"host": "cfg"})
    assert cands[0] == "cli"


def test_resolve_env_over_cfg_when_no_cli():
    cands = L.resolve_candidates(None, {"SPS30_HOST": "env"}, {"host": "cfg"})
    assert cands[0] == "env"


def test_resolve_drops_blanks_and_none():
    cands = L.resolve_candidates("  ", {"SPS30_HOST": None},
                                 {"host": "", "last_good_host": "  "})
    assert cands == ["pi.local"]


def test_resolve_dedup_preserves_order():
    cands = L.resolve_candidates(
        "dup", {"SPS30_HOST": "dup"},
        {"host": "dup", "last_good_host": "pi.local"})
    assert cands == ["dup", "pi.local"]


def test_resolve_defaults_only():
    assert L.resolve_candidates(None, {}, {}) == ["pi.local"]


def test_resolve_tolerates_none_env_and_cfg():
    assert L.resolve_candidates("h", None, None) == ["h", "pi.local"]


# --------------------------------------------------------------------------- #
# pick_host with injected connect fn
# --------------------------------------------------------------------------- #
def test_pick_host_first_success():
    calls = []

    def connect(host, port):
        calls.append(host)
        return host == "b"

    chosen = L.pick_host(["a", "b", "c"], 8730, connect=connect)
    assert chosen == "b"
    # Stops after the first success (never tries "c").
    assert calls == ["a", "b"]


def test_pick_host_all_fail_returns_none():
    chosen = L.pick_host(["a", "b"], 8730, connect=lambda h, p: False)
    assert chosen is None


def test_pick_host_empty_candidates():
    assert L.pick_host([], 8730, connect=lambda h, p: True) is None


def test_pick_host_skips_oserror():
    def connect(host, port):
        if host == "a":
            raise OSError("boom")
        return host == "b"

    assert L.pick_host(["a", "b"], 8730, connect=connect) == "b"


# --------------------------------------------------------------------------- #
# helpers to isolate config path per test
# --------------------------------------------------------------------------- #
@pytest.fixture
def cfg_env(tmp_path, monkeypatch):
    path = tmp_path / "sps30" / "config"
    monkeypatch.setenv("SPS30_CONFIG", str(path))
    # Ensure env host does not leak in from the host machine.
    monkeypatch.delenv("SPS30_HOST", raising=False)
    return path


# --------------------------------------------------------------------------- #
# main('--set-host X') persists and exits 0
# --------------------------------------------------------------------------- #
def test_main_set_host_persists_and_exits_zero(cfg_env):
    rc = L.main(["--set-host", "192.168.1.42"])
    assert rc == 0
    assert L.load_config(cfg_env) == {"host": "192.168.1.42"}


def test_main_set_host_preserves_existing_keys(cfg_env):
    L.save_config(cfg_env, {"last_good_host": "oldpi", "port": "8730"})
    rc = L.main(["--set-host", "10.0.0.9"])
    assert rc == 0
    cfg = L.load_config(cfg_env)
    assert cfg["host"] == "10.0.0.9"
    assert cfg["last_good_host"] == "oldpi"


# --------------------------------------------------------------------------- #
# main('--doctor') exits 0 with monkeypatched preflight
# --------------------------------------------------------------------------- #
def test_main_doctor_exits_zero_no_launch(cfg_env, monkeypatch, capsys):
    monkeypatch.setattr(L, "preflight", lambda h, p, timeout=3.0: h == "pi.local")
    # Keep discovery off the real network.
    monkeypatch.setattr(L, "local_subnet_bases", lambda: ["10.9.9."])
    monkeypatch.setattr(L, "discover", lambda port, **k: "10.9.9.50")
    # If _launch were called this would blow up; assert it is not.
    monkeypatch.setattr(L, "_launch",
                        lambda *a, **k: pytest.fail("doctor must not launch"))
    rc = L.main(["--doctor"])
    assert rc == 0
    out = capsys.readouterr().out
    assert "config path:" in out
    assert "pi.local" in out
    assert "chosen host:" in out
    # Subnet discovery is reported.
    assert "subnet bases:" in out
    assert "10.9.9.50" in out
    # Dependency lines are printed for each client module.
    assert "textual" in out


# --------------------------------------------------------------------------- #
# main('--no-launch') prints chosen host, no TUI import
# --------------------------------------------------------------------------- #
def test_main_no_launch_prints_host_and_saves(cfg_env, monkeypatch, capsys):
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: "10.1.1.1")
    # Guard: ensure no TUI is launched.
    monkeypatch.setattr(L, "_launch",
                        lambda *a, **k: pytest.fail("no-launch must not launch"))
    rc = L.main(["--no-launch"])
    assert rc == 0
    out = capsys.readouterr().out.strip()
    assert out == "10.1.1.1"
    # last_good_host was remembered - in the STATE file, not the user config.
    assert L.load_config(L.state_path())["last_good_host"] == "10.1.1.1"
    assert not cfg_env.exists()  # a launch never writes the user config


# --------------------------------------------------------------------------- #
# launch path: sps30_gui.main called with right args; last_good_host saved
# --------------------------------------------------------------------------- #
def test_main_launch_calls_gui_with_host_and_port(cfg_env, monkeypatch):
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: "172.16.0.3")

    recorded = {}
    fake_gui = types.ModuleType("sps30_gui")

    def fake_main(argv):
        recorded["argv"] = argv
        return 0

    fake_gui.main = fake_main
    monkeypatch.setitem(sys.modules, "sps30_gui", fake_gui)

    rc = L.main(["--port", "9999"])
    assert rc == 0
    assert recorded["argv"] == ["--host", "172.16.0.3", "--tcp-port", "9999"]
    assert L.load_config(L.state_path())["last_good_host"] == "172.16.0.3"


def test_main_launch_uses_default_port(cfg_env, monkeypatch):
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: "pi.local")
    recorded = {}
    fake_gui = types.ModuleType("sps30_gui")

    def fake_main(argv):
        recorded["argv"] = argv
        return 0

    fake_gui.main = fake_main
    monkeypatch.setitem(sys.modules, "sps30_gui", fake_gui)
    rc = L.main([])
    assert rc == 0
    assert recorded["argv"] == ["--host", "pi.local", "--tcp-port", "8730"]


def test_main_launch_propagates_gui_exit_code(cfg_env, monkeypatch):
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: "pi.local")
    fake_gui = types.ModuleType("sps30_gui")
    fake_gui.main = lambda argv: 7
    monkeypatch.setitem(sys.modules, "sps30_gui", fake_gui)
    assert L.main([]) == 7


# --------------------------------------------------------------------------- #
# total preflight failure -> non-zero + actionable message
# --------------------------------------------------------------------------- #
def test_main_total_failure_returns_nonzero(cfg_env, monkeypatch, capsys):
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: None)
    monkeypatch.setattr(L, "discover", lambda port, **k: None)
    monkeypatch.setattr(L, "_launch",
                        lambda *a, **k: pytest.fail("must not launch on failure"))
    rc = L.main([])
    assert rc != 0
    err = capsys.readouterr().err
    assert "--set-host" in err
    assert "pi.local" in err


# --------------------------------------------------------------------------- #
# LAN subnet discovery
# --------------------------------------------------------------------------- #
def test_local_subnet_base_shape():
    base = L.local_subnet_base()
    # Either None or a "x.y.z." string ending in a dot with 3 numeric octets.
    if base is not None:
        assert base.endswith(".")
        octets = base.rstrip(".").split(".")
        assert len(octets) == 3
        assert all(o.isdigit() for o in octets)


def test_local_subnet_base_never_raises(monkeypatch):
    def boom(*a, **k):
        raise OSError("no network")

    monkeypatch.setattr(L.socket, "socket", boom)
    assert L.local_subnet_base() is None


def test_discover_finds_the_one_matching_host():
    def verify(host, port, timeout=1.0):
        return host == "192.168.7.42"

    found = L.discover(8730, base="192.168.7.", verify=verify, timeout=0.01)
    assert found == "192.168.7.42"


def test_discover_returns_none_when_no_match():
    found = L.discover(8730, base="192.168.7.",
                       verify=lambda h, p, timeout=1.0: False, timeout=0.01)
    assert found is None


def test_discover_none_base_and_undetectable(monkeypatch):
    monkeypatch.setattr(L, "local_subnet_bases", lambda: [])
    assert L.discover(8730, verify=lambda h, p, timeout=1.0: True) is None


def test_discover_sweeps_all_detected_bases(monkeypatch):
    # Simulate a VPN-active device: default route on a tunnel /24 plus the real
    # Wi-Fi /24. The daemon lives only on the Wi-Fi subnet; discovery must still
    # find it even though it is not the default-route subnet.
    monkeypatch.setattr(L, "local_subnet_bases", lambda: ["192.168.1.", "10.198.0."])

    def verify(host, port, timeout=1.0):
        return host == "192.168.1.159"

    found = L.discover(8730, verify=verify, timeout=0.01)
    assert found == "192.168.1.159"


def test_local_subnet_bases_collects_private_first(monkeypatch):
    # 192.168.0.1 -> Wi-Fi LAN; 10.0.0.1 -> VPN tunnel; 8.8.8.8 -> same tunnel.
    routes = {
        "192.168.0.1": "192.168.1.156",
        "10.0.0.1": "10.198.0.5",
        "172.16.0.1": None,
        "8.8.8.8": "10.198.0.5",
    }
    monkeypatch.setattr(L, "_source_ip_for", lambda dst, timeout=0.5: routes.get(dst))
    bases = L.local_subnet_bases()
    # Both distinct /24s are found, private LAN listed first, de-duplicated.
    assert bases == ["192.168.1.", "10.198.0."]


def test_discover_sweeps_the_full_last_octet_range():
    seen = set()

    def verify(host, port, timeout=1.0):
        seen.add(host)
        return False

    L.discover(8730, base="10.0.0.", verify=verify, timeout=0.01)
    # Sweeps .1 through .254 inclusive, and nothing outside that.
    assert "10.0.0.1" in seen
    assert "10.0.0.254" in seen
    assert "10.0.0.0" not in seen
    assert "10.0.0.255" not in seen


# --------------------------------------------------------------------------- #
# verify_daemon / _is_hello_line (pure hello-line check)
# --------------------------------------------------------------------------- #
def test_is_hello_line_true_for_hello_message():
    assert L._is_hello_line(b'{"type":"hello","product":"SPS30"}\n') is True


def test_is_hello_line_accepts_str_and_spacing():
    assert L._is_hello_line('{"type": "hello"}') is True


def test_is_hello_line_false_for_other_types():
    assert L._is_hello_line(b'{"type":"reading","pm2p5":3.1}') is False


def test_is_hello_line_false_for_garbage():
    assert L._is_hello_line(b"not json at all") is False
    assert L._is_hello_line(b"") is False
    assert L._is_hello_line(None) is False


def test_verify_daemon_never_raises_on_connect_failure(monkeypatch):
    def boom(*a, **k):
        raise OSError("refused")

    monkeypatch.setattr(L.socket, "create_connection", boom)
    assert L.verify_daemon("10.0.0.1", 8730, timeout=0.01) is False


def test_verify_daemon_true_on_hello(monkeypatch):
    class FakeConn:
        def __init__(self):
            self._chunks = [b'{"type":"hello","product":"SPS30"}\n']

        def settimeout(self, t):
            pass

        def recv(self, n):
            return self._chunks.pop(0) if self._chunks else b""

        def close(self):
            pass

    monkeypatch.setattr(L.socket, "create_connection",
                        lambda *a, **k: FakeConn())
    assert L.verify_daemon("10.0.0.1", 8730, timeout=0.01) is True


def test_verify_daemon_false_on_non_hello(monkeypatch):
    class FakeConn:
        def settimeout(self, t):
            pass

        def recv(self, n):
            return b'{"type":"reading"}\n'

        def close(self):
            pass

    monkeypatch.setattr(L.socket, "create_connection",
                        lambda *a, **k: FakeConn())
    assert L.verify_daemon("10.0.0.1", 8730, timeout=0.01) is False


# --------------------------------------------------------------------------- #
# --discover CLI flag
# --------------------------------------------------------------------------- #
def test_main_discover_found(cfg_env, monkeypatch, capsys):
    monkeypatch.setattr(L, "local_subnet_bases", lambda: ["192.168.4."])
    monkeypatch.setattr(L, "discover", lambda port, **k: "192.168.4.77")
    monkeypatch.setattr(L, "_launch",
                        lambda *a, **k: pytest.fail("--discover must not launch"))
    rc = L.main(["--discover"])
    assert rc == 0
    out = capsys.readouterr().out
    assert "192.168.4.77" in out


def test_main_discover_not_found(cfg_env, monkeypatch, capsys):
    monkeypatch.setattr(L, "local_subnet_bases", lambda: ["192.168.4."])
    monkeypatch.setattr(L, "discover", lambda port, **k: None)
    rc = L.main(["--discover"])
    assert rc != 0
    assert "not found" in capsys.readouterr().out


# --------------------------------------------------------------------------- #
# launch path falls back to discovery when preflight fails
# --------------------------------------------------------------------------- #
def test_main_launch_uses_discovery_fallback(cfg_env, monkeypatch):
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: None)
    monkeypatch.setattr(L, "discover", lambda port, **k: "192.168.9.9")
    recorded = {}
    fake_gui = types.ModuleType("sps30_gui")

    def fake_main(argv):
        recorded["argv"] = argv
        return 0

    fake_gui.main = fake_main
    monkeypatch.setitem(sys.modules, "sps30_gui", fake_gui)
    rc = L.main([])
    assert rc == 0
    assert recorded["argv"] == ["--host", "192.168.9.9", "--tcp-port", "8730"]
    assert L.load_config(L.state_path())["last_good_host"] == "192.168.9.9"


# --------------------------------------------------------------------------- #
# config_path resolution
# --------------------------------------------------------------------------- #
def test_config_path_uses_explicit_env(monkeypatch, tmp_path):
    monkeypatch.setenv("SPS30_CONFIG", str(tmp_path / "custom"))
    assert L.config_path() == tmp_path / "custom"


def test_config_path_uses_xdg(monkeypatch, tmp_path):
    monkeypatch.delenv("SPS30_CONFIG", raising=False)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    assert L.config_path() == tmp_path / "xdg" / "sps30" / "config"


# --------------------------------------------------------------------------- #
# config fidelity: comments preserved; port honored from config
# --------------------------------------------------------------------------- #
def test_set_host_preserves_comments(cfg_env):
    cfg_env.parent.mkdir(parents=True, exist_ok=True)
    cfg_env.write_text("# my sps30 config\nhost=oldip\n# keep me\nport=8730\n")
    rc = L.main(["--set-host", "192.168.1.77"])
    assert rc == 0
    text = cfg_env.read_text()
    assert "# my sps30 config" in text     # comments survive
    assert "# keep me" in text
    assert "host=192.168.1.77" in text     # host updated in place
    assert "port=8730" in text             # other keys survive
    assert text.count("host=") == 1        # replaced, not duplicated


def test_launch_does_not_touch_user_config(cfg_env, monkeypatch):
    cfg_env.parent.mkdir(parents=True, exist_ok=True)
    original = "# precious comment\nhost=10.0.0.5\n"
    cfg_env.write_text(original)
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: "10.0.0.5")
    monkeypatch.setattr(L, "_launch", lambda *a, **k: 0)
    rc = L.main([])
    assert rc == 0
    assert cfg_env.read_text() == original                 # config untouched
    assert L.load_config(L.state_path())["last_good_host"] == "10.0.0.5"


def test_resolve_port_precedence():
    assert L.resolve_port(9999, {"SPS30_PORT": "1"}, {"port": "2"}) == 9999
    assert L.resolve_port(None, {"SPS30_PORT": "1234"}, {"port": "2"}) == 1234
    assert L.resolve_port(None, {}, {"port": "8888"}) == 8888
    assert L.resolve_port(None, {}, {}) == L.DEFAULT_PORT
    assert L.resolve_port(None, {}, {"port": "junk"}) == L.DEFAULT_PORT


def test_launch_uses_config_port(cfg_env, monkeypatch):
    cfg_env.parent.mkdir(parents=True, exist_ok=True)
    cfg_env.write_text("port=9000\n")
    monkeypatch.setattr(L, "pick_host", lambda cands, port, **k: "pi.local")
    recorded = {}
    fake_gui = types.ModuleType("sps30_gui")
    fake_gui.main = lambda argv: recorded.setdefault("argv", argv) and 0 or 0
    monkeypatch.setitem(sys.modules, "sps30_gui", fake_gui)
    rc = L.main([])                      # no --port -> config's 9000 wins
    assert rc == 0
    assert recorded["argv"] == ["--host", "pi.local", "--tcp-port", "9000"]
