"""
Regression tests for the BLE identity-handshake churn fixes.

Two behaviours under test, both in BLEInterface:

1. A 16-byte frame on an ESTABLISHED connection that does not match the
   known peer identity is real data, not a handshake. Consuming it used to
   silently corrupt the transfer the reassembler was waiting for.

2. Rejecting a duplicate-identity connection blacklists the rejected
   ADDRESS (never the healthy one) for a bounded window, breaking the
   connect -> handshake -> reject -> disconnect loop that produced
   connection churn next to link handshakes.

Runs with plain unittest (no pytest, no RNS install): RNS is stubbed in
sys.modules before BLEInterface is imported.
"""

import sys
import threading
import time
import types
import unittest
from pathlib import Path


def _install_rns_stub():
    rns = types.ModuleType("RNS")
    rns.LOG_CRITICAL = 0
    rns.LOG_ERROR = 1
    rns.LOG_WARNING = 2
    rns.LOG_INFO = 3
    rns.LOG_DEBUG = 4
    rns.LOG_EXTREME = 5  # values don't matter; RNS.log ignores them
    rns.log = lambda *args, **kwargs: None

    interfaces_pkg = types.ModuleType("RNS.Interfaces")
    interface_mod = types.ModuleType("RNS.Interfaces.Interface")

    class Interface:
        def __init__(self, *args, **kwargs):
            pass

    interface_mod.Interface = Interface
    interfaces_pkg.Interface = interface_mod
    rns.Interfaces = interfaces_pkg

    sys.modules["RNS"] = rns
    sys.modules["RNS.Interfaces"] = interfaces_pkg
    sys.modules["RNS.Interfaces.Interface"] = interface_mod


_install_rns_stub()

SRC_DIR = Path(__file__).resolve().parent.parent / "src" / "ble_reticulum"
sys.path.insert(0, str(SRC_DIR))

import BLEInterface as ble  # noqa: E402


class FakeDriver:
    """Minimal driver stand-in recording disconnect calls."""

    def __init__(self, role="peripheral", mtu=512):
        self.role = role
        self.mtu = mtu
        self.connected_peers = {}
        self.disconnected = []

    def get_peer_role(self, address):
        return self.role

    def get_peer_mtu(self, address):
        return self.mtu

    def disconnect(self, address):
        self.disconnected.append(address)
        self.connected_peers.pop(address, None)


def make_interface():
    """Build a BLEInterface without running __init__ (no threads, no driver)."""
    iface = ble.BLEInterface.__new__(ble.BLEInterface)
    iface.name = "TestBLE"
    iface.address_to_identity = {}
    iface.address_to_interface = {}
    iface.identity_to_address = {}
    iface.spawned_interfaces = {}
    iface.peers = {}
    iface.peer_lock = threading.Lock()
    iface.frag_lock = threading.Lock()
    iface.fragmenters = {}
    iface.reassemblers = {}
    iface.pending_mtu = {}
    iface._pending_detach = {}
    iface._pending_detach_grace_period = 2.0
    iface._pending_identity_connections = {}
    iface._pending_identity_timeout = 30
    iface._identity_cache = {}
    iface._identity_cache_ttl = 60
    iface._last_real_data = {}
    iface._zombie_timeout = 30.0
    iface._duplicate_reject_backoff = 60.0
    iface.connection_blacklist = {}
    iface.discovered_peers = {}
    iface.driver = FakeDriver()
    return iface


IDENTITY = bytes(range(16))
IDENTITY_HASH = IDENTITY.hex()[:16]
OLD_ADDR = "11:22:33:44:55:66"
NEW_ADDR = "66:55:44:33:22:11"


class SixteenByteFrameTests(unittest.TestCase):
    def test_mismatched_16_bytes_on_established_connection_is_data(self):
        iface = make_interface()
        iface.address_to_identity[OLD_ADDR] = IDENTITY
        received = []
        iface._handle_ble_data = lambda a, d: received.append((a, d))

        payload = b"\x5a" * 16  # 16 bytes, but NOT the peer identity
        iface._data_received_callback(OLD_ADDR, payload)

        self.assertEqual(received, [(OLD_ADDR, payload)])

    def test_duplicate_handshake_still_consumed(self):
        iface = make_interface()
        iface.address_to_identity[OLD_ADDR] = IDENTITY
        received = []
        iface._handle_ble_data = lambda a, d: received.append((a, d))

        iface._data_received_callback(OLD_ADDR, IDENTITY)

        self.assertEqual(received, [])

    def test_fresh_handshake_registers_identity(self):
        iface = make_interface()
        spawned = []
        iface._spawn_peer_interface = lambda **kw: spawned.append(kw)

        iface._data_received_callback(NEW_ADDR, IDENTITY)

        self.assertEqual(iface.address_to_identity[NEW_ADDR], IDENTITY)
        assert iface.identity_to_address[IDENTITY_HASH] == NEW_ADDR
        self.assertIn(IDENTITY_HASH, iface._last_real_data)
        self.assertEqual(len(spawned), 1)
        self.assertNotIn(NEW_ADDR, iface._pending_identity_connections)


class DuplicateRejectBackoffTests(unittest.TestCase):
    def _healthy_existing_connection(self, iface):
        iface.identity_to_address[IDENTITY_HASH] = OLD_ADDR
        iface.address_to_identity[OLD_ADDR] = IDENTITY
        iface.peers[OLD_ADDR] = object()
        iface.driver.connected_peers[OLD_ADDR] = True
        iface._last_real_data[IDENTITY_HASH] = time.time()

    def test_reject_blacklists_only_the_new_address(self):
        iface = make_interface()
        self._healthy_existing_connection(iface)

        rejected = iface._check_duplicate_identity(NEW_ADDR, IDENTITY)

        self.assertTrue(rejected)
        self.assertIn(NEW_ADDR, iface.connection_blacklist)
        self.assertNotIn(OLD_ADDR, iface.connection_blacklist)

    def test_handshake_path_disconnects_and_blacklists_duplicate(self):
        iface = make_interface()
        self._healthy_existing_connection(iface)
        spawned = []
        iface._spawn_peer_interface = lambda **kw: spawned.append(kw)

        iface._data_received_callback(NEW_ADDR, IDENTITY)

        self.assertEqual(iface.driver.disconnected, [NEW_ADDR])
        self.assertIn(NEW_ADDR, iface.connection_blacklist)
        self.assertEqual(spawned, [])  # no interface for the rejected connection

    def test_zombie_existing_connection_still_replaced(self):
        iface = make_interface()
        self._healthy_existing_connection(iface)
        # ... but with no real data for longer than the zombie timeout
        iface._last_real_data[IDENTITY_HASH] = time.time() - 60.0

        rejected = iface._check_duplicate_identity(NEW_ADDR, IDENTITY)

        self.assertFalse(rejected)  # new connection allowed to replace the zombie
        self.assertEqual(iface.driver.disconnected, [OLD_ADDR])  # zombie cleaned up
        self.assertNotIn(NEW_ADDR, iface.connection_blacklist)

    def test_new_identity_is_allowed_and_not_blacklisted(self):
        iface = make_interface()
        self._healthy_existing_connection(iface)
        other_identity = bytes(reversed(range(16)))

        rejected = iface._check_duplicate_identity(NEW_ADDR, other_identity)

        self.assertFalse(rejected)
        self.assertEqual(iface.connection_blacklist, {})


if __name__ == "__main__":
    unittest.main()
