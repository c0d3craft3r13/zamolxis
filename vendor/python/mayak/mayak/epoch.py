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
both by message count and by age, on both ends.

The sender rolls over when either bound is reached. The receiver forgets a root
once it is older than the epoch's lifetime plus :data:`RECEIVE_GRACE_SECONDS`,
keeps at most :data:`MAX_LIVE_EPOCHS` at once, and refuses a counter no honest
sender could have reached. It used to do none of this: the docstring promised
roots were discarded and the receiving side kept every root it had ever learned
for as long as the process lived.

What forgetting buys is exact and narrow: roots extracted from a running
process's memory open only the epochs still live. It does **not** protect
recorded traffic against someone who takes the device itself. The long-term
encapsulation private key is in the device file, and it re-derives the root of
any opening that was recorded. Forward secrecy against seizure needs that key to
change and the old one to be destroyed — see :mod:`mayak.contacts` — and nothing
in this module can provide it.

## A forgotten epoch stays forgotten

The receiver keeps a short record of epochs it retired. Without it, replaying a
captured opening would decapsulate again, rebuild the root, and hand back a
fresh replay window — so every captured message of that epoch could be
delivered a second time. The record lasts as long as a replayed opening could
still pass address binding, which is three address epochs, and is bounded in
size.

## What the receiving side keeps across a restart, and what the sending side does not

A receiver that restarted used to lose every live root, and with them every
continuation of an epoch opened before the restart — a contact mid-epoch could
not reach a device that had merely been switched off and on, for up to a week.
So the receiving side can be exported as :class:`EpochRecord` values and
restored: roots, replay windows, and the record of retired epochs.

Keeping those on disk costs nothing that was not already paid. A receiving root
can be rebuilt from its recorded opening by the key that opened it, and that key
is on disk for exactly as long as the root is live; when the key is destroyed,
its epochs are retired with it and leave the file at the next save.

The sending side is not exported, deliberately. Our outgoing roots are sealed to
the contact's key, not ours: nothing on this device can rebuild them, so writing
them down would give a seized device the one thing it otherwise cannot open —
what this device itself sent. A sender that restarts opens a new epoch instead.

## Replay, and why a window rather than a counter

A mesh reorders. Requiring strictly increasing counters would drop any message
that arrived late, which on a multi-hop route is ordinary rather than
exceptional. So the receiver keeps the highest counter it has seen and a bitmap
of the sixty-four below it: anything inside the window is accepted once and
never twice, anything below it is refused as too old to judge.
"""

from __future__ import annotations

import hashlib
import struct
import time
from collections.abc import Callable, Sequence
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

#: How long past its lifetime the receiver keeps a root.
#:
#: A sender may hold a sealed message for up to :data:`mayak.outbox.MAX_AGE_SECONDS`
#: before giving up on it, so a root has to outlive its epoch by at least that
#: much or held messages would arrive to find it gone. A day covers that and a
#: clock that disagrees by a few hours. A test holds the two constants together.
RECEIVE_GRACE_SECONDS = 24 * 60 * 60

#: Most roots one conversation keeps at once.
#:
#: Every process that sends opens its own epoch, so a contact who runs a
#: short-lived sender several times a day opens several. Four keeps late
#: messages from the last few of them readable and bounds what memory extraction
#: can yield. The oldest goes first.
MAX_LIVE_EPOCHS = 4

#: How long a retired epoch is remembered, so a replayed opening cannot revive it.
#:
#: Openings are bound to an address, and an address is live for at most three
#: address epochs — previous, current and next. After that no replay of the
#: opening can pass binding, and the record has nothing left to guard.
RETIRED_MEMORY_SECONDS = 3 * 24 * 60 * 60

#: Most retired epochs remembered at once. Only the real contact can create
#: epochs, so reaching this means a very busy contact, not an attack.
MAX_RETIRED_EPOCHS = 256

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

    #: Which receiving key opened it, as a tag rather than the key itself — an
    #: epoch that held its key would keep a destroyed key alive in memory.
    opened_with: bytes = b""

    def exhausted(self, now: float) -> bool:
        """Whether this root has protected as much as it should."""
        return self.messages >= MESSAGES_PER_EPOCH or (now - self.created) >= EPOCH_LIFETIME_SECONDS


@dataclass(frozen=True)
class EpochRecord:
    """One receiving epoch as it is kept between runs."""

    identifier: bytes
    root: bytes
    created: float
    opened_with: bytes
    highest: int
    seen: int


def key_tag(private_key: bytes) -> bytes:
    """A short name for a private key that does not contain it."""
    return hashlib.sha256(b"mayak/epoch/key-tag/v1" + private_key).digest()[:8]


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

    def retarget(self, recipient_kem_public: bytes) -> None:
        """Seal to a new key from now on, starting a new epoch under it.

        The current epoch is abandoned rather than finished: its root was
        encapsulated to the old key, and the point of the contact replacing that
        key is that nothing more should depend on it.
        """
        if recipient_kem_public == self._recipient:
            return
        self._recipient = recipient_kem_public
        self._epoch = None
        self._pending_encapsulation = None

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
        kem_private_key: bytes | Callable[[], Sequence[bytes]],
        *,
        clock=time.time,
        allow_classical_only: bool = False,
        on_opening: Callable[[bytes], None] | None = None,
    ) -> None:
        """
        :param kem_private_key: one private key, or a callable returning every
            key still live, newest first. An opening names no key on the wire —
            a key identifier would be a label an observer could follow — so
            each is tried in turn.
        :param on_opening: told which key an opening decapsulated under. That is
            the only proof a contact has a key, and what lets older ones be
            destroyed.
        """
        if not getattr(kem, "post_quantum", False) and not allow_classical_only:
            raise DowngradeRefused(
                f"{kem.name} offers no post-quantum resistance; pass allow_classical_only=True to mean it",
            )
        self._kem = kem
        self._private_keys = kem_private_key if callable(kem_private_key) else (lambda: [kem_private_key])
        self._on_opening = on_opening
        self._clock = clock
        self._epochs: dict[bytes, Epoch] = {}
        self._windows: dict[bytes, ReplayWindow] = {}
        #: identifier -> when it was retired.
        self._retired: dict[bytes, float] = {}

        #: The last opening's ciphertext and what each key made of it. An opening
        #: is tried against every live address in turn, and without this each try
        #: would decapsulate under every key again.
        self._decapsulated: tuple[bytes, list[tuple[bytes, bytes]]] | None = None

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

        self._expire(self._clock())

        kind = sealed[0]
        if kind == OPENING:
            return self._open_opening(sealed, context)
        if kind == CONTINUATION:
            return self._open_continuation(sealed, context)
        raise EpochError("the message could not be opened")

    def forget(self, identifier: bytes) -> None:
        """Drop a root. What it protected can no longer be read here, even replayed."""
        self._retire(identifier, self._clock())

    def records(self) -> list[EpochRecord]:
        """The live receiving epochs, for keeping across a restart.

        Copied before it is walked: a save can run on the caller's thread while a
        frame arriving on the transport's thread changes the epochs underneath.
        """
        windows = dict(self._windows)
        return [
            EpochRecord(
                identifier=identifier,
                root=epoch.root,
                created=epoch.created,
                opened_with=epoch.opened_with,
                highest=windows.get(identifier, ReplayWindow()).highest,
                seen=windows.get(identifier, ReplayWindow()).seen,
            )
            for identifier, epoch in list(self._epochs.items())
        ]

    def retired_records(self) -> dict[bytes, float]:
        """Epochs retired recently enough that a replayed opening must still be refused."""
        return dict(self._retired)

    def restore(self, records: list[EpochRecord], retired: dict[bytes, float]) -> None:
        """Take back what :meth:`records` and :meth:`retired_records` gave.

        Records past their lifetime are dropped here rather than restored and
        expired later, so a device that was off for a month does not hold
        a month-old root for even one call.
        """
        now = self._clock()
        limit = EPOCH_LIFETIME_SECONDS + RECEIVE_GRACE_SECONDS
        for record in records:
            if now - record.created > limit or record.identifier in retired:
                continue
            self._epochs[record.identifier] = Epoch(
                root=record.root,
                identifier=record.identifier,
                created=record.created,
                opened_with=record.opened_with,
            )
            self._windows[record.identifier] = ReplayWindow(highest=record.highest, seen=record.seen)
        self._retired.update(
            {identifier: when for identifier, when in retired.items() if now - when <= RETIRED_MEMORY_SECONDS},
        )

    def forget_opened_with(self, private_key: bytes) -> int:
        """Retire every epoch a destroyed key opened. Returns how many.

        Destroying a key is only half the job while roots it produced are still
        live in memory: they open the rest of their epochs whether the key exists
        or not.
        """
        tag = key_tag(private_key)
        now = self._clock()
        doomed = [identifier for identifier, epoch in self._epochs.items() if epoch.opened_with == tag]
        for identifier in doomed:
            self._retire(identifier, now)
        self._decapsulated = None
        return len(doomed)

    def _retire(self, identifier: bytes, now: float) -> None:
        self._epochs.pop(identifier, None)
        self._windows.pop(identifier, None)
        self._retired[identifier] = now
        while len(self._retired) > MAX_RETIRED_EPOCHS:
            del self._retired[min(self._retired, key=self._retired.__getitem__)]

    def _expire(self, now: float) -> None:
        """Forget roots past their lifetime, and the records of long-retired ones."""
        limit = EPOCH_LIFETIME_SECONDS + RECEIVE_GRACE_SECONDS
        for identifier in [key for key, epoch in self._epochs.items() if now - epoch.created > limit]:
            self._retire(identifier, now)
        for identifier in [key for key, when in self._retired.items() if now - when > RETIRED_MEMORY_SECONDS]:
            del self._retired[identifier]

    def _open_opening(self, sealed: bytes, context: bytes) -> bytes:
        head = 1 + self._kem.ciphertext_length
        if len(sealed) < head + TAG_LENGTH:
            raise EpochError("the message could not be opened")

        opened = self._try_keys(sealed[1:head], sealed[head:], context)
        if opened is None:
            raise EpochError("the message could not be opened")
        private, root, plaintext = opened

        # Replay is checked after the tag, and against the window this epoch
        # already has rather than a fresh one. Building a new window here let a
        # captured opening be delivered over and over: each delivery derived the
        # same root and then wiped the record of the first.
        identifier = _identifier_of(root)
        if identifier in self._retired:
            # Genuine, authenticated, and already finished with. Rebuilding it
            # would hand every captured message of the epoch a clean window.
            raise EpochError("the message could not be opened")

        window = self._windows.setdefault(identifier, ReplayWindow())
        if not window.accept(0):
            raise EpochError("the message could not be opened")

        now = self._clock()
        if identifier not in self._epochs:
            self._epochs[identifier] = Epoch(
                root=root,
                identifier=identifier,
                created=now,
                next_counter=1,
                messages=1,
                opened_with=key_tag(private),
            )
            while len(self._epochs) > MAX_LIVE_EPOCHS:
                oldest = min(
                    (key for key in self._epochs if key != identifier),
                    key=lambda key: self._epochs[key].created,
                )
                self._retire(oldest, now)

        if self._on_opening is not None:
            self._on_opening(private)
        return plaintext

    def _try_keys(self, ciphertext: bytes, sealed: bytes, context: bytes) -> tuple[bytes, bytes, bytes] | None:
        """Find the live key this opening was sealed to, if any.

        ML-KEM does not fail under the wrong key — it returns a different secret —
        so the only test is whether the resulting root opens the tag.
        """
        if self._decapsulated is None or self._decapsulated[0] != ciphertext:
            candidates = []
            for private in self._private_keys():
                try:
                    shared_secret = self._kem.decapsulate(private, ciphertext)
                except KemError:
                    continue
                candidates.append((private, _root_from(shared_secret, self._kem.name)))
            self._decapsulated = (ciphertext, candidates)

        for private, root in self._decapsulated[1]:
            key, nonce = _message_key(root, 0, context)
            try:
                plaintext = AESGCM(key).decrypt(nonce, sealed, context)
            except (InvalidTag, ValueError):
                continue
            self._decapsulated = None
            return private, root, plaintext
        return None

    def _open_continuation(self, sealed: bytes, context: bytes) -> bytes:
        head = 1 + EPOCH_ID_LENGTH + _COUNTER_LENGTH
        if len(sealed) < head + TAG_LENGTH:
            raise EpochError("the message could not be opened")

        identifier = sealed[1 : 1 + EPOCH_ID_LENGTH]
        (counter,) = struct.unpack(">I", sealed[1 + EPOCH_ID_LENGTH : head])
        epoch = self._epochs.get(identifier)
        if epoch is None or counter >= MESSAGES_PER_EPOCH:
            # A sender rolls over before sealing message MESSAGES_PER_EPOCH, so a
            # counter that high did not come from one. Refused before any key is
            # derived for it.
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
