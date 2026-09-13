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
    -- layout 2 only:
    conversation count (2)
    per conversation:  contact identity (2 + n)
      key count (1)
      per key:     flags (1) || created (8) || superseded (8, if flagged)
                   || private (2 + n) || public (2 + n)    -- not for the invitation key
      epoch count (1)
      per epoch:   identifier (2 + n) || root (2 + n) || created (8)
                   || opened with (2 + n) || highest counter (4) || seen (8)
      retired count (2)
      per retired: identifier (2 + n) || when (8)
      -- layout 3 only:
      introduced with (2 + n) || disputed (1)
    -- layout 3 only:
    open invitation count (1)
    per invitation: private (2 + n) || public (2 + n) || created (8)

Layout 3 added one-time invitations — see :mod:`mayak.invitations`: the keys of
invitations handed out and not yet used, a flag on a conversation key that came
from one, which of our keys each contact was introduced with, and whether a
contact named an invitation somebody else had already used.

Layout 2 added what a conversation needs to keep receiving across a restart:
its receiving keys — see :mod:`mayak.keyring` — and its receiving epochs, with
their replay windows and the record of retired ones — see :mod:`mayak.epoch`.
Without the keys, no contact could reach a restarted device until introduced
again; without the epochs, not until the contact happened to open a new one, up
to a week later. That second one was not hypothetical: a restart test found it.

Outgoing epochs are never here. See :mod:`mayak.epoch` for why.

The invitation key's entry carries a flag and no key material. That key is
already in the file once, as the device's own — or it is not in the file at all,
because a platform keystore holds it, and a keyring must not be the side door
that copies it out.

A layout 1 file still opens, with no keyrings: every conversation in it starts
again from the invitation key, which is exactly what they were using.

The book is delegated to :mod:`mayak.book` rather than re-parsed here, and it is
length-prefixed so that module can keep refusing trailing bytes — it is handed
exactly its own slice and nothing else.

The field helpers are this module's own rather than shared with
:mod:`mayak.book`. Two file formats that share a parser are two file formats
where a change to one silently changes the other, and eight lines of
duplication is a cheaper price than that.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass, field

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from mayak import book as book_format
from mayak.book import BookFormatError
from mayak.contacts import ContactBook
from mayak.epoch import EpochRecord
from mayak.invitations import InvitationKey
from mayak.kem import Kem
from mayak.session import Us
from mayak.store import Store

#: This module's own format marker, separate from the store's and the book's.
LAYOUT_VERSION = 3

#: Layouts this build reads. Only :data:`LAYOUT_VERSION` is written.
READABLE_LAYOUTS = (1, 2, 3)

_MAX_FIELD = 0xFFFF
_KEYS_ABSENT = 0
_KEYS_PRESENT = 1

_FLAG_INTRODUCTION = 0x01
_FLAG_CONFIRMED = 0x02
_FLAG_SUPERSEDED = 0x04
_FLAG_INVITATION = 0x08
_KNOWN_FLAGS = _FLAG_INTRODUCTION | _FLAG_CONFIRMED | _FLAG_SUPERSEDED | _FLAG_INVITATION
_MAX_OPEN_INVITATIONS = 255
_MAX_KEYS_PER_RING = 255
_MAX_EPOCHS_PER_CONVERSATION = 255


class DeviceFormatError(ValueError):
    """The saved bytes are not a device this build can read."""


@dataclass(frozen=True)
class StoredKey:
    """A conversation's receiving key as the file holds it.

    For the invitation key, :attr:`private` and :attr:`public` are empty: the
    caller fills them in from the device's own keys.
    """

    private: bytes
    public: bytes
    created: float
    confirmed: bool = False
    superseded: float | None = None
    introduction: bool = False
    invitation: bool = False


@dataclass(frozen=True)
class StoredConversation:
    """What one conversation needs to keep receiving after a restart."""

    #: Receiving keys, oldest first.
    keys: list[StoredKey]

    #: Live receiving epochs.
    epochs: list[EpochRecord] = field(default_factory=list)

    #: Recently retired epoch identifiers, and when.
    retired: dict[bytes, float] = field(default_factory=dict)

    #: Our public key the contact was introduced with; empty until known.
    introduced_with: bytes = b""

    #: Whether the contact named an invitation someone else had already used.
    disputed: bool = False


@dataclass(frozen=True)
class Device:
    """Everything one device keeps between runs."""

    #: None when the keys are held somewhere this file does not reach.
    us: Us | None
    book: ContactBook

    #: Contact identity key -> that conversation's receiving side.
    conversations: dict[bytes, StoredConversation] = field(default_factory=dict)

    #: Invitations handed out and not yet used.
    invitations: list[InvitationKey] = field(default_factory=list)


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

    if len(device.conversations) > _MAX_FIELD:
        raise DeviceFormatError(f"{len(device.conversations)} conversations exceeds the {_MAX_FIELD} allowed")
    out += struct.pack(">H", len(device.conversations))
    for identity, conversation in device.conversations.items():
        keys, epochs, retired = conversation.keys, conversation.epochs, conversation.retired
        if not keys or len(keys) > _MAX_KEYS_PER_RING:
            raise DeviceFormatError(f"a keyring of {len(keys)} keys cannot be written")
        if len(epochs) > _MAX_EPOCHS_PER_CONVERSATION or len(retired) > _MAX_FIELD:
            raise DeviceFormatError("a conversation holds more epochs than the format allows")
        out += _field(identity)
        out.append(len(keys))
        for key in keys:
            out += _stored_key(key)
        out.append(len(epochs))
        for epoch in epochs:
            out += _field(epoch.identifier) + _field(epoch.root) + struct.pack(">d", epoch.created)
            out += _field(epoch.opened_with) + struct.pack(">iQ", epoch.highest, epoch.seen)
        out += struct.pack(">H", len(retired))
        for identifier, when in retired.items():
            out += _field(identifier) + struct.pack(">d", when)
        out += _field(conversation.introduced_with) + bytes([1 if conversation.disputed else 0])

    if len(device.invitations) > _MAX_OPEN_INVITATIONS:
        raise DeviceFormatError(f"{len(device.invitations)} open invitations exceeds what the format allows")
    out.append(len(device.invitations))
    for invitation in device.invitations:
        out += _field(invitation.private) + _field(invitation.public) + struct.pack(">d", invitation.created)
    return bytes(out)


def _stored_key(key: StoredKey) -> bytes:
    flags = (
        (_FLAG_INTRODUCTION if key.introduction else 0)
        | (_FLAG_INVITATION if key.invitation else 0)
        | (_FLAG_CONFIRMED if key.confirmed else 0)
        | (_FLAG_SUPERSEDED if key.superseded is not None else 0)
    )
    out = bytearray([flags]) + struct.pack(">d", key.created)
    if key.superseded is not None:
        out += struct.pack(">d", key.superseded)
    if not key.introduction:
        if not key.private or not key.public:
            raise DeviceFormatError("a rotated key with no key material cannot be written")
        out += _field(key.private) + _field(key.public)
    return bytes(out)


def _take_time(raw: bytes, at: int) -> tuple[float, int]:
    if at + 8 > len(raw):
        raise DeviceFormatError("the device ends in the middle of a time")
    (value,) = struct.unpack(">d", raw[at : at + 8])
    if not math.isfinite(value):
        raise DeviceFormatError("the device holds a time that is not a time")
    return value, at + 8


def _take_conversations(raw: bytes, at: int, layout: int) -> tuple[dict[bytes, StoredConversation], int]:
    if at + 2 > len(raw):
        raise DeviceFormatError("the device ends before its conversations")
    (count,) = struct.unpack(">H", raw[at : at + 2])
    at += 2

    conversations: dict[bytes, StoredConversation] = {}
    for _ in range(count):
        identity, at = _take_field(raw, at)
        if identity in conversations:
            raise DeviceFormatError("the device holds two conversations for one contact")
        if at >= len(raw):
            raise DeviceFormatError("the device ends before a keyring's size")
        size = raw[at]
        at += 1
        if size == 0:
            raise DeviceFormatError("the device holds a keyring with no keys")

        keys = []
        for _ in range(size):
            if at >= len(raw):
                raise DeviceFormatError("the device ends before a key")
            flags = raw[at]
            at += 1
            if flags & ~_KNOWN_FLAGS:
                raise DeviceFormatError(f"unknown key flags {flags:#x}")
            created, at = _take_time(raw, at)
            superseded = None
            if flags & _FLAG_SUPERSEDED:
                superseded, at = _take_time(raw, at)
            private = public = b""
            introduction = bool(flags & _FLAG_INTRODUCTION)
            if not introduction:
                private, at = _take_field(raw, at)
                public, at = _take_field(raw, at)
                if not private or not public:
                    raise DeviceFormatError("the device holds a rotated key with no key material")
            keys.append(
                StoredKey(
                    private=private,
                    public=public,
                    created=created,
                    confirmed=bool(flags & _FLAG_CONFIRMED),
                    superseded=superseded,
                    introduction=introduction,
                    invitation=bool(flags & _FLAG_INVITATION),
                ),
            )

        if at >= len(raw):
            raise DeviceFormatError("the device ends before a conversation's epochs")
        epoch_count = raw[at]
        at += 1
        epochs = []
        for _ in range(epoch_count):
            identifier, at = _take_field(raw, at)
            root, at = _take_field(raw, at)
            created, at = _take_time(raw, at)
            opened_with, at = _take_field(raw, at)
            if at + 12 > len(raw):
                raise DeviceFormatError("the device ends in the middle of a replay window")
            highest, seen = struct.unpack(">iQ", raw[at : at + 12])
            at += 12
            if not identifier or not root:
                raise DeviceFormatError("the device holds an epoch with no root")
            epochs.append(
                EpochRecord(
                    identifier=identifier,
                    root=root,
                    created=created,
                    opened_with=opened_with,
                    highest=highest,
                    seen=seen,
                ),
            )

        if at + 2 > len(raw):
            raise DeviceFormatError("the device ends before a conversation's retired epochs")
        (retired_count,) = struct.unpack(">H", raw[at : at + 2])
        at += 2
        retired: dict[bytes, float] = {}
        for _ in range(retired_count):
            identifier, at = _take_field(raw, at)
            retired[identifier], at = _take_time(raw, at)

        introduced_with, disputed = b"", False
        if layout >= 3:
            introduced_with, at = _take_field(raw, at)
            if at >= len(raw):
                raise DeviceFormatError("the device ends before a conversation's dispute flag")
            if raw[at] not in (0, 1):
                raise DeviceFormatError(f"unknown dispute flag {raw[at]}")
            disputed = raw[at] == 1
            at += 1

        conversations[identity] = StoredConversation(
            keys=keys,
            epochs=epochs,
            retired=retired,
            introduced_with=introduced_with,
            disputed=disputed,
        )
    return conversations, at


def _take_invitations(raw: bytes, at: int) -> tuple[list[InvitationKey], int]:
    if at >= len(raw):
        raise DeviceFormatError("the device ends before its open invitations")
    count = raw[at]
    at += 1
    invitations = []
    for _ in range(count):
        private, at = _take_field(raw, at)
        public, at = _take_field(raw, at)
        created, at = _take_time(raw, at)
        if not private or not public:
            raise DeviceFormatError("the device holds an invitation with no key")
        invitations.append(InvitationKey(private=private, public=public, created=created))
    return invitations, at


def deserialise(raw: bytes) -> Device:
    """Rebuild a device, or refuse.

    Refusing rather than repairing, for the same reason the contact book does:
    a device file that loses half its contacts silently is a set of people whose
    messages stop arriving with no error anywhere.
    """
    if len(raw) < 6:
        raise DeviceFormatError("too short to be a device")

    layout = raw[0]
    if layout not in READABLE_LAYOUTS:
        raise DeviceFormatError(f"device layout {layout} is not one this build reads {READABLE_LAYOUTS}")

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
    if at + length > len(raw):
        raise DeviceFormatError("the device claims a contact book longer than what is left of it")
    if layout == 1 and at + length != len(raw):
        # Layout 1 ends with the book. Anything after it means the file is not
        # what it says, and ignoring it would let anything ride along unnoticed.
        raise DeviceFormatError("the device and its contact book disagree about where the file ends")

    try:
        book = book_format.deserialise(raw[at : at + length])
    except BookFormatError as broken:
        raise DeviceFormatError(f"the device holds something that is not a contact book: {broken}") from broken
    at += length

    conversations: dict[bytes, StoredConversation] = {}
    invitations: list[InvitationKey] = []
    if layout >= 2:
        conversations, at = _take_conversations(raw, at, layout)
    if layout >= 3:
        invitations, at = _take_invitations(raw, at)
    if layout >= 2 and at != len(raw):
        raise DeviceFormatError("the device has more bytes after what it holds")

    return Device(us=us, book=book, conversations=conversations, invitations=invitations)


def load(store: Store) -> Device | None:
    """Read a device, or None if nothing has been saved yet."""
    raw = store.load()
    if raw is None:
        return None
    return deserialise(raw)


def save(store: Store, device: Device) -> None:
    """Write a device, replacing whatever was there."""
    store.save(serialise(device))
