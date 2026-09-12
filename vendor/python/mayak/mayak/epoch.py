"""Encapsulate once, then ride a counter — because the alternative does not fit.

## The measurement that forces this

A Reticulum packet carries 383 bytes. An ML-KEM-768 ciphertext is 1088. So
encapsulating on every message, which is what :mod:`mayak.sealing` does on its
own, cannot survive contact with a real post-quantum mechanism: one message
would need three packets before a single byte of content.

The way out is the one the Android side already measured. Encapsulate once, keep
the shared secret as a root, and derive a fresh key for each message from that
root and a counter. The first message of an epoch is large; every one after it
costs **thirteen bytes** of framing plus the tag.

    opening       0x01 || KEM ciphertext || sealed
    continuation  0x02 || epoch id (8) || counter (4) || sealed

Twenty-nine bytes of overhead on a continuation, against a thousand and more for
re-encapsulating. On a 383-byte packet that is the difference between 350 bytes
of message and none at all.

## Why each message still gets its own key

The root is never used to encrypt anything. Every message derives its own key
and nonce from the root and its counter, so a key is used exactly once and a
nonce can never repeat under a key — which is the failure mode that turns
AES-GCM from an authenticated cipher into a plaintext recovery exercise.

It also means a recovered message key reveals nothing about any other message:
the derivation runs one way, and knowing the key for counter 40 does not give
counter 41.

## Forward secrecy has a shape here, and it is worth being exact about

Compromising the root exposes every message in that epoch, past and future,
because every key in the epoch derives from it. Epochs are therefore bounded
both by message count and by age, and a root is discarded when its epoch ends.
What this buys is that a root taken today does not open last week's traffic. It
does not protect the current epoch — nothing that keeps a usable session open
can.

## Replay, and why a window rather than a counter

A mesh reorders. Requiring strictly increasing counters would drop any message
that arrived late, which on a multi-hop route is ordinary rather than
exceptional. So the receiver keeps the highest counter it has seen and a bitmap
of the sixty-four below it: anything inside the window is accepted once and
never twice, anything below it is refused as too old to judge.
"""

from __future__ import annotations

import struct
import time
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from mayak.kem import Kem, KemError
from mayak.sealing import TAG_LENGTH, DowngradeRefused, SealError

#: The first message of an epoch: carries the encapsulation.
OPENING = 0x01

#: Every message after it: carries the epoch id and a counter.
CONTINUATION = 0x02

#: Bytes identifying an epoch on the wire. Derived from the root, so it reveals
#: nothing about it and two epochs never collide in practice.
EPOCH_ID_LENGTH = 8

_COUNTER_LENGTH = 4
_KEY_LENGTH = 32
_NONCE_LENGTH = 12

#: Framing on a continuation: kind, epoch id, counter. The tag is on top.
CONTINUATION_OVERHEAD = 1 + EPOCH_ID_LENGTH + _COUNTER_LENGTH + TAG_LENGTH

#: How many messages one root may protect before a new encapsulation is due.
#:
#: A bound on blast radius rather than a cryptographic limit — AES-GCM under
#: per-message keys is nowhere near exhaustion at this scale. Five hundred is
#: chosen so that a compromised root costs a bounded conversation, not a month.
MESSAGES_PER_EPOCH = 500

#: And an age bound, for a conversation too quiet to reach the message count.
EPOCH_LIFETIME_SECONDS = 7 * 24 * 60 * 60

#: How far behind the highest counter a late message may still arrive.
REPLAY_WINDOW = 64

_ROOT_INFO = b"mayak/epoch/root/v1"
_ID_INFO = b"mayak/epoch/id/v1"
_MESSAGE_INFO = b"mayak/epoch/message/v1"


class EpochError(SealError):
    """A message could not be sealed or opened under any epoch."""


def _root_from(shared_secret: bytes, kem_name: str) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=_KEY_LENGTH,
        salt=None,
        info=_ROOT_INFO + b"/" + kem_name.encode("ascii"),
    ).derive(shared_secret)


def _identifier_of(root: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=EPOCH_ID_LENGTH, salt=None, info=_ID_INFO).derive(root)


def _message_key(root: bytes, counter: int, context: bytes) -> tuple[bytes, bytes]:
    material = HKDF(
        algorithm=hashes.SHA256(),
        length=_KEY_LENGTH + _NONCE_LENGTH,
        salt=struct.pack(">I", counter),
        info=_MESSAGE_INFO + b"/" + context,
    ).derive(root)
    return material[:_KEY_LENGTH], material[_KEY_LENGTH:]


@dataclass
class Epoch:
    """A root and the counter riding on it."""

    root: bytes
    identifier: bytes
    created: float
    next_counter: int = 0
    messages: int = 0

    def exhausted(self, now: float) -> bool:
        """Whether this root has protected as much as it should."""
        return self.messages >= MESSAGES_PER_EPOCH or (now - self.created) >= EPOCH_LIFETIME_SECONDS


@dataclass
class ReplayWindow:
    """What has already been seen, so nothing is accepted twice."""

    highest: int = -1
    seen: int = 0  # bitmap of the REPLAY_WINDOW counters below `highest`

    def accept(self, counter: int) -> bool:
        """Record ``counter`` and say whether it was new.

        Late is fine; twice is not. A counter more than the window behind the
        highest seen is refused, because there is no longer any record of
        whether it already arrived and guessing would allow a replay.

        Bit ``i`` of :attr:`seen` means the counter ``highest - 1 - i`` has
        already arrived. Advancing the window therefore shifts the bitmap by the
        distance moved *and* marks the counter that used to be highest — the
        first version marked bit zero instead, which is only the same thing when
        the counter advances by exactly one, and silently rejected anything that
        arrived out of order by more.
        """
        mask = (1 << REPLAY_WINDOW) - 1

        if counter > self.highest:
            if self.highest < 0:
                self.seen = 0
            else:
                shift = counter - self.highest
                previous = 1 << (shift - 1) if shift <= REPLAY_WINDOW else 0
                self.seen = ((self.seen << shift) | previous) & mask
            self.highest = counter
            return True

        behind = self.highest - counter
        if behind == 0 or behind >= REPLAY_WINDOW:
            return False
        bit = 1 << (behind - 1)
        if self.seen & bit:
            return False
        self.seen |= bit
        return True


class Sealer:
    """The sending half: opens an epoch when it must, rides the counter otherwise."""

    def __init__(
        self,
        kem: Kem,
        recipient_kem_public: bytes,
        *,
        clock=time.time,
        allow_classical_only: bool = False,
    ) -> None:
        if not getattr(kem, "post_quantum", False) and not allow_classical_only:
            raise DowngradeRefused(
                f"{kem.name} offers no post-quantum resistance; pass allow_classical_only=True to mean it",
            )
        self._kem = kem
        self._recipient = recipient_kem_public
        self._clock = clock
        self._epoch: Epoch | None = None
        self._pending_encapsulation: bytes | None = None

    @property
    def epoch(self) -> Epoch | None:
        """The epoch in use, if one has been opened. Exposed for tests and state."""
        return self._epoch

    @property
    def opening_next(self) -> bool:
        """Whether the next message will have to open an epoch.

        Asked before sealing rather than discovered afterwards, because the
        opening is the one frame that is a different size on the wire and the
        caller may want to decide what rides in it — see
        :class:`mayak.session.Session`.
        """
        return self._epoch is None or self._epoch.exhausted(self._clock())

    def overhead(self) -> int:
        """What the next message will cost beyond its content."""
        if self.opening_next:
            return 1 + self._kem.ciphertext_length + TAG_LENGTH
        return CONTINUATION_OVERHEAD

    def seal(self, plaintext: bytes, context: bytes = b"") -> bytes:
        now = self._clock()
        if self._epoch is None or self._epoch.exhausted(now):
            self._open_epoch(now)

        epoch = self._epoch
        assert epoch is not None  # _open_epoch sets it or raises
        counter = epoch.next_counter
        epoch.next_counter += 1
        epoch.messages += 1

        key, nonce = _message_key(epoch.root, counter, context)
        sealed = AESGCM(key).encrypt(nonce, plaintext, context)

        if counter == 0 and self._pending_encapsulation is not None:
            opening = bytes([OPENING]) + self._pending_encapsulation + sealed
            self._pending_encapsulation = None
            return opening

        return bytes([CONTINUATION]) + epoch.identifier + struct.pack(">I", counter) + sealed

    def _open_epoch(self, now: float) -> None:
        try:
            encapsulation = self._kem.encapsulate(self._recipient)
        except KemError as bad_key:
            raise EpochError(f"cannot open an epoch with this recipient: {bad_key}") from bad_key

        root = _root_from(encapsulation.shared_secret, self._kem.name)
        self._epoch = Epoch(root=root, identifier=_identifier_of(root), created=now)
        self._pending_encapsulation = encapsulation.ciphertext


class Opener:
    """The receiving half: learns roots from openings, then follows counters."""

    def __init__(
        self,
        kem: Kem,
        kem_private_key: bytes,
        *,
        clock=time.time,
        allow_classical_only: bool = False,
    ) -> None:
        if not getattr(kem, "post_quantum", False) and not allow_classical_only:
            raise DowngradeRefused(
                f"{kem.name} offers no post-quantum resistance; pass allow_classical_only=True to mean it",
            )
        self._kem = kem
        self._private = kem_private_key
        self._clock = clock
        self._epochs: dict[bytes, Epoch] = {}
        self._windows: dict[bytes, ReplayWindow] = {}

    @property
    def known_epochs(self) -> int:
        return len(self._epochs)

    def open(self, sealed: bytes, context: bytes = b"") -> bytes:
        """Open a message, whichever kind it is, or refuse.

        Refusals are deliberately identical whatever went wrong. "Unknown
        epoch", "already seen" and "bad tag" are three different things to a
        prober and one thing to a recipient.
        """
        if not sealed:
            raise EpochError("the message could not be opened")

        kind = sealed[0]
        if kind == OPENING:
            return self._open_opening(sealed, context)
        if kind == CONTINUATION:
            return self._open_continuation(sealed, context)
        raise EpochError("the message could not be opened")

    def forget(self, identifier: bytes) -> None:
        """Drop a root. What it protected can no longer be read here."""
        self._epochs.pop(identifier, None)
        self._windows.pop(identifier, None)

    def _open_opening(self, sealed: bytes, context: bytes) -> bytes:
        head = 1 + self._kem.ciphertext_length
        if len(sealed) < head + TAG_LENGTH:
            raise EpochError("the message could not be opened")

        try:
            shared_secret = self._kem.decapsulate(self._private, sealed[1:head])
            root = _root_from(shared_secret, self._kem.name)
            key, nonce = _message_key(root, 0, context)
            plaintext = AESGCM(key).decrypt(nonce, sealed[head:], context)
        except (KemError, InvalidTag, ValueError) as refused:
            raise EpochError("the message could not be opened") from refused

        # Replay is checked after the tag, and against the window this epoch
        # already has rather than a fresh one. Building a new window here let a
        # captured opening be delivered over and over: each delivery derived the
        # same root and then wiped the record of the first.
        identifier = _identifier_of(root)
        window = self._windows.setdefault(identifier, ReplayWindow())
        if not window.accept(0):
            raise EpochError("the message could not be opened")

        self._epochs.setdefault(
            identifier,
            Epoch(root=root, identifier=identifier, created=self._clock(), next_counter=1, messages=1),
        )
        return plaintext

    def _open_continuation(self, sealed: bytes, context: bytes) -> bytes:
        head = 1 + EPOCH_ID_LENGTH + _COUNTER_LENGTH
        if len(sealed) < head + TAG_LENGTH:
            raise EpochError("the message could not be opened")

        identifier = sealed[1 : 1 + EPOCH_ID_LENGTH]
        (counter,) = struct.unpack(">I", sealed[1 + EPOCH_ID_LENGTH : head])
        epoch = self._epochs.get(identifier)
        if epoch is None:
            raise EpochError("the message could not be opened")

        # Authenticate first, then record. The other order looks equivalent and
        # is not: a caller that tries several binding contexts — which is exactly
        # what opening a message against three live addresses does — would have
        # its first wrong guess consume the counter, and the right one would then
        # be refused as a replay of a message that never arrived.
        try:
            key, nonce = _message_key(epoch.root, counter, context)
            plaintext = AESGCM(key).decrypt(nonce, sealed[head:], context)
        except (InvalidTag, ValueError) as refused:
            raise EpochError("the message could not be opened") from refused

        window = self._windows.setdefault(identifier, ReplayWindow())
        if not window.accept(counter):
            raise EpochError("the message could not be opened")
        return plaintext
