"""Pure codec unit tests - no hardware, no serial port.

These validate framing, byte-stuffing, checksum and float decoding against the
exact bytes captured from a real SPS30 (see vectors.py).
"""
import struct

import pytest

import sps30
from tests import vectors


# --- checksum ---------------------------------------------------------------
def test_checksum_known_value():
    # product-type request payload: ADR CMD LEN ARG = 00 d0 01 00 -> CHK 0x2e
    assert sps30.checksum(bytes.fromhex("00d00100")) == 0x2E


def test_checksum_is_inverted_low_byte():
    assert sps30.checksum(b"\x00") == 0xFF
    assert sps30.checksum(b"\xff\x01") == 0xFF  # sum 0x100 -> low byte 0x00 -> ~ = 0xFF


# --- byte stuffing ----------------------------------------------------------
@pytest.mark.parametrize(
    "raw, wire",
    [
        (0x7E, "7d5e"),
        (0x7D, "7d5d"),
        (0x11, "7d31"),
        (0x13, "7d33"),
        (0x00, "00"),
        (0xFF, "ff"),
    ],
)
def test_stuff_each_reserved_byte(raw, wire):
    assert sps30.stuff(bytes([raw])).hex() == wire


def test_stuff_unstuff_roundtrip_all_bytes():
    payload = bytes(range(256))
    assert sps30.unstuff(sps30.stuff(payload)) == payload


def test_unstuff_real_serial_number_length_byte():
    # The serial-number response carries LEN 0x11, escaped on the wire as 7d31.
    inner = sps30.unstuff(bytes.fromhex(vectors.SERIAL_NUMBER[1])[1:-1])
    assert inner[3] == 0x11  # LEN un-stuffed back to 17


def test_unstuff_rejects_malformed_escape():
    with pytest.raises(sps30.SPS30Error):
        sps30.unstuff(bytes([sps30.ESC, 0x99]))  # 0x99 is not a valid escape


# --- build_frame matches real captured requests -----------------------------
@pytest.mark.parametrize(
    "cmd, data, expected",
    [
        (sps30.CMD_INFO, b"\x00", vectors.PRODUCT_TYPE[0]),
        (sps30.CMD_INFO, b"\x03", vectors.SERIAL_NUMBER[0]),
        (sps30.CMD_VERSION, b"", vectors.VERSION[0]),
        (sps30.CMD_START, b"\x01\x03", vectors.START[0]),
        (sps30.CMD_READ, b"", vectors.READ[0]),
        (sps30.CMD_STOP, b"", vectors.STOP[0]),
    ],
)
def test_build_frame_matches_hardware(cmd, data, expected):
    assert sps30.build_frame(cmd, data).hex() == expected


# --- parse_response on real captured responses ------------------------------
def _inner(response_hex):
    return sps30.unstuff(bytes.fromhex(response_hex)[1:-1])


def test_parse_product_type():
    cmd, state, data = sps30.parse_response(_inner(vectors.PRODUCT_TYPE[1]))
    assert cmd == sps30.CMD_INFO and state == 0
    assert sps30._ascii(data) == vectors.EXPECTED_PRODUCT


def test_parse_serial_number():
    cmd, state, data = sps30.parse_response(_inner(vectors.SERIAL_NUMBER[1]))
    assert sps30._ascii(data) == vectors.EXPECTED_SERIAL


def test_parse_version():
    _cmd, _state, data = sps30.parse_response(_inner(vectors.VERSION[1]))
    assert f"{data[0]}.{data[1]}" == vectors.EXPECTED_FIRMWARE


def test_parse_rejects_bad_checksum():
    good = bytearray(_inner(vectors.STOP[1]))
    good[-1] ^= 0xFF  # corrupt the checksum byte
    with pytest.raises(sps30.SPS30Error, match="checksum"):
        sps30.parse_response(bytes(good))


def test_parse_rejects_bogus_length():
    with pytest.raises(sps30.SPS30Error):
        sps30.parse_response(bytes.fromhex("00d000ff00"))  # LEN 0xff, no data


# --- decode_measurement -----------------------------------------------------
def test_decode_measurement_matches_expected():
    _cmd, _state, data = sps30.parse_response(_inner(vectors.READ[1]))
    m = sps30.decode_measurement(data)
    got = {k: getattr(m, k) for k in vectors.EXPECTED_MEASUREMENT}
    for k, want in vectors.EXPECTED_MEASUREMENT.items():
        assert got[k] == pytest.approx(want, abs=1e-3), k


def test_decode_measurement_is_big_endian():
    # A little-endian read of the same bytes would give wildly different values.
    _c, _s, data = sps30.parse_response(_inner(vectors.READ[1]))
    be = struct.unpack(">f", data[:4])[0]
    le = struct.unpack("<f", data[:4])[0]
    assert be == pytest.approx(7.0332, abs=1e-3)
    assert le != pytest.approx(7.0332, abs=1e-1)


def test_decode_measurement_none_when_not_ready():
    assert sps30.decode_measurement(b"") is None


def test_decode_measurement_rejects_short_payload():
    with pytest.raises(sps30.SPS30Error):
        sps30.decode_measurement(b"\x00" * 20)
