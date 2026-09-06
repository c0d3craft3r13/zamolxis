"""Regression tests for the `_pending_identities` cache in
`ble_modules/android_ble_driver.py`.

The cache bridges the race between onIdentityReceived and onConnected for
peripheral-mode peers. Before the TTL fix, a peer whose connection never
completed (the usual end of a connect/reject churn cycle under Android MAC
rotation) left its entry in the dict forever — one entry per rotated MAC
address, growing for the whole uptime of the app.

These tests load the driver module standalone (same stub pattern as
test_android_ble_driver_startup.py) and pin down:
  1. the happy path — identity cached, then consumed by _handle_connected;
  2. TTL eviction — a stale entry is evicted when the next identity is cached;
  3. the hard bound — the cache never exceeds _PENDING_IDENTITY_MAX;
  4. the data-arrived-first path — _handle_data_received consumes (not peeks)
     the pending entry.
"""

import importlib.util
import sys
import time
import types
import unittest
from enum import Enum
from pathlib import Path


DRIVER_PATH = (
    Path(__file__).resolve().parents[2]
    / "main/python/ble_modules/android_ble_driver.py"
)


class DriverState(Enum):
    IDLE = "idle"
    SCANNING = "scanning"
    ADVERTISING = "advertising"


class BLEDriverInterface:
    def __init__(self):
        self.on_error = None


def hex_identity(byte: int) -> str:
    """A valid 16-byte identity hex string, distinct per `byte`."""
    return f"{byte:02x}" * 16


class PendingIdentityCacheTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 7)
        setattr(rns, "LOG_INFO", 6)
        setattr(rns, "LOG_WARNING", 4)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_EXTREME", 8)
        setattr(rns, "log", lambda *args, **kwargs: None)
        sys.modules["RNS"] = rns

        bluetooth_driver = types.ModuleType("bluetooth_driver")
        setattr(bluetooth_driver, "BLEDriverInterface", BLEDriverInterface)
        setattr(bluetooth_driver, "BLEDevice", object)
        setattr(bluetooth_driver, "DriverState", DriverState)
        sys.modules["bluetooth_driver"] = bluetooth_driver

        spec = importlib.util.spec_from_file_location(
            "android_ble_driver_pending_identities_test", DRIVER_PATH
        )
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls.Driver = module.AndroidBLEDriver
        cls.module = module

    def new_driver(self):
        return self.Driver()

    def test_pending_identity_consumed_on_connect(self):
        driver = self.new_driver()
        driver._handle_identity_received("AA:01", hex_identity(0x01))
        self.assertIn("AA:01", driver._pending_identities)

        driver._handle_connected("AA:01", mtu=185, role="peripheral")

        self.assertNotIn("AA:01", driver._pending_identities)
        self.assertEqual(
            driver._address_to_identity.get("AA:01"), hex_identity(0x01)
        )

    def test_stale_entry_evicted_on_next_insert(self):
        driver = self.new_driver()
        # Simulate an identity cached long ago whose connection never came.
        driver._pending_identities["AA:OLD"] = (
            bytes.fromhex(hex_identity(0x0A)),
            time.monotonic() - (self.module._PENDING_IDENTITY_TTL_S + 1),
        )

        driver._handle_identity_received("AA:NEW", hex_identity(0x02))

        self.assertNotIn("AA:OLD", driver._pending_identities)
        self.assertIn("AA:NEW", driver._pending_identities)

    def test_fresh_entry_survives_insert_of_others(self):
        driver = self.new_driver()
        driver._handle_identity_received("AA:FRESH", hex_identity(0x03))
        driver._handle_identity_received("AA:OTHER", hex_identity(0x04))
        self.assertIn("AA:FRESH", driver._pending_identities)
        self.assertIn("AA:OTHER", driver._pending_identities)

    def test_cache_hard_bound(self):
        driver = self.new_driver()
        limit = self.module._PENDING_IDENTITY_MAX
        for i in range(limit + 10):
            driver._handle_identity_received(f"BB:{i:03d}", hex_identity(i % 256))
        self.assertLessEqual(len(driver._pending_identities), limit)
        # Oldest entries were the ones evicted (insertion order).
        self.assertNotIn("BB:000", driver._pending_identities)
        self.assertIn(f"BB:{limit + 9:03d}", driver._pending_identities)

    def test_data_received_consumes_pending_entry(self):
        driver = self.new_driver()
        connected = []
        driver.on_device_connected = lambda addr, ident: connected.append(addr)
        # No reassembler exists in this standalone harness — data delivery
        # past the pending-identity handling is not under test here.
        driver.on_data_received = lambda addr, data: None
        driver._handle_identity_received("CC:01", hex_identity(0x05))

        driver._handle_data_received("CC:01", b"\x00")

        self.assertNotIn("CC:01", driver._pending_identities)
        self.assertEqual(connected, ["CC:01"])
        self.assertIn("CC:01", driver._connected_peers)


if __name__ == "__main__":
    unittest.main()
