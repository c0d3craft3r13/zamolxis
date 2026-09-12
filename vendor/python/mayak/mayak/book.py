"""Putting a contact book on disk, and taking it off again.

## Why the serialisation is its own module

:mod:`mayak.contacts` holds contacts and decides what they mean; :mod:`mayak.store`
holds bytes and decides who can read them. Neither should know about the other:
a contact book that knew its file format could not be kept in a platform
keystore instead, and a store that knew about contacts could not hold anything
else.

This is the join, and it is deliberately the only place that knows both.

## The format

Length-prefixed fields, fixed order, no delimiters:

    count (2)
    per contact:  name (2 + bytes) || identity (2 + bytes) || kem (2 + bytes) || trust (1)

No JSON, no pickle, no key-value text. Pickle executes what it reads, which on a
file an adversary can edit is a remote code execution waiting for a passphrase
to leak. Text formats need escaping, and escaping is where parsers disagree —
two implementations that read the same file differently is how a contact ends up
with somebody else's key.

Lengths are two bytes because a name that needs more than sixty-five thousand
characters is not a name, and refusing it here is cheaper than discovering it as
a corrupt file later.

## What round-trips and what does not

Trust survives, because whether a key was checked in person is exactly the thing
worth persisting — a contact book that forgot it would quietly turn verified
contacts back into unverified ones on every restart, and nobody would notice
until it mattered.
"""

from __future__ import annotations

import struct

from mayak.contacts import Contact, ContactBook, ContactError, Trust
from mayak.store import Store

#: Format marker, separate from the store's own: the store says "this is a
#: Mayak file", this says "the contents are a contact book of this shape".
LAYOUT_VERSION = 1

_MAX_FIELD = 0xFFFF


class BookFormatError(ValueError):
    """The saved bytes are not a contact book this build can read."""


_TRUST_TO_BYTE = {Trust.UNVERIFIED: 0, Trust.VERIFIED: 1, Trust.CHANGED: 2}
_BYTE_TO_TRUST = {value: trust for trust, value in _TRUST_TO_BYTE.items()}


def _field(value: bytes) -> bytes:
    if len(value) > _MAX_FIELD:
        raise BookFormatError(f"a field of {len(value)} bytes exceeds the {_MAX_FIELD} the format allows")
    return struct.pack(">H", len(value)) + value


def _take_field(raw: bytes, at: int) -> tuple[bytes, int]:
    if at + 2 > len(raw):
        raise BookFormatError("the book ends in the middle of a field length")
    (length,) = struct.unpack(">H", raw[at : at + 2])
    end = at + 2 + length
    if end > len(raw):
        raise BookFormatError("the book claims a field longer than what is left of it")
    return raw[at + 2 : end], end


def serialise(book: ContactBook) -> bytes:
    """Turn a contact book into bytes for a :class:`mayak.store.Store`."""
    contacts = list(book)
    if len(contacts) > _MAX_FIELD:
        raise BookFormatError(f"{len(contacts)} contacts exceeds the {_MAX_FIELD} the format allows")

    out = bytearray(struct.pack(">BH", LAYOUT_VERSION, len(contacts)))
    for contact in contacts:
        out += _field(contact.name.encode("utf-8"))
        out += _field(contact.identity_key)
        out += _field(contact.kem_key)
        out += bytes([_TRUST_TO_BYTE[contact.trust]])
    return bytes(out)


def deserialise(raw: bytes) -> ContactBook:
    """Rebuild a contact book, or refuse.

    Every refusal is a refusal to guess. A book that is truncated, or that
    claims more contacts than it holds, or that carries a trust value this build
    does not know, is not repaired into something plausible — a contact book
    that silently loses an entry is a contact whose messages stop arriving with
    no error anywhere.
    """
    if len(raw) < 3:
        raise BookFormatError("too short to be a contact book")

    version, count = struct.unpack(">BH", raw[:3])
    if version != LAYOUT_VERSION:
        raise BookFormatError(f"contact book layout {version} is not layout {LAYOUT_VERSION}")

    book = ContactBook()
    at = 3
    for _ in range(count):
        name, at = _take_field(raw, at)
        identity, at = _take_field(raw, at)
        kem, at = _take_field(raw, at)
        if at >= len(raw):
            raise BookFormatError("the book ends before a contact's trust")
        trust_value = raw[at]
        at += 1

        if trust_value not in _BYTE_TO_TRUST:
            raise BookFormatError(f"unknown trust value {trust_value}")

        try:
            book.add(
                Contact(
                    name=name.decode("utf-8"),
                    identity_key=identity,
                    kem_key=kem,
                    trust=_BYTE_TO_TRUST[trust_value],
                ),
            )
        except (ContactError, UnicodeDecodeError) as broken:
            raise BookFormatError(f"the book holds something that is not a contact: {broken}") from broken

    if at != len(raw):
        # Trailing bytes mean the file is not what it says. Ignoring them would
        # let anything be appended to a book without the reader noticing.
        raise BookFormatError("the book has more bytes after its last contact")

    return book


def load(store: Store) -> ContactBook:
    """Read a contact book, returning an empty one if nothing is saved yet."""
    raw = store.load()
    if raw is None:
        return ContactBook()
    return deserialise(raw)


def save(store: Store, book: ContactBook) -> None:
    """Write a contact book, replacing whatever was there."""
    store.save(serialise(book))
