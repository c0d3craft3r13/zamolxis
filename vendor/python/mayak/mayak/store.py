"""Keeping state on a device that may be taken.

## What is stored, and why it needs protecting at all

Contacts and their keys, and messages waiting for someone unreachable. The
messages are already sealed and the store adds nothing to them — but the
contacts are not. A contact list in the clear is a list of who this person talks
to, which on a seized device is often worth more than any single message.

## The shape on disk

    magic (6) || version (1) || salt (16) || nonce (12) || ciphertext and tag

One file, rewritten whole. No index, no free list, no incremental append —
because each of those leaves earlier states recoverable in the gaps, and a store
whose old contents survive its own updates is not a store that can be wiped.

## Why the passphrase is stretched with Argon2id

A passphrase a person can remember has perhaps forty bits in it. Against an
adversary holding the file and a graphics card, a fast hash turns that into
minutes. Argon2id is memory-hard: the same attack needs 64 MiB for every guess
instead of a few kilobytes, which is the difference between minutes and years.

The parameters are chosen for a phone, not a server, and they are fixed by the
format version rather than written into each file. Raising them means a new
version, which an older build refuses by name instead of deriving the wrong key
and reporting a wrong passphrase.

Argon2id itself comes from :mod:`mayak.stretch`, which finds it in `cryptography`
on the desktop and in `argon2-cffi` on Android, where Chaquopy's `cryptography`
has none. The two give identical bytes, so a file opens on either.

## Wiping is overwrite-then-remove, and it is not a guarantee

:meth:`EncryptedStore.wipe` overwrites the bytes before unlinking. On a plain
disk that is enough. On the flash inside a phone it is not: the controller
writes new blocks elsewhere and the old ones are freed but not erased, so the
ciphertext may persist until the device reuses them.

That is why the file is encrypted rather than merely deleted. Wiping destroys
the key material and the file; what remains on the flash is ciphertext nobody
holds a key for, which is the strongest honest claim and better than an
overwrite everyone assumes worked.
"""

from __future__ import annotations

import os
import secrets
from pathlib import Path
from typing import Protocol, runtime_checkable

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from mayak.stretch import argon2id

#: Identifies the format so a file that is not one is refused rather than parsed.
MAGIC = b"MAYAK\x00"
VERSION = 1

SALT_LENGTH = 16
NONCE_LENGTH = 12
KEY_LENGTH = 32
_HEADER = len(MAGIC) + 1 + SALT_LENGTH + NONCE_LENGTH

#: Argon2id parameters, sized for a phone rather than a server.
#:
#: 64 MiB and three passes takes a fraction of a second on a handset and costs
#: an attacker 64 MiB of memory for every guess, which is what makes a
#: remembered passphrase worth anything. They are part of what VERSION means:
#: change them and VERSION has to change with them, or every existing file stops
#: opening and says the passphrase was wrong.
MEMORY_KIB = 64 * 1024
ITERATIONS = 3
LANES = 4


class StoreError(ValueError):
    """The store could not be read, written, or opened with this passphrase."""


@runtime_checkable
class Store(Protocol):
    """Somewhere to keep bytes between runs.

    Deliberately a byte store rather than a database. What goes in it is decided
    above; keeping the interface this narrow is what lets a caller substitute a
    platform keystore, a file, or nothing at all without anything else noticing.
    """

    def load(self) -> bytes | None:
        """Return what was saved, or None if nothing has been."""

    def save(self, payload: bytes) -> None:
        """Replace the contents with ``payload``."""

    def wipe(self) -> None:
        """Destroy the contents. Safe to call when there is nothing there."""


class MemoryStore:
    """A store that forgets when the process does.

    For tests, and for a device that is meant to keep nothing across a restart —
    which is a legitimate configuration rather than a missing feature.
    """

    def __init__(self) -> None:
        self._payload: bytes | None = None

    def load(self) -> bytes | None:
        return self._payload

    def save(self, payload: bytes) -> None:
        self._payload = payload

    def wipe(self) -> None:
        self._payload = None


class EncryptedStore:
    """One file, encrypted with a key stretched from a passphrase."""

    def __init__(self, path: str | Path, passphrase: bytes) -> None:
        if not passphrase:
            raise StoreError("a store with no passphrase is not encrypted")
        self._path = Path(path)
        self._passphrase = passphrase

    @property
    def path(self) -> Path:
        return self._path

    def exists(self) -> bool:
        return self._path.is_file()

    def load(self) -> bytes | None:
        if not self._path.is_file():
            return None

        raw = self._path.read_bytes()
        if len(raw) < _HEADER + 16:
            raise StoreError("the file is too short to be a store")
        if raw[: len(MAGIC)] != MAGIC:
            raise StoreError("the file is not a store")

        version = raw[len(MAGIC)]
        if version != VERSION:
            raise StoreError(f"store version {version} is not version {VERSION}")

        salt = raw[len(MAGIC) + 1 : len(MAGIC) + 1 + SALT_LENGTH]
        nonce = raw[len(MAGIC) + 1 + SALT_LENGTH : _HEADER]

        try:
            key = self._derive(salt)
            # The header is authenticated, so a file whose salt or version was
            # edited fails here rather than decrypting into something else.
            return AESGCM(key).decrypt(nonce, raw[_HEADER:], raw[:_HEADER])
        except (InvalidTag, ValueError) as refused:
            raise StoreError("the store could not be opened") from refused

    def save(self, payload: bytes) -> None:
        salt = secrets.token_bytes(SALT_LENGTH)
        nonce = secrets.token_bytes(NONCE_LENGTH)
        header = MAGIC + bytes([VERSION]) + salt + nonce
        ciphertext = AESGCM(self._derive(salt)).encrypt(nonce, payload, header)

        # Written beside the target and moved into place, so a process that dies
        # mid-write leaves the previous store intact rather than half of a new
        # one. A store that can be truncated by a battery is a store that loses
        # every contact at the worst possible moment.
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_suffix(self._path.suffix + ".writing")
        with open(temporary, "wb") as handle:
            handle.write(header + ciphertext)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, self._path)

    def wipe(self) -> None:
        """Overwrite and remove, and do not pretend that is erasure.

        The overwrite is worth doing and is not sufficient: flash storage writes
        new blocks elsewhere, so the old ciphertext may survive until the device
        reuses them. What makes this a wipe is that the contents were encrypted
        and the key is not stored anywhere — the bytes that survive are bytes
        nobody can open.
        """
        if not self._path.is_file():
            return
        length = self._path.stat().st_size
        with open(self._path, "r+b") as handle:
            handle.write(secrets.token_bytes(length))
            handle.flush()
            os.fsync(handle.fileno())
        self._path.unlink()

    def _derive(self, salt: bytes) -> bytes:
        return argon2id(
            self._passphrase,
            salt,
            length=KEY_LENGTH,
            iterations=ITERATIONS,
            lanes=LANES,
            memory_kib=MEMORY_KIB,
        )
