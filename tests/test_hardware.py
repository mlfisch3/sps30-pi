"""Hardware-in-the-loop tests: run against a real SPS30 on the Pi.

    pytest --run-hardware                 # uses /dev/serial0
    pytest --run-hardware --port /dev/ttyUSB0

These confirm that the sensor is wired correctly and communicating: every one
of these passing means real frames were exchanged with valid checksums.
"""
import re
import time

import pytest

import sps30

pytestmark = pytest.mark.hardware


def test_identity(sensor):
    assert sensor.product_type() == "00080000"
    assert re.fullmatch(r"[0-9A-F]{16}", sensor.serial_number())
    assert re.fullmatch(r"\d+\.\d+", sensor.firmware_version())


def test_measurement_is_plausible(sensor):
    sensor.start_measurement()
    try:
        m = None
        deadline = time.monotonic() + 10
        while m is None and time.monotonic() < deadline:
            m = sensor.read_measurement()
            if m is None:
                time.sleep(0.2)
        assert m is not None, "no measurement became ready within 10 s"
        # Physically sane bounds for an SPS30.
        for field in ("pm1_0", "pm2_5", "pm4_0", "pm10"):
            assert 0.0 <= getattr(m, field) <= 1000.0, field
        assert m.pm2_5 >= m.pm1_0 - 1e-6  # cumulative mass is monotonic
        assert m.pm10 >= m.pm2_5 - 1e-6
        assert 0.0 <= m.typical_size <= 10.0
    finally:
        sensor.stop_measurement()


def test_repeated_reads_all_valid(sensor):
    # Every successful read implies a valid frame + checksum; loop to catch flakiness.
    sensor.start_measurement()
    try:
        time.sleep(2)
        for _ in range(10):
            sensor.read_measurement()  # raises on any framing/checksum error
            time.sleep(0.2)
    finally:
        sensor.stop_measurement()
