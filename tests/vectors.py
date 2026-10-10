"""Golden protocol vectors captured from a real SPS30.

Product type 00080000, serial E3164D2544338487, firmware 2.1. Every value here
is genuine on-the-wire traffic, so the software tests validate the codec against
the exact bytes the hardware produces.
"""

# (request_hex, response_hex) pairs, exactly as seen on the UART.
PRODUCT_TYPE = ("7e00d001002e7e", "7e00d000093030303830303030009e7e")
SERIAL_NUMBER = ("7e00d001032b7e", "7e00d0007d314533313634443235343433333834383700b77e")
VERSION = ("7e00d1002e7e", "7e00d10007020100070002001b7e")
START = ("7e0000020103f97e", "7e00000000ff7e")
READ = (
    "7e000300fc7e",
    "7e0003002840e1104b416e944a41a7ade841b15b7541faf4b2423f9364"
    "425e9588426479104265673a3f8a183ee07e",
)
STOP = ("7e000100fe7e", "7e00010000fe7e")
# Sleep/Wake (firmware >= 2.0). The SLEEP pair is the datasheet's own worked
# example (5.3.4); WAKE's 0x11 command byte lands in the byte-stuffing set and
# so travels as 0x7D 0x31.
CLEAN = ("7e005600a97e", "7e00560000a97e")
SLEEP = ("7e001000ef7e", "7e00100000ef7e")
WAKE = ("7e007d3100ee7e", "7e007d310000ee7e")

# Expected decodings.
EXPECTED_PRODUCT = "00080000"
EXPECTED_SERIAL = "E3164D2544338487"
EXPECTED_FIRMWARE = "2.1"

# The 10 floats inside the READ response, in output order.
EXPECTED_MEASUREMENT = {
    "pm1_0": 7.0332,
    "pm2_5": 14.9112,
    "pm4_0": 20.9599,
    "pm10": 22.1697,
    "nc0_5": 31.3695,
    "nc1_0": 47.8939,
    "nc2_5": 55.6460,
    "nc4_0": 57.1182,
    "nc10": 57.3508,
    "typical_size": 1.0789,
}

# The empty "no new measurement ready yet" read response (STATE 0, LEN 0).
READ_NOT_READY = ("7e000300fc7e", "7e00030000fc7e")
