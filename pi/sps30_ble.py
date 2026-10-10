#!/usr/bin/env python3
"""BLE-UART bridge for the SPS30 daemon.

Advertises a Nordic UART Service (NUS) BLE peripheral and pipes the **exact same
newline-delimited JSONL** the daemon already serves over TCP - so an Android phone
(or any BLE central) gets identical live readings + control commands over Bluetooth
when there is no Wi-Fi network available. See BLUETOOTH_DESIGN.md.

Architecture: this bridge is a *local TCP client* of the running daemon
(127.0.0.1:8730); it does not touch the daemon or the sensor. Data flow:

    daemon TCP  --(JSONL)-->  bridge  --(notify, chunked)-->  BLE TX char  --> phone
    phone       --(write)-->  BLE RX char  -->  bridge  --(JSONL)-->  daemon TCP

The future ESP32-C6 firmware implements the same NUS + JSONL directly; the phone app
is identical either way.

Requires BlueZ (with LE advertising) and the `bluezero` library:  pip install bluezero
Runs as a small service next to sps30-daemon.  NOT YET tested on hardware.
"""
from __future__ import annotations

import argparse
import socket
import threading
import time

from bluezero import adapter, peripheral  # type: ignore
from gi.repository import GLib  # type: ignore

# Nordic UART Service - identical UUIDs on the Android side and (future) ESP32-C6.
NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # write:  phone -> host (commands)
NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # notify: host  -> phone (readings)

NOTIFY_CHUNK = 180  # bytes per notification (< negotiated ATT_MTU-3, conservative)


class DaemonLink:
    """Maintains a socket to the local daemon, reconnecting as needed. Thread-safe
    send; a background reader invokes ``on_line(bytes_without_newline)`` per JSONL
    line received from the daemon."""

    def __init__(self, host: str, port: int, on_line):
        self.host = host
        self.port = port
        self.on_line = on_line
        self._sock: socket.socket | None = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        threading.Thread(target=self._run, name="daemon-reader", daemon=True).start()

    def _connect(self) -> socket.socket:
        while not self._stop.is_set():
            try:
                s = socket.create_connection((self.host, self.port), timeout=5)
                with self._lock:
                    self._sock = s
                return s
            except OSError:
                time.sleep(2)
        raise RuntimeError("stopped")

    def _run(self):
        buf = bytearray()
        while not self._stop.is_set():
            try:
                s = self._connect()
                s.settimeout(None)
                while not self._stop.is_set():
                    data = s.recv(4096)
                    if not data:
                        break  # daemon closed; reconnect
                    buf.extend(data)
                    while b"\n" in buf:
                        line, _, rest = buf.partition(b"\n")
                        buf = bytearray(rest)
                        if line.strip():
                            self.on_line(bytes(line))
            except OSError:
                pass
            with self._lock:
                self._sock = None
            time.sleep(1)

    def send(self, data: bytes) -> None:
        """Send a raw command frame (already newline-terminated JSONL) to the daemon."""
        with self._lock:
            s = self._sock
        if s is None:
            return
        try:
            s.sendall(data)
        except OSError:
            pass


class Bridge:
    def __init__(self, args):
        self.args = args
        self.tx_char = None            # set once the peripheral is built
        self.notifying = False
        self._rx_buf = bytearray()     # accumulates writes from the phone (RX)
        self.link = DaemonLink(args.daemon_host, args.daemon_port, self._on_daemon_line)

    # --- daemon -> phone (TX notifications) ---------------------------------
    def _on_daemon_line(self, line: bytes):
        """A full JSONL line arrived from the daemon; notify it to the phone in
        MTU-sized chunks (scheduled on the GLib main loop for D-Bus thread-safety)."""
        if not self.notifying or self.tx_char is None:
            return  # nobody subscribed; drop (phone will get the live stream on connect)
        payload = line + b"\n"
        for i in range(0, len(payload), NOTIFY_CHUNK):
            chunk = list(payload[i:i + NOTIFY_CHUNK])
            GLib.idle_add(self._notify_chunk, chunk)

    def _notify_chunk(self, chunk):
        try:
            self.tx_char.set_value(chunk)
        except Exception:
            pass
        return False  # one-shot idle callback

    def _on_tx_notify(self, notifying, characteristic):
        self.notifying = notifying

    # --- phone -> daemon (RX writes) ----------------------------------------
    def _on_rx_write(self, value, options):
        """The phone wrote to RX. `value` is a bytes-like; accumulate and forward
        complete newline-terminated command lines to the daemon."""
        self._rx_buf.extend(bytes(value))
        while b"\n" in self._rx_buf:
            line, _, rest = self._rx_buf.partition(b"\n")
            self._rx_buf = bytearray(rest)
            if line.strip():
                self.link.send(line + b"\n")

    def run(self):
        dongle = adapter.Adapter(self.args.adapter) if self.args.adapter else \
            list(adapter.Adapter.available())[0]
        ble = peripheral.Peripheral(dongle.address, local_name=self.args.name)
        ble.add_service(srv_id=1, uuid=NUS_SERVICE, primary=True)
        # TX: notify (host -> phone)
        ble.add_characteristic(
            srv_id=1, chr_id=1, uuid=NUS_TX, value=[], notifying=False,
            flags=["notify"], notify_callback=self._on_tx_notify,
        )
        # RX: write (phone -> host)
        ble.add_characteristic(
            srv_id=1, chr_id=2, uuid=NUS_RX, value=[], notifying=False,
            flags=["write", "write-without-response"], write_callback=self._on_rx_write,
        )
        # Grab a handle to the TX characteristic object so the reader can notify it.
        self.tx_char = ble.characteristics[0]
        print(f"SPS30 BLE bridge advertising '{self.args.name}' (NUS) on {dongle.address}; "
              f"bridging daemon {self.args.daemon_host}:{self.args.daemon_port}")
        ble.publish()  # blocks in the GLib main loop


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="BLE-UART bridge for the SPS30 daemon")
    p.add_argument("--daemon-host", default="127.0.0.1")
    p.add_argument("--daemon-port", type=int, default=8730)
    p.add_argument("--adapter", default=None, help="hci adapter MAC (default: first)")
    p.add_argument("--name", default="SPS30", help="BLE advertised local name")
    args = p.parse_args(argv)
    Bridge(args).run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
