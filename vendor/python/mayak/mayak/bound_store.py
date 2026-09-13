"""A device file that earlier copies on flash cannot be opened from.

## The problem with an ordinary encrypted file

:class:`mayak.store.EncryptedStore` rewrites its file whole, and the flash inside
a phone keeps what it frees. Every earlier copy is sealed under the same
passphrase — so whoever has the phone and the passphrase can open an old copy,
and with it keys that key rotation destroyed on purpose. Rotation stops recorded
history opening; old copies of the file start it opening again.

## What binding changes

A bound file's contents are sealed under a key that needs two things: the
passphrase, stretched as before, and a random file key wrapped by a key held in
a hardware vault — see :mod:`mayak.vault`. Neither alone opens it.

When a secret leaves the file — a conversation key destroyed, an epoch root
retired, an invitation used or expired, a contact forgotten — the store makes a
new vault key and a new file key, writes the file under them, and only then
destroys the old vault key. Every earlier copy names a vault key that no longer
exists. The passphrase opens none of them.

Saves that remove nothing — the one before each message is shown, which only
moves a replay window — keep the current vault key, so receiving does not wait
on the secure element for every message.

## What it costs, stated

- **The file cannot move.** It opens only on the phone whose vault holds its key:
  not on a laptop, not on the next phone, not from a backup.
- **Losing the phone loses the contacts.** There is nothing to restore from.
- **Both halves are needed.** A stolen passphrase without the phone, or the phone
  without the passphrase, opens nothing.

That is why this is a mode chosen on purpose rather than a default.

## Crashes

A new vault key is made before the file is replaced and the old one destroyed
after, so a crash can leave a spare vault key but never a file without one.
Spares are found by name and destroyed the next time the store opens.

A file that is gone for good — deleted by hand, or a temporary one from a run that
crashed — leaves its vault keys behind with nothing to open them, because the
Keystore outlives files. :func:`forget_orphaned_stores` destroys every store key
whose file is not among those an application says it still has. Found on the
phone: a self-check that crashed half way left keys that the next run counted.

## Changing mode

:func:`rebind` turns an existing file bound or portable in place, for a setting a
person can switch. The two directions reach different things, and its docstring
says which.

## The format

    magic (6) || version 2 (1) || salt (16) || nonce (12)
    || vault key name (1 + n) || wrapped file key (2 + n) || ciphertext and tag

The whole header is authenticated.
"""

from __future__ import annotations

import hashlib
import secrets
import struct
from collections.abc import Iterable
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from mayak.store import (
    MAGIC,
    NONCE_LENGTH,
    SALT_LENGTH,
    EncryptedStore,
    Store,
    StoreError,
    overwrite_and_remove,
    stretch_passphrase,
    write_atomically,
)
from mayak.store import VERSION as PASSPHRASE_ONLY_VERSION
from mayak.vault import KeyVault, VaultError

#: The store version that means "bound to a vault".
BOUND_VERSION = 2

#: Every bound store's vault keys are named with this, then a digest of the
#: file's path, then something random.
KEY_NAME_PREFIX = "mayak-store-"

_FILE_KEY_LENGTH = 32
_KEY_INFO = b"mayak/store/bound/v1"


class BoundStore:
    """One file, sealed under a passphrase and a key a hardware vault can destroy."""

    def __init__(self, path: str | Path, passphrase: bytes, vault: KeyVault) -> None:
        if not passphrase:
            raise StoreError("a store with no passphrase is not encrypted")
        self._path = Path(path)
        self._passphrase = passphrase
        self._vault = vault
        #: Names of this file's vault keys start with this, so a spare left by a
        #: crash can be recognised as this file's and nobody else's.
        self._prefix = key_prefix(self._path)
        self._derived: tuple[bytes, bytes] | None = None
        #: (vault key name, file key, wrapped file key) for the file as it stands.
        self._current: tuple[str, bytes, bytes] | None = None

    @property
    def path(self) -> Path:
        return self._path

    @property
    def vault_name(self) -> str:
        return self._vault.name

    def exists(self) -> bool:
        return self._path.is_file()

    def load(self) -> bytes | None:
        if not self._path.is_file():
            return None
        raw = self._path.read_bytes()
        header, alias, wrapped, salt, nonce, ciphertext = _parse(raw)

        try:
            file_key = self._vault.unwrap(alias, wrapped)
        except VaultError as gone:
            raise StoreError(
                "the hardware key this file was sealed under no longer exists; this copy cannot be opened",
            ) from gone

        try:
            payload = AESGCM(self._key(salt, file_key)).decrypt(nonce, ciphertext, header)
        except (InvalidTag, ValueError) as refused:
            raise StoreError("the store could not be opened") from refused

        self._current = (alias, file_key, wrapped)
        self._destroy_spares(keep=alias)
        return payload

    def save(self, payload: bytes, *, forget_previous: bool = False) -> None:
        previous = self._current
        if previous is None or forget_previous:
            alias = self._prefix + secrets.token_hex(8)
            self._vault.create(alias)
            file_key = secrets.token_bytes(_FILE_KEY_LENGTH)
            wrapped = self._vault.wrap(alias, file_key)
        else:
            alias, file_key, wrapped = previous

        salt = self._derived[0] if self._derived is not None else secrets.token_bytes(SALT_LENGTH)
        nonce = secrets.token_bytes(NONCE_LENGTH)
        encoded_alias = alias.encode("ascii")
        header = (
            MAGIC
            + bytes([BOUND_VERSION])
            + salt
            + nonce
            + bytes([len(encoded_alias)])
            + encoded_alias
            + struct.pack(">H", len(wrapped))
            + wrapped
        )
        ciphertext = AESGCM(self._key(salt, file_key)).encrypt(nonce, payload, header)
        write_atomically(self._path, header + ciphertext)

        self._current = (alias, file_key, wrapped)
        if previous is not None and previous[0] != alias:
            # Only now: the file on disk no longer needs it.
            self._vault.destroy(previous[0])

    def wipe(self) -> None:
        """Destroy the vault keys, then the file.

        Destroying the vault key first is what makes this stronger than the
        passphrase-only wipe: even a perfect image of the current file taken a
        moment earlier no longer opens.
        """
        for alias in self._vault.aliases(self._prefix):
            self._vault.destroy(alias)
        self._current = None
        self._derived = None
        overwrite_and_remove(self._path)

    def _key(self, salt: bytes, file_key: bytes) -> bytes:
        if self._derived is None or self._derived[0] != salt:
            self._derived = (salt, stretch_passphrase(self._passphrase, salt))
        return HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=_KEY_INFO).derive(
            self._derived[1] + file_key,
        )

    def _destroy_spares(self, keep: str) -> None:
        for alias in self._vault.aliases(self._prefix):
            if alias != keep:
                self._vault.destroy(alias)


def key_prefix(path: str | Path) -> str:
    """How the vault keys of the store at ``path`` are named."""
    digest = hashlib.sha256(str(Path(path).resolve()).encode()).hexdigest()[:12]
    return f"{KEY_NAME_PREFIX}{digest}-"


def forget_orphaned_stores(vault: KeyVault, keep: Iterable[str | Path]) -> int:
    """Destroy the vault keys of every bound store not in ``keep``. Returns how many.

    Call it with every device file the application still uses. A key whose file
    is not among them opens nothing that exists, and leaving it costs a key in the
    secure element and a record that the file once existed.
    """
    kept = tuple(key_prefix(path) for path in keep)
    orphaned = [alias for alias in vault.aliases(KEY_NAME_PREFIX) if not alias.startswith(kept)]
    for alias in orphaned:
        vault.destroy(alias)
    return len(orphaned)


def _parse(raw: bytes) -> tuple[bytes, str, bytes, bytes, bytes, bytes]:
    at = len(MAGIC)
    if len(raw) < at + 1 + SALT_LENGTH + NONCE_LENGTH + 1 or raw[:at] != MAGIC:
        raise StoreError("the file is not a store")
    if raw[at] != BOUND_VERSION:
        raise StoreError("the file is not bound to a hardware key")
    at += 1
    salt = raw[at : at + SALT_LENGTH]
    at += SALT_LENGTH
    nonce = raw[at : at + NONCE_LENGTH]
    at += NONCE_LENGTH

    alias_length = raw[at]
    at += 1
    if at + alias_length + 2 > len(raw):
        raise StoreError("the file is too short to be a store")
    try:
        alias = raw[at : at + alias_length].decode("ascii")
    except UnicodeDecodeError as broken:
        raise StoreError("the file names its hardware key in something that is not a name") from broken
    at += alias_length

    (wrapped_length,) = struct.unpack(">H", raw[at : at + 2])
    at += 2
    if at + wrapped_length + 16 > len(raw):
        raise StoreError("the file is too short to be a store")
    wrapped = raw[at : at + wrapped_length]
    at += wrapped_length
    return raw[:at], alias, wrapped, salt, nonce, raw[at:]


def open_store(path: str | Path, passphrase: bytes, vault: KeyVault | None = None, *, bind: bool = False) -> Store:
    """The right store for a file: bound if it is bound, or if a new one is asked to be.

    An existing file says what it is. A file that does not exist yet is created
    bound only when ``bind`` is given — binding is a choice with costs, and
    nothing should make it silently.
    """
    location = Path(path)
    if is_bound(location):
        if vault is None:
            raise StoreError(
                "this device file is bound to the hardware of the phone it was made on, and opens only there",
            )
        return BoundStore(location, passphrase, vault)

    if bind and not location.is_file():
        if vault is None:
            raise StoreError("there is no hardware keystore on this device to bind a file to")
        return BoundStore(location, passphrase, vault)

    if vault is not None:
        # A file that is not bound has no business with vault keys. Any found
        # under its name were left by a crash part-way through binding or
        # unbinding it — see rebind — and open nothing that exists.
        _destroy_keys_of(location, vault)
    return EncryptedStore(location, passphrase)


def is_bound(path: str | Path) -> bool:
    """Whether the file at ``path`` is a store bound to a vault."""
    location = Path(path)
    if not location.is_file():
        return False
    with open(location, "rb") as handle:
        head = handle.read(len(MAGIC) + 1)
    return len(head) == len(MAGIC) + 1 and head[: len(MAGIC)] == MAGIC and head[len(MAGIC)] == BOUND_VERSION


def rebind(path: str | Path, passphrase: bytes, vault: KeyVault | None, *, bind: bool) -> Store:
    """Turn an existing device file bound or portable, in place. Returns the store it now is.

    What each direction does and does not reach, because the two are not mirror
    images:

    - **Binding** rewrites the file under a new vault key. It cannot reach copies
      already on flash: those stay sealed under the passphrase alone, holding
      whatever secrets the file held until now. Binding protects what comes
      after it — the keys made and destroyed from here on — not what came before.
    - **Unbinding** rewrites the file under the passphrase alone, then destroys
      the vault keys. Earlier bound copies stop opening, the file can be copied
      off the phone again, and from here on so can every earlier copy of it.

    The new file is in place before any vault key is destroyed, so a crash
    leaves a file that opens; a vault key it leaves behind is destroyed by
    :func:`open_store` the next time the file is opened.
    """
    location = Path(path)
    current = open_store(location, passphrase, vault)
    payload = current.load()
    if payload is None:
        raise StoreError("there is no device file here to convert")

    if bind == isinstance(current, BoundStore):
        return current

    if bind:
        if vault is None:
            raise StoreError("there is no hardware keystore on this device to bind a file to")
        target: Store = BoundStore(location, passphrase, vault)
        target.save(payload)
        return target

    target = EncryptedStore(location, passphrase)
    target.save(payload)
    if vault is not None:
        _destroy_keys_of(location, vault)
    return target


def _destroy_keys_of(path: Path, vault: KeyVault) -> None:
    for alias in vault.aliases(key_prefix(path)):
        vault.destroy(alias)


__all__ = [
    "BOUND_VERSION",
    "KEY_NAME_PREFIX",
    "PASSPHRASE_ONLY_VERSION",
    "BoundStore",
    "forget_orphaned_stores",
    "is_bound",
    "key_prefix",
    "open_store",
    "rebind",
]
