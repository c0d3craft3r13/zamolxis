"""Invitations that can be used once, by one contact, and then only by them.

## The exposure this removes

An invitation used to carry the device's own encapsulation key, and so every
contact's first messages were sealed to a key the device kept for as long as it
existed — because every other contact, and every contact not yet met, needed it
too. Key rotation protected everything after the first exchange and nothing in
it: whoever took the device later still opened the beginning of every
conversation it ever had.

Now each invitation carries a key made for it alone. Whoever first opens an epoch
under it claims it for their conversation — see :meth:`InvitationPool.claim` —
and from then on it is that conversation's key, destroyed on that
conversation's schedule once it has moved on. Nothing about a first exchange
outlives the conversation's own control any more.

## One contact, once

Claiming removes the key from the pool, so no other conversation tries it again.
An invitation that reaches the wrong person as well as the right one is used by
whichever writes first — and the other's first messages simply do not open,
which is how someone finds out an invitation was intercepted.

Only a known contact can claim a key: an opening arrives on an address derived
from a contact's identity, so a stranger holding a copy of an invitation has
nowhere to send it.

## Unused invitations do not live for ever

An invitation nobody takes up is destroyed after :data:`MAX_INVITATION_AGE_SECONDS`,
and at most :data:`MAX_OPEN_INVITATIONS` are open at once. An invitation is a key
waiting to be sealed to; one that waits indefinitely is one more private key on a
device that may be taken.

## A contact who never seals to the invitation still claims it

If we write first, the contact learns our rotated key before they ever need the
invitation key, and never seal anything to it — so no opening can claim it. Every
key update therefore carries a :func:`tag_of` the key the sender was introduced
to us with. The tag claims the invitation just as an opening would, and costs
nothing on the air: sixteen bytes inside frames the new key already fills.

A tag naming an invitation someone else already claimed, or one that expired, or
one never issued here, marks the conversation disputed. That is the visible half
of "one contact, once".

## Fingerprints follow the invitation

A contact's fingerprint covers the key they were introduced with, so each
invitation has a fingerprint of its own. The device learns which invitation a
contact used from their first opening or their first key update, and from then
on can show "you, as they know you" — the number that contact will read back.
"""

from __future__ import annotations

import hashlib
import hmac
import time
from collections.abc import Callable
from dataclasses import dataclass

from mayak.kem import Kem

#: How long an invitation stays usable if nobody uses it.
MAX_INVITATION_AGE_SECONDS = 30 * 24 * 60 * 60

#: Most invitations open at once. The oldest goes first.
#:
#: Every open invitation is a key every conversation without proof of its own has
#: to try on each epoch opening. Sixteen keeps that to a handful of decapsulations
#: once per epoch.
MAX_OPEN_INVITATIONS = 16


#: Bytes of an invitation tag: enough to tell sixteen invitations apart with no
#: realistic collision, short enough to ride inside a key update for free.
TAG_LENGTH = 16


def tag_of(public_key: bytes) -> bytes:
    """What names an introduction key inside a conversation, without repeating it."""
    return hashlib.sha256(b"mayak/invitation/tag/v1" + public_key).digest()[:TAG_LENGTH]


@dataclass(frozen=True)
class InvitationKey:
    """The encapsulation keypair behind one invitation that nobody has used yet."""

    private: bytes
    public: bytes
    created: float


class InvitationPool:
    """Invitations handed out and not yet claimed."""

    def __init__(self, keys: list[InvitationKey] | None = None, *, clock: Callable[[], float] = time.time) -> None:
        self._keys = list(keys or [])
        self._clock = clock

    @property
    def keys(self) -> list[InvitationKey]:
        return list(self._keys)

    def __len__(self) -> int:
        return len(self._keys)

    def create(self, kem: Kem) -> InvitationKey:
        """A new invitation key. The caller saves before handing the invitation out."""
        private, public = kem.generate()
        key = InvitationKey(private=private, public=public, created=self._clock())
        self._keys.append(key)
        return key

    def open_private_keys(self) -> list[bytes]:
        """What a conversation without proof of its own tries, newest first."""
        return [key.private for key in reversed(self._keys)]

    def claim(self, private: bytes) -> InvitationKey | None:
        """Take an invitation key for one conversation, so no other can use it."""
        for key in self._keys:
            if hmac.compare_digest(key.private, private):
                self._keys.remove(key)
                return key
        return None

    def claim_by_tag(self, tag: bytes) -> InvitationKey | None:
        """Take the invitation a contact says they were introduced with.

        A contact who received a key update from us before ever writing never
        seals anything to the invitation key, so an opening cannot claim it; the
        tag they send does instead.
        """
        for key in self._keys:
            if hmac.compare_digest(tag_of(key.public), tag):
                self._keys.remove(key)
                return key
        return None

    def expire(self) -> list[InvitationKey]:
        """Destroy invitations nobody used in time, and any beyond the limit."""
        now = self._clock()
        destroyed = [key for key in self._keys if now - key.created > MAX_INVITATION_AGE_SECONDS]
        survivors = [key for key in self._keys if all(key is not gone for gone in destroyed)]
        while len(survivors) > MAX_OPEN_INVITATIONS:
            destroyed.append(survivors.pop(0))
        self._keys = survivors
        return destroyed
