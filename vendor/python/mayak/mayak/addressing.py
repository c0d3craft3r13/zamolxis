"""Where a message is sent, derived rather than announced.

## The problem this solves

A Reticulum node is found because it announces, and an announce publishes the
identity's public key in the clear to everyone in range. So the act of being
reachable is the act of being identifiable, and an address that never changes is
a name that follows its owner for the life of the install.

The way out is an address both ends can *work out* instead of being told. Two
people who have each other's public key already share a secret — the X25519
agreement between one's private key and the other's public key — without ever
having exchanged another byte. Everything here is derived from that.

## What is derived

For a pair and a moment in time, one address per direction:

    seed = HKDF-SHA256(
        secret   = X25519(our private, their public),
        salt     = epoch number,
        info     = "mayak/address/v1" || hash of the receiver's public key,
        length   = 64,
    )

Those 64 bytes are exactly the private key material a Reticulum identity is made
of — 32 for X25519, 32 for Ed25519 — so the result is a destination the transport
can carry without knowing any of this happened.

The receiver's key is in the `info`, not the sender's, and that is what keeps the
two directions apart. Without it both ends would derive one address from one
shared secret and talk over each other.

## Why the clock does not have to agree

Addresses move on an epoch boundary, and two devices are never exactly in step —
one may be minutes off, or have been switched off across the change. So three
addresses are live at any moment: the previous epoch, the current one, and the
next. A receiver listens on all three; a sender uses the current one. A message
sent seconds before a boundary still lands, and a device whose clock is an hour
fast is still reachable.

The cost is being addressable on three destinations instead of one, which costs
nothing on the wire — none of them are announced.

## What this does not claim

It does not hide *that* two parties are talking from someone who already knows
both their long-term keys: anyone holding those can derive the same addresses.
It hides the link from everyone else, and it stops one address from following a
person across months. Against a watcher who has neither key, consecutive epochs
are unrelated values.
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

#: How long an address is used before the pair moves to the next one.
#:
#: A day is a chosen default, not a derived one. Shorter means less traffic
#: gathered under any one address and more boundaries to survive; longer means
#: the opposite. It is a single constant so the trade can be made once, in the
#: open, rather than assumed in a dozen places.
EPOCH_SECONDS = 24 * 60 * 60

#: Identity key material: 32 bytes of X25519 private key, then 32 of Ed25519.
SEED_LENGTH = 64

#: Separates this use of the pairwise secret from every other use of it.
_INFO_PREFIX = b"mayak/address/v1"


class AddressError(ValueError):
    """The inputs could not produce an address, and guessing would be worse."""


@dataclass(frozen=True)
class EpochAddress:
    """Identity key material for one pair, one direction, one epoch."""

    epoch: int
    seed: bytes


def pairwise_secret(our_private: bytes, their_public: bytes) -> bytes:
    """The 32 bytes both ends can compute and nobody else can.

    Plain X25519: our private key against their public key. The peer computes
    the mirror image and lands on the same value, having sent nothing.

    :raises AddressError: on key material of the wrong size or shape. A silent
        fallback here would produce an address the peer cannot derive, and the
        symptom would be messages vanishing rather than an error anyone sees.
    """
    if len(our_private) != 32:
        raise AddressError(f"an X25519 private key is 32 bytes, not {len(our_private)}")
    if len(their_public) != 32:
        raise AddressError(f"an X25519 public key is 32 bytes, not {len(their_public)}")
    try:
        private = X25519PrivateKey.from_private_bytes(our_private)
        public = X25519PublicKey.from_public_bytes(their_public)
        return private.exchange(public)
    except Exception as bad_key:  # noqa: BLE001 - the library raises several types for the same cause
        raise AddressError("the key material is not a usable X25519 pair") from bad_key


def epoch_at(when: float | None = None) -> int:
    """Which epoch a moment falls in. Defaults to now."""
    moment = time.time() if when is None else when
    return int(moment // EPOCH_SECONDS)


def address_seed(secret: bytes, epoch: int, receiver_public: bytes) -> bytes:
    """Identity key material for messages *to* the holder of ``receiver_public``.

    Both ends call this with the same three arguments and get the same bytes:
    the sender to know where to send, the receiver to know where to listen.

    The receiver's key rather than the sender's is what separates the two
    directions of a conversation. Passing the sender's would give one address
    for both, and the two ends would collide on it.
    """
    if len(secret) != 32:
        raise AddressError(f"a pairwise secret is 32 bytes, not {len(secret)}")
    if not receiver_public:
        raise AddressError("an address needs a receiver to be addressed to")

    return HKDF(
        algorithm=hashes.SHA256(),
        length=SEED_LENGTH,
        # The epoch is the salt rather than part of the info so that two epochs
        # are independent extractions, not two labels over one extraction.
        salt=str(epoch).encode("ascii"),
        info=_INFO_PREFIX + hashlib.sha256(receiver_public).digest(),
    ).derive(secret)


def addresses_in_flight(
    secret: bytes,
    receiver_public: bytes,
    when: float | None = None,
) -> list[EpochAddress]:
    """The addresses a receiver must be listening on right now.

    Previous, current and next, because two devices are never exactly in step.
    A message sent a second before a boundary arrives after it; a device that
    was switched off across one comes back to find the world has moved.

    Returned oldest first, so a caller that registers them in order ends with
    the current epoch most recently touched.
    """
    current = epoch_at(when)
    return [
        EpochAddress(epoch=epoch, seed=address_seed(secret, epoch, receiver_public))
        for epoch in (current - 1, current, current + 1)
    ]


def sending_address(
    secret: bytes,
    receiver_public: bytes,
    when: float | None = None,
) -> EpochAddress:
    """The address to send to now.

    Only the current epoch. A sender that also tried the neighbours would
    double or triple every message for no gain — the receiver is already
    listening on all three.
    """
    epoch = epoch_at(when)
    return EpochAddress(epoch=epoch, seed=address_seed(secret, epoch, receiver_public))
