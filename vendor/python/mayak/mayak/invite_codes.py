"""Invitations as text a person can send: ``mayak1:`` and the keys, and back.

Shared by the command line and by :mod:`mayak.host`, which an app embeds. It used
to live in the command line, and an app needs exactly the same encoding — two
copies of it would be two places for an invitation from one to be refused by the
other.
"""

from __future__ import annotations

import base64

from mayak.contacts import IDENTITY_KEY_LENGTH, Contact
from mayak.kem import Kem

#: What an invitation looks like when it is pasted into a message or read off a
#: screen. The prefix is there so a person can tell what they have been sent,
#: and so a future layout can be refused rather than misread.
INVITE_PREFIX = "mayak1:"


class InvitationError(ValueError):
    """Text that is not an invitation this build can use."""


def encode(identity_key: bytes, kem_key: bytes) -> str:
    """The two public keys as one string somebody can send."""
    raw = base64.urlsafe_b64encode(identity_key + kem_key).decode("ascii").rstrip("=")
    return INVITE_PREFIX + raw


def decode(text: str, kem: Kem) -> tuple[bytes, bytes]:
    """Take an invitation apart, refusing anything that is not one.

    The encapsulation key length is checked against the mechanism in use. That
    catches two people running different mechanisms here, where it can be said
    plainly, rather than later as messages that never open.
    """
    offered = text.strip()
    if not offered.startswith(INVITE_PREFIX):
        raise InvitationError(f"that does not look like an invitation; they start with {INVITE_PREFIX!r}")

    body = offered[len(INVITE_PREFIX) :]
    try:
        raw = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
    except (ValueError, TypeError) as unreadable:
        raise InvitationError("the invitation is damaged and cannot be read") from unreadable

    expected = IDENTITY_KEY_LENGTH + kem.public_key_length
    if len(raw) != expected:
        raise InvitationError(
            f"the invitation carries {len(raw)} bytes of key and this build expects {expected}.\n"
            f"That usually means the other device is not running {kem.name}.",
        )
    return raw[:IDENTITY_KEY_LENGTH], raw[IDENTITY_KEY_LENGTH:]


def spoken_fingerprint(identity_key: bytes, kem_key: bytes) -> str:
    """The fingerprint of an invitation, by the same route a contact's is computed.

    Through :class:`~mayak.contacts.Contact` rather than a second copy of the
    hash, because the number shown here and the number the other person sees
    must be the same number.
    """
    return Contact(name="me", identity_key=identity_key, kem_key=kem_key).spoken_fingerprint
