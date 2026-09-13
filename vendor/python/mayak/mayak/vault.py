"""Somewhere to keep keys that can be destroyed for real.

## Why a store needs one

A device file is rewritten whole, but the flash inside a phone does not erase
what it frees: an earlier copy of the file can sit in unused blocks for a long
time. Every such copy is sealed under the same passphrase. So key rotation —
destroying a conversation's old keys so recorded history stops opening — is only
as good as the guarantee that no earlier copy of the file still holds those keys
in a form the passphrase opens.

A vault closes that. It holds keys in hardware the application cannot read out,
and destroys them when asked. A store bound to a vault — see
:mod:`mayak.bound_store` — wraps each file's key with a vault key, and replaces
that vault key whenever a secret leaves the file. Earlier copies on flash name a
vault key that no longer exists, and the passphrase alone opens none of them.

## The contract

Five operations over short string names. Nothing here exposes key material: a
vault wraps and unwraps secrets, and the keys that do the wrapping never leave it.

:class:`MemoryVault` is not a vault in that sense — its keys are ordinary bytes in
this process — and exists so the store's logic can be tested where there is no
hardware. :func:`hardware_vault` returns a real one, or None.
"""

from __future__ import annotations

import secrets
from typing import Protocol, runtime_checkable

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class VaultError(RuntimeError):
    """A vault operation failed — most often, the key it named no longer exists."""


class VaultUnavailable(VaultError):
    """No vault that deserves the name on this device."""


@runtime_checkable
class KeyVault(Protocol):
    """Keys that stay inside, and can be destroyed."""

    #: What backs it, in words a person can read: "strongbox", "trusted environment".
    name: str

    def create(self, alias: str) -> None:
        """Make a new key under ``alias``."""

    def wrap(self, alias: str, secret: bytes) -> bytes:
        """Encrypt ``secret`` under the key named ``alias``."""

    def unwrap(self, alias: str, wrapped: bytes) -> bytes:
        """Recover a secret, or raise :class:`VaultError` if the key is gone or it was altered."""

    def destroy(self, alias: str) -> None:
        """Destroy the key. Safe to call for one that does not exist."""

    def aliases(self, prefix: str) -> list[str]:
        """Names of existing keys that start with ``prefix``."""


class MemoryVault:
    """A vault for tests: the logic of one, and none of the protection."""

    name = "memory (tests only)"

    def __init__(self) -> None:
        self._keys: dict[str, bytes] = {}

    def create(self, alias: str) -> None:
        self._keys[alias] = secrets.token_bytes(32)

    def wrap(self, alias: str, secret: bytes) -> bytes:
        key = self._keys.get(alias)
        if key is None:
            raise VaultError(f"no key named {alias}")
        nonce = secrets.token_bytes(12)
        return nonce + AESGCM(key).encrypt(nonce, secret, alias.encode())

    def unwrap(self, alias: str, wrapped: bytes) -> bytes:
        key = self._keys.get(alias)
        if key is None:
            raise VaultError(f"no key named {alias}")
        try:
            return AESGCM(key).decrypt(wrapped[:12], wrapped[12:], alias.encode())
        except (InvalidTag, ValueError) as refused:
            raise VaultError("the wrapped secret was altered") from refused

    def destroy(self, alias: str) -> None:
        self._keys.pop(alias, None)

    def aliases(self, prefix: str) -> list[str]:
        return [alias for alias in self._keys if alias.startswith(prefix)]


def hardware_vault() -> KeyVault | None:
    """A vault backed by hardware on this device, or None where there is none.

    Only Android has one here, reached through Chaquopy's bridge to the platform
    Keystore — see :mod:`mayak.android_vault`. A keystore that turns out to be
    software-only is refused rather than returned: binding a file to it would
    promise a protection it does not give.
    """
    try:
        from mayak.android_vault import AndroidKeystoreVault
    except ImportError:
        return None
    try:
        return AndroidKeystoreVault()
    except VaultUnavailable:
        return None
