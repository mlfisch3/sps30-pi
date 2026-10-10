"""Software integration tests: the full driver stack against a FakeSerial.

No hardware - the FakeSerial replays real captured responses, so this exercises
write -> frame -> read -> unstuff -> parse -> decode end to end.
"""
import pytest

import sps30
from tests import vectors


def test_info_commands(fake_serial_factory):
    sensor, _ = fake_serial_factory(
        dict([vectors.PRODUCT_TYPE, vectors.SERIAL_NUMBER, vectors.VERSION])
    )
    assert sensor.product_type() == vectors.EXPECTED_PRODUCT
    assert sensor.serial_number() == vectors.EXPECTED_SERIAL
    assert sensor.firmware_version() == vectors.EXPECTED_FIRMWARE


def test_measurement_cycle(fake_serial_factory):
    sensor, fake = fake_serial_factory(
        dict([vectors.START, vectors.READ, vectors.STOP])
    )
    sensor.start_measurement()
    m = sensor.read_measurement()
    sensor.stop_measurement()

    assert m is not None
    assert m.pm1_0 == pytest.approx(vectors.EXPECTED_MEASUREMENT["pm1_0"], abs=1e-3)
    assert m.typical_size == pytest.approx(
        vectors.EXPECTED_MEASUREMENT["typical_size"], abs=1e-3
    )
    # The exact request bytes went out on the wire, in order.
    assert fake.written[0].hex() == vectors.START[0]
    assert fake.written[-1].hex() == vectors.STOP[0]


def test_read_returns_none_when_not_ready(fake_serial_factory):
    sensor, _ = fake_serial_factory(dict([vectors.READ_NOT_READY]))
    assert sensor.read_measurement() is None


def test_command_echo_mismatch_raises(fake_serial_factory):
    # Answer a READ request with a STOP-shaped response (wrong CMD echo).
    sensor, _ = fake_serial_factory({vectors.READ[0]: vectors.STOP[1]})
    with pytest.raises(sps30.SPS30Error, match="echo mismatch"):
        sensor.read_measurement()


def test_device_error_state_raises(fake_serial_factory):
    # Start request answered with STATE 0x43 (command not allowed in state).
    sensor, _ = fake_serial_factory({vectors.START[0]: "7e00004300bc7e"})
    with pytest.raises(sps30.SPS30Error, match="0x43"):
        sensor.start_measurement()


def test_timeout_when_no_response(fake_serial_factory):
    # Empty response map -> nothing to read -> transport timeout.
    sensor, _ = fake_serial_factory({})
    with pytest.raises(sps30.SPS30Error, match="timeout"):
        sensor.product_type()


def test_transceive_raw_passes_bytes_through_with_delimiters(fake_serial_factory):
    # Raw pass-through: request goes out verbatim, one full framed response
    # comes back INCLUDING the 0x7e delimiters and byte-stuffing intact.
    sensor, fake = fake_serial_factory(
        {vectors.PRODUCT_TYPE[0]: vectors.PRODUCT_TYPE[1]}
    )
    req = bytes.fromhex(vectors.PRODUCT_TYPE[0])
    resp = sensor.transceive_raw(req)

    assert resp.hex() == vectors.PRODUCT_TYPE[1]
    assert resp[0] == sps30.START and resp[-1] == sps30.STOP
    assert fake.written[-1] == req  # bytes went out verbatim


def test_transceive_raw_times_out_without_response(fake_serial_factory):
    sensor, _ = fake_serial_factory({})
    with pytest.raises(sps30.SPS30Error, match="timeout"):
        sensor.transceive_raw(bytes.fromhex(vectors.PRODUCT_TYPE[0]))
