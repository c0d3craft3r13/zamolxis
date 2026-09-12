"""Who we can talk to, and the keys that make it possible.

## What a contact is

Two public keys and a name someone chose. The identity key decides which
addresses the pair will use; the encapsulation key decides who can read what is
sent there. Nothing else is needed to hold a conversation — no server, no
directory, no announcement.

## Why the two keys are separate

They rotate on different schedules and for different reasons. An identity key is
the long-term thing a contact *is*: changing it means the pair no longer share a
history and every address changes. An encapsulation key is machinery, replaced
when a device is re-keyed, and replacing it does not disturb the addresses.

Conflating them would tie each to the other's calendar and make the cheap
operation as disruptive as the expensive one.

## Verification is a property of the contact, not a ceremony elsewhere

A key obtained over the air is a key somebody may have substituted. A key read
off a screen in person is not. The difference decides whether a conversation is
protected from the person who introduced the two parties, and it is recorded
here rather than left in a user interface — because code that needs to know
whether a contact was verified should be able to ask.

Nothing here refuses to talk to an unverified contact: that is a decision for
whatever is holding the conversation, and the sensible default differs between
a courier and a journalist. What this guarantees is that the question always has
an answer.

## What it deliberately does not do

No persistence. A contact book that wrote itself to disk would decide the file
format, the encryption, and the wipe behaviour for everything above it — and on
a device that may be seized, those are the decisions that matter most. This
holds contacts in memory and hands them out; storing them is the caller's, with
its own threat model.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterator
from dataclasses import dataclass, replace
from enum import Enum

#: An identity or encapsulation key that is not this long is not a key.
IDENTITY_KEY_LENGTH = 32

#: How many characters of the fingerprint two people compare out loud.
#:
#: Sixteen hexadecimal characters is sixty-four bits: reading them aloud takes a
#: few seconds, and an attacker who must produce a colliding key pair before the
#: comparison finishes cannot. Longer is harder to read and gains nothing
#: against an adversary who cannot forge sixty-four bits in real time.
SPOKEN_FINGERPRINT_CHARACTERS = 16


class Trust(Enum):
    """How the keys got here, which is what decides what they are worth."""

    #: Taken from the air — an introduction, a relay, a message. Usable, and
    #: substitutable by whoever passed it along.
    UNVERIFIED = "unverified"

    #: Read off the other person's screen, or compared aloud and matched. Not
    #: substitutable without the two of them noticing.
    VERIFIED = "verified"

    #: Was verified and no longer matches. Kept rather than deleted so the
    #: mismatch can be shown — silently accepting new keys for a known contact
    #: is exactly the substitution verification exists to catch.
    CHANGED = "changed"


class ContactError(ValueError):
    """A contact could not be made or changed."""


@dataclass(frozen=True)
class Contact:
    """One person we can reach."""

    name: str
    identity_key: bytes
    kem_key: bytes
    trust: Trust = Trust.UNVERIFIED

    def __post_init__(self) -> None:
        if len(self.identity_key) != IDENTITY_KEY_LENGTH:
            raise ContactError(f"an identity key is {IDENTITY_KEY_LENGTH} bytes, not {len(self.identity_key)}")
        if not self.kem_key:
            raise ContactError("a contact with no encapsulation key cannot be written to")
        if not self.name.strip():
            raise ContactError("a contact needs a name somebody can recognise")

    @property
    def fingerprint(self) -> str:
        """What two people compare to know they hold the same keys.

        Covers both keys. A fingerprint over the identity key alone would let
        an encapsulation key be substituted while the comparison still matched,
        which is the substitution that matters — it is the one that decides who
        can read the messages.
        """
        digest = hashlib.sha256(b"mayak/contact/v1" + self.identity_key + self.kem_key).hexdigest()
        return digest[:SPOKEN_FINGERPRINT_CHARACTERS]

    @property
    def spoken_fingerprint(self) -> str:
        """The fingerprint in groups of four, which is how people read it aloud."""
        return " ".join(
            self.fingerprint[at : at + 4] for at in range(0, len(self.fingerprint), 4)
        )


class ContactBook:
    """Everyone this device can talk to, held in memory.

    Keyed by identity key rather than by name: two people may choose the same
    name, and a name is not what addresses are derived from.
    """

    def __init__(self) -> None:
        self._contacts: dict[bytes, Contact] = {}

    def __len__(self) -> int:
        return len(self._contacts)

    def __iter__(self) -> Iterator[Contact]:
        return iter(list(self._contacts.values()))

    def __contains__(self, identity_key: object) -> bool:
        return identity_key in self._contacts

    def add(self, contact: Contact) -> Contact:
        """Record a contact we have not met before.

        :raises ContactError: if this identity is already known. Adding over an
            existing contact would silently replace its keys, which is the
            substitution :meth:`offer_keys` exists to make visible instead.
        """
        if contact.identity_key in self._contacts:
            raise ContactError("this identity is already a contact; offer new keys instead of adding again")
        self._contacts[contact.identity_key] = contact
        return contact

    def get(self, identity_key: bytes) -> Contact | None:
        return self._contacts.get(identity_key)

    def forget(self, identity_key: bytes) -> None:
        """Remove a contact. What was sealed to their keys stays unreadable here."""
        self._contacts.pop(identity_key, None)

    def verify(self, identity_key: bytes, fingerprint: str) -> Contact:
        """Record that the fingerprint was compared in person and matched.

        :raises ContactError: if it does not match. A mismatch is the whole
            point of comparing, so it is never rounded down to a warning.
        """
        contact = self._require(identity_key)
        offered = fingerprint.replace(" ", "").lower()
        if offered != contact.fingerprint:
            raise ContactError("the fingerprint does not match; the keys on the two devices differ")

        updated = replace(contact, trust=Trust.VERIFIED)
        self._contacts[identity_key] = updated
        return updated

    def offer_keys(self, identity_key: bytes, kem_key: bytes) -> Contact:
        """Take a new encapsulation key for a contact we already know.

        A device that is re-keyed sends a new encapsulation key under the old
        one, so this is ordinary. What is not ordinary is it happening to a
        contact whose keys were verified in person: that is either a genuine
        re-key or someone standing in the middle, and the two look identical
        from here.

        So a verified contact becomes :attr:`Trust.CHANGED` rather than staying
        verified or being refused. The conversation keeps working — refusing
        would strand a contact who simply replaced their phone — and anything
        that cares can see that the verification no longer holds.
        """
        contact = self._require(identity_key)
        if not kem_key:
            raise ContactError("a contact with no encapsulation key cannot be written to")
        if kem_key == contact.kem_key:
            return contact

        trust = Trust.CHANGED if contact.trust in (Trust.VERIFIED, Trust.CHANGED) else Trust.UNVERIFIED
        updated = replace(contact, kem_key=kem_key, trust=trust)
        self._contacts[identity_key] = updated
        return updated

    def _require(self, identity_key: bytes) -> Contact:
        contact = self._contacts.get(identity_key)
        if contact is None:
            raise ContactError("no such contact")
        return contact
