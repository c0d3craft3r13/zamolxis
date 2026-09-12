"""Turning a frame body into something only its recipient can read.

## The shape

    sealed = KEM ciphertext || AEAD ciphertext-and-tag

Nothing else. No nonce, no key identifier, no algorithm byte.

The nonce is absent because it does not need to be sent. Every message
encapsulates freshly, so every message has its own key, and the same KDF that
produces the key produces the nonce beside it. Both ends derive both. Twelve
bytes saved on every message, and — more to the point — no way to reuse a nonce
under a key, because a key is never used twice.

The algorithm is absent because it is not negotiated. Two ends that disagree
about the mechanism produce different keys and the tag fails; that is the
correct outcome and it needs no field. A field would only offer an attacker
somewhere to write.

## Why a KDF and not the shared secret

The secret a hybrid produces is two secrets laid end to end. Used directly, a
weakness in either half would show through the half it sits in. Run through
HKDF, the output depends on all of it, so recovering the key needs both halves
broken rather than one.

The mechanism's name goes into the KDF's `info`. Two ends running different
mechanisms then derive different keys even if the ciphertext lengths happen to
line up, and fail closed instead of producing plausible rubbish.

## Refusing to fall back

:func:`seal` will not use a mechanism that offers no post-quantum resistance
unless the caller says so in as many words. A message that quietly went out
under X25519 alone would look identical to one that did not, on both ends and
in any log — and the whole point of sealing against a recording made today is
that it cannot be opened in ten years.

So the downgrade exists, because interoperating with something old is a real
need, but it is a named argument at the call site rather than a fallback that
happens when a library fails to load.
"""

from __future__ import annotations

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from mayak.kem import Kem, KemError

#: AES-256 key plus the 96-bit nonce GCM expects, from one derivation.
_KEY_LENGTH = 32
_NONCE_LENGTH = 12

#: What AES-GCM appends. Counted so callers can size a frame without guessing.
TAG_LENGTH = 16

_INFO_PREFIX = b"mayak/seal/v1/"


class SealError(ValueError):
    """A message could not be sealed, or could not be trusted once opened."""


class DowngradeRefused(SealError):
    """A mechanism without post-quantum resistance was used without being asked for."""


def overhead(kem: Kem) -> int:
    """Bytes a sealed message costs beyond its plaintext.

    Exposed so a caller can work out how much content fits in a frame instead
    of discovering it by exception.
    """
    return kem.ciphertext_length + TAG_LENGTH


def _derive(shared_secret: bytes, kem_name: str, context: bytes) -> tuple[bytes, bytes]:
    material = HKDF(
        algorithm=hashes.SHA256(),
        length=_KEY_LENGTH + _NONCE_LENGTH,
        salt=None,
        info=_INFO_PREFIX + kem_name.encode("ascii") + b"/" + context,
    ).derive(shared_secret)
    return material[:_KEY_LENGTH], material[_KEY_LENGTH:]


def _check_post_quantum(kem: Kem, allow_classical_only: bool) -> None:
    if getattr(kem, "post_quantum", False):
        return
    if allow_classical_only:
        return
    raise DowngradeRefused(
        f"{kem.name} offers no post-quantum resistance; pass allow_classical_only=True to mean it",
    )


def seal(
    kem: Kem,
    recipient_public_key: bytes,
    plaintext: bytes,
    context: bytes = b"",
    *,
    allow_classical_only: bool = False,
) -> bytes:
    """Seal ``plaintext`` to the holder of ``recipient_public_key``.

    :param context: bound into the key derivation and the tag. Pass whatever
        must not be swapped — the address the message is going to, say — and a
        ciphertext lifted from one place and replayed into another stops
        opening.
    :raises DowngradeRefused: if the mechanism has no post-quantum half and the
        caller has not said that is what they want.
    """
    _check_post_quantum(kem, allow_classical_only)
    try:
        encapsulation = kem.encapsulate(recipient_public_key)
    except KemError as bad_key:
        raise SealError(f"cannot seal to this recipient: {bad_key}") from bad_key

    key, nonce = _derive(encapsulation.shared_secret, kem.name, context)
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, context)
    return encapsulation.ciphertext + ciphertext


def unseal(
    kem: Kem,
    private_key: bytes,
    sealed: bytes,
    context: bytes = b"",
    *,
    allow_classical_only: bool = False,
) -> bytes:
    """Open a message sealed to us, or refuse.

    Refusal covers every way it can be wrong: too short to hold a ciphertext,
    key material that does not match, a tag that does not verify, a different
    context than the sender used. None of them are distinguished in the error,
    because the difference between "wrong key" and "tampered" is exactly what an
    attacker probing with modified ciphertexts wants told.
    """
    _check_post_quantum(kem, allow_classical_only)
    if len(sealed) < kem.ciphertext_length + TAG_LENGTH:
        raise SealError("the message is too short to be a sealed message")

    encapsulated = sealed[: kem.ciphertext_length]
    ciphertext = sealed[kem.ciphertext_length :]

    try:
        shared_secret = kem.decapsulate(private_key, encapsulated)
        key, nonce = _derive(shared_secret, kem.name, context)
        return AESGCM(key).decrypt(nonce, ciphertext, context)
    except (KemError, InvalidTag, ValueError) as refused:
        raise SealError("the message could not be opened") from refused
