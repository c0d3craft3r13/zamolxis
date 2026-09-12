"""The shape every message has on the wire.

This is the one layer that decides whether two messages can be told apart by
someone who cannot read either of them. Everything else — who signs, how keys
are agreed, where it is delivered — sits above or below it.

Two rules follow from that, and they are the whole design:

**Every frame is the same size.** Not "usually", not "when padded" — the body
is a fixed length chosen at construction, and a shorter message is padded to
reach it. A watcher counting bytes learns nothing about what was said, because
"hello" and a two-hundred-character order leave the device identically sized.

**Nothing that distinguishes one message from another is outside the sealed
part.** In particular the kind — real message, cover traffic, control — lives
*inside* the body, never in a header. A kind byte in the clear would let anyone
sort cover traffic from real traffic with a single comparison, which would make
the cover worthless while still costing the airtime to send it.

So the wire form is deliberately almost empty:

    version (1 byte) || body (fixed length)

The version is outside because a receiver has to know how to parse before it
can decrypt, and because it says nothing about the message: every frame this
device sends carries the same value.

The body, before it is sealed, is:

    kind (1) || content length (2, big-endian) || content || random padding

The padding is random rather than zeroes. Anything that compresses this body
later would collapse a run of zeroes and hand back a frame whose size depends
on its content, which is exactly the property being bought here.
"""

from __future__ import annotations

import os
import struct
from dataclasses import dataclass
from enum import IntEnum

#: Wire format version. Present so a receiver can refuse what it cannot parse.
VERSION = 1

#: version byte
_HEADER_LEN = 1

#: kind byte + two length bytes
_BODY_PREFIX_LEN = 3


class Kind(IntEnum):
    """What a frame is for.

    Carried inside the body, never in the clear. A frame's kind is only
    readable by someone who can already read the message.
    """

    #: Something a person wrote, to be shown to a person.
    MESSAGE = 1

    #: Sent to say nothing, so that saying something is less noticeable.
    COVER = 2

    #: Protocol housekeeping between the two ends; never shown to a person.
    CONTROL = 3


class FrameError(ValueError):
    """A frame could not be built or could not be trusted once parsed."""


@dataclass(frozen=True)
class Frame:
    """A parsed frame: what was sent, and what it was for."""

    kind: Kind
    content: bytes


def body_capacity(body_len: int) -> int:
    """How many bytes of content fit in a body of ``body_len``.

    Exposed because a caller has to be able to ask before it builds, rather
    than discovering by exception that its message was one byte too long.
    """
    if body_len <= _BODY_PREFIX_LEN:
        raise FrameError(f"a body of {body_len} bytes has no room for content")
    return body_len - _BODY_PREFIX_LEN


def encode(kind: Kind, content: bytes, body_len: int) -> bytes:
    """Build the plaintext body of a frame, padded to exactly ``body_len``.

    The result still has to be sealed before it goes anywhere: this function
    decides shape, not secrecy.

    :raises FrameError: if the content cannot fit, rather than truncating it.
        Silently dropping the tail of a message is the kind of failure nobody
        notices until it matters.
    """
    capacity = body_capacity(body_len)
    if len(content) > capacity:
        raise FrameError(f"content of {len(content)} bytes exceeds the {capacity} available")

    prefix = struct.pack(">BH", int(kind), len(content))
    padding = os.urandom(capacity - len(content))
    return prefix + content + padding


def decode(body: bytes) -> Frame:
    """Read back a body produced by :func:`encode`.

    :raises FrameError: if the body is too short to hold its own header, or
        claims a content length that runs past its end. Both mean the bytes are
        not what they say they are, and guessing at a repair would hand a
        caller content that was never sent.
    """
    if len(body) < _BODY_PREFIX_LEN:
        raise FrameError(f"a body of {len(body)} bytes is too short to parse")

    kind_value, content_len = struct.unpack(">BH", body[:_BODY_PREFIX_LEN])
    end = _BODY_PREFIX_LEN + content_len
    if end > len(body):
        raise FrameError(f"body claims {content_len} bytes of content but holds {len(body) - _BODY_PREFIX_LEN}")

    try:
        kind = Kind(kind_value)
    except ValueError as unknown:
        raise FrameError(f"unknown frame kind {kind_value}") from unknown

    return Frame(kind=kind, content=body[_BODY_PREFIX_LEN:end])


def wrap(body: bytes) -> bytes:
    """Put the version byte in front of a sealed body, ready to transmit."""
    return bytes([VERSION]) + body


def unwrap(wire: bytes) -> bytes:
    """Take the version byte off a received frame and return the sealed body.

    :raises FrameError: on a version this build does not implement. Parsing an
        unknown layout would produce content nobody sent.
    """
    if len(wire) < _HEADER_LEN:
        raise FrameError("an empty frame carries no version")
    if wire[0] != VERSION:
        raise FrameError(f"frame version {wire[0]} is not version {VERSION}")
    return wire[_HEADER_LEN:]
