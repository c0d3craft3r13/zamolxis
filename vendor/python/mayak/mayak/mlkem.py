"""The post-quantum half, from the library that was already here.

## Why this is a small file

:mod:`mayak.kem` says the post-quantum member is the part most likely to be
replaced, and that nothing above it should name a library. This is the one place
that does, and it is deliberately nothing but an adapter: lengths, error
mapping, and the order of a returned pair.

## Where the implementation comes from

`cryptography` 47 and later exposes ML-KEM-768 (FIPS 203) through its Rust
binding, which is AWS-LC underneath — a C implementation written for production
rather than for teaching. It is the same package this project already depends on
for X25519, AES-GCM and Argon2id, so the post-quantum half costs no new
dependency, no build step and no vendored C.

That is a better answer than the two pure-Python packages, which say plainly
that they are educational and not side-channel resistant. Shipping one of those
would have looked like protection while falling to a timing measurement; this is
protection.

## On Android it is not here yet

Chaquopy resolves `cryptography` to 42.0.8 — the newest build in its native
wheel repository — so :func:`post_quantum_mechanism` finds nothing there and the
caller has to say ``--insecure-classical-only`` in as many words or refuse to
run. That is the correct behaviour and not the end state: the Android app
already carries ML-KEM-768 in Kotlin, and it reaches this protocol through the
same :class:`mayak.kem.Kem` interface. Which is what the interface is for.

## The private key is the seed

ML-KEM's expanded private key is 2400 bytes; its seed is 64, and the expanded
key is derived from it deterministically. The seed is what goes in the device
file, because what is stored is what has to be destroyed, and 64 bytes are
easier to keep track of than 2400.

## Decapsulation does not fail, by design

Feed an ML-KEM ciphertext to the wrong private key and it returns a shared
secret — a different one, derived from the key and the ciphertext. That is
FIPS 203's implicit rejection, and it is deliberate: a decapsulation that
reported failure would tell an attacker which of their guesses was closer.

Nothing here tries to improve on it. What catches a wrong key is the seal
failing to open one layer up, which is where a failure belongs.
"""

from __future__ import annotations

from mayak.kem import Encapsulation, KemError

try:
    from cryptography.hazmat.primitives.asymmetric.mlkem import MLKEM768PrivateKey, MLKEM768PublicKey
except ImportError as too_old:  # pragma: no cover - depends on the installed build
    raise ImportError(
        "ML-KEM-768 needs cryptography 47 or later; this build has an older one",
    ) from too_old


class MlKem768:
    """ML-KEM-768 as a :class:`mayak.kem.Kem`."""

    name = "ml-kem-768"

    #: FIPS 203 parameter set sizes. Written down rather than asked of the
    #: library because they are properties of the standard, and a build whose
    #: numbers disagreed with these would be producing something else.
    public_key_length = 1184
    private_key_length = 64
    ciphertext_length = 1088
    shared_secret_length = 32

    post_quantum = True

    def generate(self) -> tuple[bytes, bytes]:
        private = MLKEM768PrivateKey.generate()
        return private.private_bytes_raw(), private.public_key().public_bytes_raw()

    def encapsulate(self, public_key: bytes) -> Encapsulation:
        if len(public_key) != self.public_key_length:
            raise KemError(f"an {self.name} public key is {self.public_key_length} bytes, not {len(public_key)}")
        try:
            recipient = MLKEM768PublicKey.from_public_bytes(public_key)
            # The library returns (secret, ciphertext); this project's pair is
            # named rather than positional, so the order cannot be got wrong
            # twice.
            shared_secret, ciphertext = recipient.encapsulate()
        except Exception as bad_key:  # noqa: BLE001 - several exception types, one cause
            raise KemError(f"the public key is not a usable {self.name} key") from bad_key
        return Encapsulation(shared_secret=shared_secret, ciphertext=ciphertext)

    def decapsulate(self, private_key: bytes, ciphertext: bytes) -> bytes:
        if len(private_key) != self.private_key_length:
            raise KemError(f"an {self.name} private key is {self.private_key_length} bytes, not {len(private_key)}")
        if len(ciphertext) != self.ciphertext_length:
            raise KemError(f"an {self.name} ciphertext is {self.ciphertext_length} bytes, not {len(ciphertext)}")
        try:
            return MLKEM768PrivateKey.from_seed_bytes(private_key).decapsulate(ciphertext)
        except Exception as unusable:  # noqa: BLE001 - several exception types, one cause
            raise KemError(f"the key material is not a usable {self.name} pair") from unusable
