#!/usr/bin/env python3
"""Driver and CLI for the Sensirion SPS30 particulate matter sensor over UART.

The SPS30 speaks Sensirion's SHDLC framing on a 115200 8N1 serial line. This
module separates the pure protocol codec (framing, byte-stuffing, checksum,
measurement decoding) - which is unit-testable without any hardware - from the
serial transport in the ``SPS30`` class.

UART wiring to a Raspberry Pi header (SEL left floating selects UART mode):
    SPS30 VDD  -> Pi 5V
    SPS30 GND  -> Pi GND
    SPS30 RX   -> Pi TXD (GPIO14, pin 8)
    SPS30 TX   -> Pi RXD (GPIO15, pin 10)
    SPS30 SEL  -> unconnected
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
import time
from dataclasses import asdict, dataclass

import serial  # pyserial

# --- SHDLC constants --------------------------------------------------------
START = 0x7E
STOP = 0x7E
ESC = 0x7D
ADDR = 0x00  # the SPS30's SHDLC address is fixed at 0

# Byte-stuffing: inside a frame these bytes are escaped as ESC, (byte ^ 0x20).
_STUFF = {0x7E: 0x5E, 0x7D: 0x5D, 0x11: 0x31, 0x13: 0x33}
_UNSTUFF = {v: k for k, v in _STUFF.items()}

# Commands
CMD_START = 0x00
CMD_STOP = 0x01
CMD_READ = 0x03
CMD_SLEEP = 0x10
CMD_WAKE = 0x11
CMD_CLEAN = 0x56
CMD_AUTO_CLEAN = 0x80
CMD_INFO = 0xD0
CMD_VERSION = 0xD1
CMD_STATUS = 0xD2
CMD_RESET = 0xD3

STATE_ERRORS = {
    0x01: "wrong data length for this command",
    0x02: "unknown command",
    0x03: "no access right for command",
    0x04: "illegal command parameter or out of range",
    0x28: "internal function argument out of range",
    0x43: "command not allowed in current state",
}


class SPS30Error(Exception):
    """Any protocol- or device-level failure talking to the SPS30."""


@dataclass
class Measurement:
    """One set of SPS30 outputs. Mass in ug/m3, number in #/cm3, size in um."""

    pm1_0: float
    pm2_5: float
    pm4_0: float
    pm10: float
    nc0_5: float
    nc1_0: float
    nc2_5: float
    nc4_0: float
    nc10: float
    typical_size: float

    def __str__(self) -> str:
        return (
            f"PM1.0={self.pm1_0:6.2f}  PM2.5={self.pm2_5:6.2f}  "
            f"PM4.0={self.pm4_0:6.2f}  PM10={self.pm10:6.2f} ug/m3   "
            f"typ.size={self.typical_size:4.2f} um"
        )


# --- pure protocol codec (no hardware needed) -------------------------------
def checksum(frame_bytes: bytes) -> int:
    """SHDLC checksum: LSB of the byte sum, bitwise inverted."""
    return 0xFF - (sum(frame_bytes) & 0xFF)


def stuff(payload: bytes) -> bytes:
    """Apply SHDLC byte-stuffing to the bytes between the frame delimiters."""
    out = bytearray()
    for b in payload:
        if b in _STUFF:
            out += bytes([ESC, _STUFF[b]])
        else:
            out.append(b)
    return bytes(out)


def unstuff(data: bytes) -> bytes:
    """Reverse SHDLC byte-stuffing."""
    out = bytearray()
    i = 0
    while i < len(data):
        if data[i] == ESC:
            i += 1
            if i >= len(data) or data[i] not in _UNSTUFF:
                raise SPS30Error("malformed byte-stuffing in response")
            out.append(_UNSTUFF[data[i]])
        else:
            out.append(data[i])
        i += 1
    return bytes(out)


def build_frame(cmd: int, data: bytes = b"") -> bytes:
    """Build a full MOSI frame (with delimiters, checksum and stuffing)."""
    payload = bytes([ADDR, cmd, len(data)]) + data
    body = payload + bytes([checksum(payload)])
    return bytes([START]) + stuff(body) + bytes([STOP])


def parse_response(inner: bytes) -> tuple[int, int, bytes]:
    """Parse the un-stuffed bytes between the delimiters of a MISO frame.

    Returns (cmd, state, data). Verifies framing and checksum but does not
    itself raise on a non-zero device state - the caller decides.
    """
    if len(inner) < 5:
        raise SPS30Error(f"response too short: {inner.hex()}")
    _adr, cmd, state, length = inner[0], inner[1], inner[2], inner[3]
    data = inner[4 : 4 + length]
    if len(data) != length or len(inner) < 5 + length:
        raise SPS30Error(f"declared length {length} exceeds frame {inner.hex()}")
    chk = inner[4 + length]
    expected = checksum(inner[: 4 + length])
    if chk != expected:
        raise SPS30Error(
            f"checksum mismatch got 0x{chk:02x} want 0x{expected:02x} "
            f"(frame {inner.hex()})"
        )
    return cmd, state, data


def decode_measurement(data: bytes) -> Measurement | None:
    """Decode a Read-Measured-Values payload (40 big-endian float32 bytes)."""
    if len(data) == 0:
        return None  # no new measurement ready yet
    if len(data) != 40:
        raise SPS30Error(f"expected 40 data bytes, got {len(data)}: {data.hex()}")
    return Measurement(*struct.unpack(">10f", data))


class SPS30:
    """Talks to an SPS30 over a serial port using the codec above."""

    def __init__(self, port: str = "/dev/serial0", timeout: float = 2.0):
        self._ser = serial.Serial(
            port,
            baudrate=115200,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=timeout,
        )

    def close(self) -> None:
        self._ser.close()

    def __enter__(self) -> "SPS30":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    # --- transport ----------------------------------------------------------
    def _read_frame(self) -> bytes:
        """Read one SHDLC frame: skip to START, collect through STOP.

        Returns the raw bytes *including* both 0x7e delimiters and still
        byte-stuffed. Shared by the decoded reader and the raw pass-through so
        the framing logic exists in exactly one place.
        """
        while True:  # skip to the frame start delimiter
            b = self._ser.read(1)
            if not b:
                raise SPS30Error("timeout waiting for response (start byte)")
            if b[0] == START:
                break
        out = bytearray([START])
        while True:  # collect through the stop delimiter
            b = self._ser.read(1)
            if not b:
                raise SPS30Error("timeout waiting for response (stop byte)")
            out.append(b[0])
            if b[0] == STOP:
                break
        return bytes(out)

    def _read_inner(self) -> bytes:
        # One framed response, with the delimiters stripped and un-stuffed.
        frame = self._read_frame()
        return unstuff(frame[1:-1])

    def transceive_raw(self, request: bytes) -> bytes:
        """Send raw request bytes and return one raw SHDLC response frame.

        Resets the input buffer, writes ``request`` verbatim, then reads exactly
        one delimiter-framed response and returns it raw - *including* both 0x7e
        delimiters and with byte-stuffing intact. This is the pass-through the
        raw console needs: bytes go out and come back untouched. Must be called
        by the single serial owner only (the daemon's acquisition thread).
        """
        self._ser.reset_input_buffer()
        self._ser.write(request)
        return self._read_frame()

    def _transceive(self, cmd: int, data: bytes = b"") -> bytes:
        self._ser.reset_input_buffer()
        self._ser.write(build_frame(cmd, data))
        rcmd, state, payload = parse_response(self._read_inner())
        if rcmd != cmd:
            raise SPS30Error(f"command echo mismatch sent 0x{cmd:02x} got 0x{rcmd:02x}")
        if state != 0:
            reason = STATE_ERRORS.get(state, "unknown error")
            raise SPS30Error(f"device error state 0x{state:02x}: {reason}")
        return payload

    # --- commands -----------------------------------------------------------
    def start_measurement(self) -> None:
        # subcommand 0x01, output format 0x03 = big-endian IEEE-754 floats.
        self._transceive(CMD_START, bytes([0x01, 0x03]))

    def stop_measurement(self) -> None:
        self._transceive(CMD_STOP)

    def read_measurement(self) -> Measurement | None:
        return decode_measurement(self._transceive(CMD_READ))

    def product_type(self) -> str:
        return _ascii(self._transceive(CMD_INFO, bytes([0x00])))

    def serial_number(self) -> str:
        return _ascii(self._transceive(CMD_INFO, bytes([0x03])))

    def firmware_version(self) -> str:
        data = self._transceive(CMD_VERSION)
        if len(data) < 2:
            raise SPS30Error(f"short version response: {data.hex()}")
        return f"{data[0]}.{data[1]}"

    def fan_clean(self) -> None:
        self._transceive(CMD_CLEAN)

    def reset(self) -> None:
        self._transceive(CMD_RESET)

    # --- sleep / wake (firmware >= 2.0) -------------------------------------
    def sleep(self) -> None:
        """Enter Sleep-Mode: fan, laser and most electronics off (< 50 uA).

        Must be issued from idle, i.e. after :meth:`stop_measurement`. This also
        **disables the serial interface** - see :meth:`wake` for how to get back.
        """
        self._transceive(CMD_SLEEP)

    def wake(self, attempts: int = 3) -> None:
        """Leave Sleep-Mode and return to idle.

        In Sleep-Mode the UART receiver is off, so the datasheet's sequence is:
        send a single ``0xFF`` byte as a low pulse on RX to wake the interface,
        then send the Wake-up command **within 100 ms**. Miss that window and the
        module drops straight back to sleep, so a lost race just looks like a
        timeout - hence the retries.

        The 0xFF pulse is not a frame and draws no reply; it is deliberately
        written raw rather than through :meth:`_transceive`.
        """
        last: Exception | None = None
        for _ in range(max(1, attempts)):
            try:
                self._ser.reset_input_buffer()
                self._ser.write(b"\xff")
                self._ser.flush()
                self._transceive(CMD_WAKE)
                return
            except (SPS30Error, serial.SerialException, OSError) as exc:
                last = exc
                time.sleep(0.05)
        raise SPS30Error(f"wake failed after {attempts} attempts: {last}")

    def auto_clean_interval(self) -> int:
        """Seconds between automatic fan cleans (0 = disabled)."""
        data = self._transceive(CMD_AUTO_CLEAN, bytes([0x00]))
        if len(data) < 4:
            raise SPS30Error(f"short auto-clean response: {data.hex()}")
        return int.from_bytes(data[:4], "big")

    def set_auto_clean_interval(self, seconds: int) -> None:
        """Set the automatic fan-clean interval; stored in non-volatile memory.

        Note this counter only advances while the sensor is *powered and
        measuring*, and resets whenever it is switched off - so duty-cycled
        operation must schedule cleaning itself (see the daemon).
        """
        payload = bytes([0x00]) + int(seconds).to_bytes(4, "big")
        self._transceive(CMD_AUTO_CLEAN, payload)


def _ascii(data: bytes) -> str:
    return data.split(b"\x00", 1)[0].decode("ascii", "replace")


# --- CLI --------------------------------------------------------------------
def _wait_for_first(sensor: SPS30, warmup: float) -> Measurement:
    deadline = time.monotonic() + warmup
    while True:
        m = sensor.read_measurement()
        if m is not None:
            return m
        if time.monotonic() > deadline:
            raise SPS30Error("no measurement became ready within warm-up window")
        time.sleep(0.2)


def _cmd_info(sensor: SPS30) -> None:
    print(f"product type      : {sensor.product_type()}")
    print(f"serial number     : {sensor.serial_number()}")
    print(f"firmware version  : {sensor.firmware_version()}")


def _cmd_stream(sensor: SPS30, args: argparse.Namespace) -> None:
    sensor.start_measurement()
    try:
        m = _wait_for_first(sensor, warmup=10.0)
        count = 0
        while True:
            ts = time.strftime("%H:%M:%S")
            if args.json:
                print(json.dumps({"time": ts, **asdict(m)}), flush=True)
            else:
                print(f"[{ts}] {m}", flush=True)
            count += 1
            if args.count and count >= args.count:
                break
            time.sleep(args.interval)
            nxt = sensor.read_measurement()
            if nxt is not None:
                m = nxt
    finally:
        sensor.stop_measurement()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Sensirion SPS30 over UART.")
    p.add_argument("--port", default="/dev/serial0", help="serial device")
    p.add_argument("--interval", type=float, default=1.0, help="seconds between reads")
    p.add_argument("--count", type=int, default=0, help="number of readings (0 = forever)")
    p.add_argument("--json", action="store_true", help="emit JSON lines")
    p.add_argument(
        "command",
        nargs="?",
        default="stream",
        choices=["stream", "info", "clean"],
        help="what to do (default: stream)",
    )
    args = p.parse_args(argv)

    try:
        with SPS30(port=args.port) as sensor:
            if args.command == "info":
                _cmd_info(sensor)
            elif args.command == "clean":
                sensor.start_measurement()
                time.sleep(2)
                sensor.fan_clean()
                print("fan cleaning started (~10 s)")
                time.sleep(12)
                sensor.stop_measurement()
            else:
                _cmd_info(sensor)
                print("-" * 72)
                _cmd_stream(sensor, args)
    except KeyboardInterrupt:
        print("\nstopped.", file=sys.stderr)
    except (SPS30Error, serial.SerialException) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
