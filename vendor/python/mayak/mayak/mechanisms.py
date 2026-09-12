"""Which post-quantum implementation this device actually has.

## Two implementations, one protocol

ML-KEM-768 reaches the protocol in one of two ways:

- :class:`mayak.mlkem.MlKem768` — `cryptography` 47 or later, AWS-LC underneath.
  The desktop.
- :class:`mayak.mlkem_native.MlKem768Native` — our own build of mlkem-native,
  loaded with ctypes. Android, where Chaquopy's `cryptography` is 42.0.8.

They are checked against each other in both directions on every test run and on
the phone itself, so which one a device ends up with changes nothing about who
it can talk to.

## Why the order is what it is

`cryptography` first where it exists: a maintained wheel with no build step on
this machine is less to go wrong than a library someone had to compile. The
native build second, which on Android is the only one there.

## Why this is its own module

It used to live in the command line. The Android app has to make the same choice,
and two copies of "which KEM do we have" is two answers the day one of them is
edited.
"""

from __future__ import annotations

from mayak.kem import HybridKem, Kem, X25519Kem


def post_quantum_mechanism() -> Kem | None:
    """The post-quantum half, if this build has one.

    Imports rather than a registry: an implementation either is installed or is
    not, and a registry would let something declare itself post-quantum without
    anybody having decided it was.
    """
    try:
        from mayak.mlkem import MlKem768
    except ImportError:
        pass
    else:
        return MlKem768()

    try:
        from mayak.mlkem_native import MlKem768Native

        return MlKem768Native()
    except ImportError:
        return None


def hybrid_mechanism() -> HybridKem | None:
    """X25519 with whichever ML-KEM this device has, or None if it has neither."""
    quantum = post_quantum_mechanism()
    if quantum is None:
        return None
    return HybridKem(X25519Kem(), quantum)
