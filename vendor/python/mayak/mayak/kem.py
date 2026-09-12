"""Key encapsulation, kept behind one small interface on purpose.

## Why this is an interface and not a library call

The post-quantum part of this design is the part most likely to be replaced.
The standard is young, implementations are younger, and on Android the choice is
further narrowed by what Chaquopy can build: a package with a native extension
has to have an Android wheel or be compiled into the app, and a pure-Python one
runs anywhere but slowly. Any of that may look different in a year.

So nothing above this module names a library. Sealing asks for "a key
encapsulation mechanism", gets shared secrets and ciphertexts, and does not know
whether the bytes came from a lattice or an elliptic curve. Swapping the
implementation is then a change in one place, not an archaeology dig.

## What a KEM is here

Two operations over a keypair the receiver owns:

- **encapsulate** against a public key: produces a fresh shared secret and a
  ciphertext that carries it. Only the private key recovers the secret.
- **decapsulate** the ciphertext with the private key: recovers the same secret.

That is the whole contract. It deliberately does not include key generation from
a seed, because the two live in different places — addresses are derived (see
:mod:`mayak.addressing`) while encapsulation keys are generated and rotated.

## Hybrid, always

The classical KEM here is X25519 and it is not an alternative to the
post-quantum one — it runs alongside it. A hybrid fails only if *both* fail, and
the two rest on unrelated problems: one on discrete logarithms, one on lattices.
Using the post-quantum KEM alone would bet everything on the newer and less
studied of the pair; using X25519 alone is the bet that a recording made today
cannot be opened later.

:class:`HybridKem` combines any two members, so the post-quantum half can be
replaced without touching the classical half or anything above.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey


class KemError(ValueError):
    """Key material or a ciphertext was not what the mechanism expects."""


@dataclass(frozen=True)
class Encapsulation:
    """What encapsulation produces: a secret to use, and bytes to send."""

    #: Never transmitted. Fed to a KDF, never used as a key directly.
    shared_secret: bytes

    #: Transmitted. Useless without the receiver's private key.
    ciphertext: bytes


@runtime_checkable
class Kem(Protocol):
    """The contract every mechanism satisfies, classical or post-quantum."""

    #: Short, stable label. Appears in transcripts so two ends can tell whether
    #: they agree about which mechanism they are using, rather than discovering
    #: a mismatch as an undecryptable message.
    name: str

    #: Length of a public key, in bytes. Fixed per mechanism.
    public_key_length: int

    #: Length of a ciphertext, in bytes. Fixed per mechanism — variable-length
    #: ciphertexts would leak through the frame padding.
    ciphertext_length: int

    #: Whether this mechanism is believed to resist an adversary with a quantum
    #: computer. Declared by the mechanism rather than inferred from its name so
    #: that sealing can refuse a downgrade instead of finding out later.
    post_quantum: bool

    def generate(self) -> tuple[bytes, bytes]:
        """Return a fresh ``(private_key, public_key)`` pair."""

    def encapsulate(self, public_key: bytes) -> Encapsulation: ...

    def decapsulate(self, private_key: bytes, ciphertext: bytes) -> bytes: ...


class X25519Kem:
    """The classical half: an ephemeral agreement against a static key.

    Not a KEM in the textbook sense — it is a Diffie-Hellman agreement dressed
    as one, which is the standard construction. The "ciphertext" is the
    ephemeral public key, and the shared secret is the agreement between that
    ephemeral private key and the receiver's static public key.
    """

    name = "x25519"
    public_key_length = 32
    ciphertext_length = 32
    post_quantum = False

    def generate(self) -> tuple[bytes, bytes]:
        private = X25519PrivateKey.generate()
        return private.private_bytes_raw(), private.public_key().public_bytes_raw()

    def encapsulate(self, public_key: bytes) -> Encapsulation:
        if len(public_key) != self.public_key_length:
            raise KemError(f"an X25519 public key is {self.public_key_length} bytes, not {len(public_key)}")
        try:
            receiver = X25519PublicKey.from_public_bytes(public_key)
            ephemeral = X25519PrivateKey.generate()
            return Encapsulation(
                shared_secret=ephemeral.exchange(receiver),
                ciphertext=ephemeral.public_key().public_bytes_raw(),
            )
        except KemError:
            raise
        except Exception as bad_key:  # noqa: BLE001 - several exception types, one cause
            raise KemError("the public key is not a usable X25519 key") from bad_key

    def decapsulate(self, private_key: bytes, ciphertext: bytes) -> bytes:
        if len(ciphertext) != self.ciphertext_length:
            raise KemError(f"an X25519 ciphertext is {self.ciphertext_length} bytes, not {len(ciphertext)}")
        try:
            static = X25519PrivateKey.from_private_bytes(private_key)
            ephemeral_public = X25519PublicKey.from_public_bytes(ciphertext)
            return static.exchange(ephemeral_public)
        except KemError:
            raise
        except Exception as bad_key:  # noqa: BLE001 - several exception types, one cause
            raise KemError("the key material is not a usable X25519 pair") from bad_key


class HybridKem:
    """Two mechanisms run together; both must break for the pair to break.

    Keys and ciphertexts are the two halves laid end to end, classical first.
    The layout is fixed rather than tagged because both lengths are constants of
    the mechanisms — a length prefix would add bytes that say nothing and vary
    with nothing.

    The shared secret is the two secrets concatenated in the same order. It is
    **not** a key: it goes to a KDF, which is what actually binds the two halves
    together. Concatenation alone would let a weakness in either half show
    through; a KDF over both means recovering the result needs both.
    """

    def __init__(self, classical: Kem, quantum: Kem) -> None:
        self.classical = classical
        #: The post-quantum member. Named apart from the `post_quantum` flag
        #: below, which says whether this hybrid earns the name.
        self.quantum = quantum
        self.name = f"{classical.name}+{quantum.name}"
        # A hybrid is only as post-quantum as its post-quantum half. Pairing two
        # classical mechanisms produces something that must still be refused.
        self.post_quantum = bool(getattr(quantum, "post_quantum", False))
        self.public_key_length = classical.public_key_length + quantum.public_key_length
        self.ciphertext_length = classical.ciphertext_length + quantum.ciphertext_length

    def generate(self) -> tuple[bytes, bytes]:
        classical_private, classical_public = self.classical.generate()
        quantum_private, quantum_public = self.quantum.generate()
        return classical_private + quantum_private, classical_public + quantum_public

    def encapsulate(self, public_key: bytes) -> Encapsulation:
        if len(public_key) != self.public_key_length:
            raise KemError(f"a {self.name} public key is {self.public_key_length} bytes, not {len(public_key)}")
        split = self.classical.public_key_length
        classical = self.classical.encapsulate(public_key[:split])
        quantum = self.quantum.encapsulate(public_key[split:])
        return Encapsulation(
            shared_secret=classical.shared_secret + quantum.shared_secret,
            ciphertext=classical.ciphertext + quantum.ciphertext,
        )

    def decapsulate(self, private_key: bytes, ciphertext: bytes) -> bytes:
        if len(ciphertext) != self.ciphertext_length:
            raise KemError(f"a {self.name} ciphertext is {self.ciphertext_length} bytes, not {len(ciphertext)}")
        key_split = self.classical_private_length(private_key)
        cipher_split = self.classical.ciphertext_length
        classical = self.classical.decapsulate(private_key[:key_split], ciphertext[:cipher_split])
        quantum = self.quantum.decapsulate(private_key[key_split:], ciphertext[cipher_split:])
        return classical + quantum

    def classical_private_length(self, private_key: bytes) -> int:
        """Where the classical private key ends inside a hybrid private key.

        X25519 private keys are 32 bytes; post-quantum ones are much larger and
        their size depends on the mechanism. Rather than hard-code either, the
        split is taken from the classical half, which is the fixed one.
        """
        split = 32 if self.classical.name == "x25519" else self.classical.public_key_length
        if len(private_key) <= split:
            raise KemError(f"a {self.name} private key is longer than {len(private_key)} bytes")
        return split
