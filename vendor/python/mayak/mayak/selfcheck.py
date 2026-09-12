"""The whole protocol, once, on whatever device is running it.

## What this is for

Every module has its own tests, and those tests run on a laptop. A phone is a
different machine: a different `cryptography`, a different ML-KEM, a different
Argon2id, a Python built by someone else. Each substitution was checked on its
own. This checks that they compose — that a conversation actually happens with
the pieces this device really has.

It is deliberately the same function on both: the desktop suite calls it, and so
does the instrumented test on the phone. A self-check that differed between them
would be checking two things and reporting one.

## What it does not touch

The network. The transport is the in-process loopback, so this says nothing
about Reticulum and cannot put a packet on the air. The store is a real
encrypted file, in a temporary directory under the one the caller names, and
removed afterwards.
"""

from __future__ import annotations

import os
import tempfile
from pathlib import Path

from mayak.device import new_keys
from mayak.kem import HybridKem, Kem, X25519Kem
from mayak.mechanisms import post_quantum_mechanism
from mayak.node import Node
from mayak.store import ITERATIONS, KEY_LENGTH, LANES, MEMORY_KIB, EncryptedStore
from mayak.stretch import argon2id
from mayak.transport import LoopbackTransport, Switchboard

#: Argon2id at the store's parameters for a fixed passphrase and salt, agreed by
#: `cryptography` and the reference C before it was written here. A device whose
#: Argon2id gives anything else cannot open files made anywhere else.
_ARGON2_KNOWN = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"

#: One packet's worth, as on Reticulum, so the long message really is split.
_BODY = 352


class SelfCheckFailed(AssertionError):
    """The protocol did not work end to end on this device."""


def run(directory: str | None = None) -> dict[str, str]:
    """Run the protocol end to end and report what this device used.

    Raises :class:`SelfCheckFailed` on the first thing that does not hold.
    Returns which ML-KEM and which Argon2id the device really has, so nobody
    has to guess.
    """
    report: dict[str, str] = {}

    quantum = post_quantum_mechanism()
    if quantum is None:
        raise SelfCheckFailed("no ML-KEM-768 on this device; the protocol refuses to seal without one")
    report["kem"] = f"{quantum.name} from {_kem_source(quantum)}"

    derived = argon2id(
        b"correct horse battery staple",
        bytes(range(16)),
        length=KEY_LENGTH,
        iterations=ITERATIONS,
        lanes=LANES,
        memory_kib=MEMORY_KIB,
    )
    if derived.hex() != _ARGON2_KNOWN:
        raise SelfCheckFailed("Argon2id on this device does not give the known answer")
    report["argon2id"] = _argon2_source()

    with tempfile.TemporaryDirectory(dir=directory) as workspace:
        _conversation(HybridKem(X25519Kem(), quantum), Path(workspace))

    report["conversation"] = "ok"
    return report


def _conversation(kem: Kem, workspace: Path) -> None:
    switchboard = Switchboard()
    inbox: dict[str, list[bytes]] = {"alice": [], "bob": []}
    passphrase = os.urandom(16).hex().encode()
    alice_keys, bob_keys = new_keys(kem), new_keys(kem)

    def node(name: str, keys) -> Node:
        return Node(
            kem,
            keys,
            lambda: LoopbackTransport(switchboard),
            _BODY,
            EncryptedStore(workspace / f"{name}.mayak", passphrase),
            lambda contact, message, who=name: inbox[who].append(message.content),
            store_keys=True,
        )

    alice, bob = node("alice", alice_keys), node("bob", bob_keys)
    alice.add_contact("Bob", bob_keys.public_key, bob_keys.kem_public_key)
    bob.add_contact("Alice", alice_keys.public_key, alice_keys.kem_public_key)
    alice.listen()
    bob.listen()

    short = "принято, 灯台 🔦".encode()
    long = os.urandom(900)

    alice.send(bob_keys.public_key, short)
    alice.send(bob_keys.public_key, long)
    bob.send(alice_keys.public_key, b"understood")

    if inbox["bob"] != [short, long]:
        raise SelfCheckFailed(f"bob received {len(inbox['bob'])} of 2 messages intact")
    if inbox["alice"] != [b"understood"]:
        raise SelfCheckFailed("the reply did not arrive")

    # A store that cannot be reopened is a store that loses every contact.
    reopened = Node(
        kem,
        bob_keys,
        lambda: LoopbackTransport(switchboard),
        _BODY,
        EncryptedStore(workspace / "bob.mayak", passphrase),
        lambda contact, message: None,
    )
    if [contact.name for contact in reopened.contacts] != ["Alice"]:
        raise SelfCheckFailed("the encrypted store did not survive being reopened")

    bob.wipe()
    if (workspace / "bob.mayak").exists():
        raise SelfCheckFailed("wiping left the store behind")


def _kem_source(quantum: Kem) -> str:
    # Both implementations share the name "ml-kem-768" on purpose — they are
    # interchangeable — so the name cannot say which one this is.
    if type(quantum).__module__.endswith("mlkem_native"):
        return "native C (mlkem-native)"
    return "cryptography"


def _argon2_source() -> str:
    try:
        from cryptography.hazmat.primitives.kdf.argon2 import Argon2id  # noqa: F401
    except ImportError:
        return "argon2-cffi"
    return "cryptography"
