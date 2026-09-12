"""The keys a conversation receives on, and when each of them is destroyed.

## Why this exists

A device has one encapsulation keypair from the day it is created — the one in
its invitation. Every epoch opening a contact sends is encapsulated to it, and
its private half lives in the device file. So whoever takes the device and has
recorded the traffic opens all of it, however long ago it was sent: the root of
every recorded opening is one decapsulation away.

Forgetting epoch roots cannot help with that, because the roots can be rebuilt.
Only destroying the private key that rebuilds them can. That is what this does:
each conversation receives on keys of its own, replaces them on a schedule, and
destroys the old ones once the contact has provably moved on.

## The life of a key

1. **Created** here, and its public half sent to the contact inside the
   conversation — authenticated by the pairwise address it arrives on, so it
   needs no fingerprint comparison of its own.
2. **Confirmed** when an epoch opening decapsulates under it: the contact has
   the key and is using it. Nothing else counts as confirmation — an
   acknowledgement message could be lost, and a delivered opening could not.
3. **Superseded** when a newer key is confirmed. Kept for
   :data:`SUPERSEDED_GRACE_SECONDS` more, because a message sealed under it may
   still be in the contact's outbox or on the way.
4. **Destroyed**: removed from the ring, and so from the device file at its next
   save.

A key the contact never confirms is destroyed anyway after
:data:`MAX_UNCONFIRMED_AGE_SECONDS`. Without that, a contact who went silent
would keep an old key alive for ever — and with it everything ever sealed to it.
The cost is plain: a contact silent for longer than that has to be introduced
again.

## The introduction key

The first key in every ring is the device's own, from its invitation. It is not
this conversation's to destroy — other contacts, and contacts not yet met, still
need it — so it is marked, never written into the ring on disk, and simply stops
being tried once superseded. The first thing a conversation does is replace it,
so what stays exposed to seizure is the first exchange, not the history.

## What this still does not protect

Memory. A running process holds live keys and live epoch roots, and nothing in
Python can promise to erase bytes. And flash: the store is rewritten whole, but
a phone's flash may keep an earlier copy of the file, sealed under the same
passphrase. Both are stated here rather than discovered.
"""

from __future__ import annotations

import hmac
import time
from collections.abc import Callable
from dataclasses import dataclass

from mayak.kem import Kem

#: How long a receiving key serves before a new one is due.
#:
#: The same as an epoch's lifetime, so a quiet conversation replaces its key
#: about as often as it replaces its epoch. Shorter narrows what a seized device
#: gives up, and costs a key update — 1216 bytes, four frames — each time.
KEY_ROTATION_SECONDS = 7 * 24 * 60 * 60

#: How long a superseded key is kept after the contact confirmed a newer one.
#:
#: Long enough for a message the contact sealed under the old key to arrive from
#: its outbox, which holds for up to twenty hours. A test holds the two together.
SUPERSEDED_GRACE_SECONDS = 24 * 60 * 60

#: How long an unconfirmed key may live once a newer one exists.
MAX_UNCONFIRMED_AGE_SECONDS = 30 * 24 * 60 * 60

#: How often the newest key is announced again while the contact has not used it.
#:
#: A key update is several frames, and any of them can be lost; announcing once
#: would leave a conversation stuck on a key the contact never heard about.
#: Announcing on every send would put four frames in front of every message.
KEY_UPDATE_RESEND_SECONDS = 60 * 60

#: Most keys one conversation holds. Reaching it means a contact that has not
#: confirmed anything for several rotations; the oldest non-newest key goes.
MAX_KEYS = 4


@dataclass
class ReceiveKey:
    """One encapsulation keypair this conversation receives on."""

    private: bytes
    public: bytes
    created: float
    confirmed: bool = False
    superseded: float | None = None

    #: The device's invitation key. Never destroyed by a conversation, never
    #: written into a ring on disk.
    introduction: bool = False


class ReceiveKeyring:
    """The receiving keys of one conversation, newest last."""

    def __init__(self, keys: list[ReceiveKey], *, clock: Callable[[], float] = time.time) -> None:
        if not keys:
            raise ValueError("a keyring with no keys cannot receive anything")
        self._keys = list(keys)
        self._clock = clock
        self._last_announced: float | None = None

    @classmethod
    def introduced(cls, private: bytes, public: bytes, *, clock: Callable[[], float] = time.time) -> ReceiveKeyring:
        """A new conversation's ring: only the device's invitation key."""
        return cls([ReceiveKey(private=private, public=public, created=clock(), introduction=True)], clock=clock)

    @property
    def keys(self) -> list[ReceiveKey]:
        return list(self._keys)

    @property
    def newest(self) -> ReceiveKey:
        return self._keys[-1]

    def private_keys(self) -> list[bytes]:
        """Every key still live, newest first — the order openings are tried in."""
        return [key.private for key in reversed(self._keys)]

    def needs_rotation(self) -> bool:
        """Whether a new key is due: the invitation key is still newest, or the newest is old."""
        newest = self.newest
        return newest.introduction or self._clock() - newest.created >= KEY_ROTATION_SECONDS

    def rotate(self, kem: Kem) -> ReceiveKey:
        """Create a new receiving key. The caller announces it."""
        private, public = kem.generate()
        key = ReceiveKey(private=private, public=public, created=self._clock())
        self._keys.append(key)
        self._last_announced = None
        return key

    def announcement_due(self) -> bool:
        """Whether the newest key should be sent to the contact now."""
        if self.newest.confirmed or self.newest.introduction:
            return False
        return self._last_announced is None or self._clock() - self._last_announced >= KEY_UPDATE_RESEND_SECONDS

    def announced(self) -> None:
        self._last_announced = self._clock()

    def confirm(self, private: bytes) -> bool:
        """An opening decapsulated under this key. Returns whether anything changed."""
        position = next(
            (index for index, key in enumerate(self._keys) if hmac.compare_digest(key.private, private)),
            None,
        )
        if position is None:
            return False

        changed = False
        now = self._clock()
        if not self._keys[position].confirmed:
            self._keys[position].confirmed = True
            changed = True
        for older in self._keys[:position]:
            if older.superseded is None:
                older.superseded = now
                changed = True
        return changed

    def expire(self) -> list[ReceiveKey]:
        """Destroy keys whose time is up. Returns the destroyed keys.

        The newest key is never destroyed: a ring must always be able to receive.
        Returning what went lets the caller retire the epochs those keys opened —
        a destroyed key whose roots lived on in memory would be destroyed in name.
        """
        now = self._clock()
        newest = self.newest
        destroyed = [
            key
            for key in self._keys
            if key is not newest
            and (
                (key.superseded is not None and now - key.superseded > SUPERSEDED_GRACE_SECONDS)
                or now - key.created > MAX_UNCONFIRMED_AGE_SECONDS
            )
        ]
        survivors = [key for key in self._keys if all(key is not gone for gone in destroyed)]
        while len(survivors) > MAX_KEYS:
            destroyed.append(survivors.pop(0))
        self._keys = survivors
        return destroyed
