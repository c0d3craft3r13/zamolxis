"""What a device is when it is switched off: its own keys, and who it knows.

## Why the keys live in the same file as the contacts

:mod:`mayak.book` puts a contact book in a store. That was enough while
something else held the device's own keypairs — and nothing did. Every caller
would have had to invent its own place to keep them, and the first thing each
would have got wrong is the one that matters:

**A wipe has to destroy the keys.** Two files means two wipes, and the second
one is the one somebody forgets. Keys in a separate file outlive the wipe of the
contact book, and a device that still holds its identity key after being wiped
has not been wiped — every address it ever used can be re-derived from it the
moment a contact's key turns up.

So one file holds both. They have the same secrecy requirement, the same
lifetime, and the same moment of destruction.

## Keys are optional in the file, on purpose

A device whose keys live in a platform keystore — Android's, a smartcard, an
HSM — must not have them copied into a file as a side effect of saving a
contact. So the layout marks their presence with a byte, and
:class:`mayak.node.Node` writes them back only when that is where they came
from.

## The format

    version (1) || keys present (1)
    if present:  identity private (2 + n) || identity public (2 + n)
              || kem private (2 + n)      || kem public (2 + n)
    book length (4) || book bytes

The book is delegated to :mod:`mayak.book` rather than re-parsed here, and it is
length-prefixed so that module can keep refusing trailing bytes — it is handed
exactly its own slice and nothing else.

The field helpers are this module's own rather than shared with
:mod:`mayak.book`. Two file formats that share a parser are two file formats
where a change to one silently changes the other, and eight lines of
duplication is a cheaper price than that.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from mayak import book as book_format
from mayak.book import BookFormatError
from mayak.contacts import ContactBook
from mayak.kem import Kem
from mayak.session import Us
from mayak.store import Store

#: This module's own format marker, separate from the store's and the book's.
LAYOUT_VERSION = 1

_MAX_FIELD = 0xFFFF
_KEYS_ABSENT = 0
_KEYS_PRESENT = 1


class DeviceFormatError(ValueError):
    """The saved bytes are not a device this build can read."""


@dataclass(frozen=True)
class Device:
    """Everything one device keeps between runs."""

    #: None when the keys are held somewhere this file does not reach.
    us: Us | None
    book: ContactBook


def new_keys(kem: Kem) -> Us:
    """Generate a fresh identity and a fresh encapsulation keypair.

    Two independent pairs, because they are replaced for different reasons —
    see :mod:`mayak.contacts`. Nothing here derives one from the other, so
    rotating the cheap one cannot be made to disturb the expensive one.
    """
    identity = X25519PrivateKey.generate()
    kem_private, kem_public = kem.generate()
    return Us(
        private_key=identity.private_bytes_raw(),
        public_key=identity.public_key().public_bytes_raw(),
        kem_private_key=kem_private,
        kem_public_key=kem_public,
    )


def _field(value: bytes) -> bytes:
    if len(value) > _MAX_FIELD:
        raise DeviceFormatError(f"a field of {len(value)} bytes exceeds the {_MAX_FIELD} the format allows")
    return struct.pack(">H", len(value)) + value


def _take_field(raw: bytes, at: int) -> tuple[bytes, int]:
    if at + 2 > len(raw):
        raise DeviceFormatError("the device ends in the middle of a field length")
    (length,) = struct.unpack(">H", raw[at : at + 2])
    end = at + 2 + length
    if end > len(raw):
        raise DeviceFormatError("the device claims a field longer than what is left of it")
    return raw[at + 2 : end], end


def serialise(device: Device) -> bytes:
    """Turn a device into bytes for a :class:`mayak.store.Store`."""
    out = bytearray([LAYOUT_VERSION])

    if device.us is None:
        out.append(_KEYS_ABSENT)
    else:
        out.append(_KEYS_PRESENT)
        for value in (
            device.us.private_key,
            device.us.public_key,
            device.us.kem_private_key,
            device.us.kem_public_key,
        ):
            out += _field(value)

    book = book_format.serialise(device.book)
    out += struct.pack(">I", len(book)) + book
    return bytes(out)


def deserialise(raw: bytes) -> Device:
    """Rebuild a device, or refuse.

    Refusing rather than repairing, for the same reason the contact book does:
    a device file that loses half its contacts silently is a set of people whose
    messages stop arriving with no error anywhere.
    """
    if len(raw) < 6:
        raise DeviceFormatError("too short to be a device")

    if raw[0] != LAYOUT_VERSION:
        raise DeviceFormatError(f"device layout {raw[0]} is not layout {LAYOUT_VERSION}")

    marker = raw[1]
    if marker not in (_KEYS_ABSENT, _KEYS_PRESENT):
        raise DeviceFormatError(f"unknown key marker {marker}")

    at = 2
    us: Us | None = None
    if marker == _KEYS_PRESENT:
        identity_private, at = _take_field(raw, at)
        identity_public, at = _take_field(raw, at)
        kem_private, at = _take_field(raw, at)
        kem_public, at = _take_field(raw, at)
        if not identity_private or not identity_public or not kem_private or not kem_public:
            raise DeviceFormatError("the device claims to hold keys and one of them is empty")
        us = Us(
            private_key=identity_private,
            public_key=identity_public,
            kem_private_key=kem_private,
            kem_public_key=kem_public,
        )

    if at + 4 > len(raw):
        raise DeviceFormatError("the device ends before its contact book")
    (length,) = struct.unpack(">I", raw[at : at + 4])
    at += 4
    if at + length != len(raw):
        # Not "at + length > len(raw)": a book shorter than the rest of the file
        # means something was appended, and ignoring it would let anything ride
        # along unnoticed.
        raise DeviceFormatError("the device and its contact book disagree about where the file ends")

    try:
        book = book_format.deserialise(raw[at : at + length])
    except BookFormatError as broken:
        raise DeviceFormatError(f"the device holds something that is not a contact book: {broken}") from broken

    return Device(us=us, book=book)


def load(store: Store) -> Device | None:
    """Read a device, or None if nothing has been saved yet."""
    raw = store.load()
    if raw is None:
        return None
    return deserialise(raw)


def save(store: Store, device: Device) -> None:
    """Write a device, replacing whatever was there."""
    store.save(serialise(device))
