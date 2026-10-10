"""Pytest configuration: the hardware/software split and shared fixtures.

Software tests (the default) need no sensor. Hardware tests are marked
``@pytest.mark.hardware`` and only run when ``--run-hardware`` is passed, which
you do on the Raspberry Pi with a real SPS30 attached.
"""
import pytest


def pytest_addoption(parser):
    parser.addoption(
        "--run-hardware",
        action="store_true",
        default=False,
        help="run tests that require a physically connected SPS30",
    )
    parser.addoption(
        "--port",
        action="store",
        default="/dev/serial0",
        help="serial port of the SPS30 (hardware tests)",
    )


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "hardware: requires a physically connected SPS30 sensor"
    )


def pytest_collection_modifyitems(config, items):
    if config.getoption("--run-hardware"):
        return
    skip = pytest.mark.skip(reason="needs --run-hardware and a connected SPS30")
    for item in items:
        if "hardware" in item.keywords:
            item.add_marker(skip)


class FakeSerial:
    """A minimal serial stand-in that plays back canned responses.

    Built from a {request_bytes: response_bytes} map. When the driver writes a
    request frame, the matching response is queued for it to read back - so the
    full driver stack is exercised with zero hardware.
    """

    def __init__(self, responses, enforce_state=False):
        self.responses = {bytes.fromhex(k): bytes.fromhex(v) for k, v in responses.items()}
        self._rx = bytearray()
        self.written = []
        # Opt-in device-FSM modelling; see _state_error below.
        self.enforce_state = enforce_state
        self.measuring = False

    def reset_input_buffer(self):
        self._rx.clear()

    def write(self, data):
        data = bytes(data)
        self.written.append(data)
        if self.enforce_state:
            err = self._state_error(data)
            if err is not None:
                self._rx.extend(err)
                return len(data)
            self._track_state(data)
        if data in self.responses:
            self._rx.extend(self.responses[data])
        return len(data)

    # The real SPS30 refuses commands that do not suit its current mode - Start
    # while already measuring answers 0x43 "command not allowed in current
    # state". Replaying canned responses hides that, so `enforce_state` models
    # just enough of the device FSM to catch it (a real bug found on hardware).
    _START = bytes.fromhex("7e0000020103f97e")
    _STOP = bytes.fromhex("7e000100fe7e")
    _SLEEP = bytes.fromhex("7e001000ef7e")
    _WAKE = bytes.fromhex("7e007d3100ee7e")
    # addr 00, cmd echo, state 0x43, len 0, checksum.
    _ERR_START = bytes.fromhex("7e00004300bc7e")
    _ERR_SLEEP = bytes.fromhex("7e00104300ac7e")

    def _state_error(self, data):
        if data == self._START and self.measuring:
            return self._ERR_START
        if data == self._SLEEP and self.measuring:
            return self._ERR_SLEEP  # Sleep is only legal from idle
        return None

    def _track_state(self, data):
        if data == self._START:
            self.measuring = True
        elif data in (self._STOP, self._SLEEP):
            self.measuring = False

    def flush(self):
        # The wake sequence flushes after its raw 0xFF pulse; nothing to do here.
        pass

    def read(self, n=1):
        out = bytes(self._rx[:n])
        del self._rx[: len(out)]
        return out

    def close(self):
        pass


@pytest.fixture
def fake_serial_factory(monkeypatch):
    """Return a factory that patches serial.Serial to a FakeSerial and opens SPS30."""
    import sps30

    def make(responses):
        fake = FakeSerial(responses)
        monkeypatch.setattr(sps30.serial, "Serial", lambda *a, **k: fake)
        sensor = sps30.SPS30(port="fake")
        return sensor, fake

    return make


@pytest.fixture
def sensor(request):
    """A live SPS30 on real hardware (hardware tests only)."""
    import sps30

    port = request.config.getoption("--port")
    dev = sps30.SPS30(port=port)
    yield dev
    dev.close()
