"""Argon2id, from whichever implementation this device has.

## Two implementations, one answer

The store stretches a passphrase with Argon2id, and it has to come out the same
everywhere: a device file is only portable if the key derived from its
passphrase is.

- `cryptography` 44 and later has Argon2id. The desktop.
- `argon2-cffi` binds the reference C implementation. Android, where Chaquopy's
  `cryptography` is 42.0.8 and has no Argon2id at all — found by reading the
  installed package, before anything was built: `kdf/` there holds concatkdf,
  hkdf, kbkdf, pbkdf2, scrypt and x963kdf, and nothing else.

Both were run side by side at this project's parameters and two others and
produced identical bytes each time; the test suite repeats that on every run.

## Never something weaker

With neither available this raises. It does not fall back to scrypt, PBKDF2, or
anything else that happens to be installed: a store that quietly used a weaker
function on one device would be a store an attacker attacks on that device, and
nothing about the file would say so.
"""

from __future__ import annotations


class StretchUnavailable(RuntimeError):
    """No Argon2id implementation on this device."""


def argon2id(passphrase: bytes, salt: bytes, *, length: int, iterations: int, lanes: int, memory_kib: int) -> bytes:
    """Derive ``length`` bytes from a passphrase with Argon2id, version 1.3."""
    try:
        from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
    except ImportError:
        pass
    else:
        return Argon2id(
            salt=salt,
            length=length,
            iterations=iterations,
            lanes=lanes,
            memory_cost=memory_kib,
        ).derive(passphrase)

    try:
        from argon2.low_level import Type, hash_secret_raw
    except ImportError as missing:
        raise StretchUnavailable(
            "no Argon2id here: needs cryptography 44 or later, or argon2-cffi",
        ) from missing

    # Version 19 is 0x13, Argon2 1.3 — what cryptography implements. Stated
    # rather than left to a default, because the default is what would change.
    return hash_secret_raw(
        passphrase,
        salt,
        time_cost=iterations,
        memory_cost=memory_kib,
        parallelism=lanes,
        hash_len=length,
        type=Type.ID,
        version=19,
    )
