"""One message, from plain words to the bytes that leave the device.

## The order, and why it is that order

    derive the address  →  build the frame  →  seal it, bound to the address

Sealing last is what makes the padding worth anything. Pad after sealing and
the padding is outside the ciphertext, where anyone can strip it and read the
real length underneath. Pad first and the fixed size is *inside* what gets
encrypted, so the length an observer measures is the length of the frame — the
same for every message this device has ever sent.

Binding the address into the seal is the other half. The destination is public;
it is the one thing about a message an observer always has. Feeding it into the
key derivation means a ciphertext copied off one address and replayed at another
stops opening, and it costs nothing on the wire because both ends already know
it.

## What is constant, and what is not

The **body** is constant: every message pads to the same plaintext length, so
the content length never reaches the wire.

The **wire size** is constant too, except for the first message of an epoch.
That one carries the encapsulation — with a real ML-KEM that is over a kilobyte
— and there is no honest way to hide it: padding every message to that size
would cost three packets each, and an epoch opens rarely enough that the trade
is not close.

So an observer can tell that a new epoch began. That is what they learn: not
what was said, not how long it was, not who the pair are — that an epoch rolled
over, which happens on a schedule they cannot influence. Stating it plainly is
better than implying a uniformity that does not exist.

## What this layer does not do

It does not decide *when* to send, does not retry, does not store. It turns one
message into one frame and back. Everything about delivery lives above it, which
is what lets delivery be replaced without touching anything that holds a key.
"""

from __future__ import annotations

from dataclasses import dataclass

from mayak import frame
from mayak.addressing import EpochAddress, addresses_in_flight, sending_address
from mayak.epoch import EpochError, Opener, Sealer
from mayak.frame import Kind


class EnvelopeError(ValueError):
    """A message could not be prepared, or could not be trusted once opened."""


@dataclass(frozen=True)
class Outbound:
    """A message ready to hand to the transport."""

    #: Identity key material for the destination this goes to. The caller turns
    #: it into a transport destination; this layer knows nothing about transports.
    address: EpochAddress

    #: The bytes to send: version byte, then the sealed frame.
    wire: bytes


@dataclass(frozen=True)
class Inbound:
    """A message that opened, and what it turned out to be."""

    kind: Kind
    content: bytes
    address: EpochAddress


def content_capacity(body_length: int) -> int:
    """How many bytes of message fit in a body of ``body_length``.

    Constant, whatever the sealing costs: the body is padded to this length
    before anything is encrypted, so an opening and a continuation carry exactly
    the same amount of message.
    """
    return frame.body_capacity(body_length)


def prepare(
    sealer: Sealer,
    pairwise_secret: bytes,
    recipient_public: bytes,
    kind: Kind,
    content: bytes,
    body_length: int,
    when: float | None = None,
) -> Outbound:
    """Turn a message into the bytes that go on the wire.

    :param sealer: holds the epoch. Stateful on purpose — the first message
        opens one and the rest ride its counter, which is what keeps a message
        inside a single packet.
    :param pairwise_secret: from :func:`mayak.addressing.pairwise_secret`, used
        only to find the address.
    :param recipient_public: the contact's long-term public key, which decides
        which of the two directions this is.
    :param body_length: the plaintext length every message pads to. One number
        for the whole link, so no message stands out by being different.
    """
    address = sending_address(pairwise_secret, recipient_public, when)
    body = frame.encode(kind, content, body_length)
    try:
        sealed = sealer.seal(body, context=address.seed)
    except EpochError as refused:
        raise EnvelopeError(f"the message could not be sealed: {refused}") from refused

    return Outbound(address=address, wire=frame.wrap(sealed))


def open_message(
    opener: Opener,
    pairwise_secret: bytes,
    our_public: bytes,
    wire: bytes,
    when: float | None = None,
) -> Inbound:
    """Open a message that arrived for us, or refuse.

    The address it arrived on is not taken on trust from the transport: each of
    the three live addresses is tried as the binding context, and the one that
    opens it is the one it was sent to. A message delivered to the right place
    with the wrong binding does not open, which is what makes the binding worth
    having.

    :raises EnvelopeError: if none of the live addresses opens it. No detail is
        given about which failed — the difference between "wrong key", "wrong
        address", "already seen" and "tampered" is what someone probing with
        modified messages is trying to learn.
    """
    sealed = frame.unwrap(wire)

    for address in addresses_in_flight(pairwise_secret, our_public, when):
        try:
            body = opener.open(sealed, context=address.seed)
        except EpochError:
            continue
        parsed = frame.decode(body)
        return Inbound(kind=parsed.kind, content=parsed.content, address=address)

    raise EnvelopeError("the message did not open under any live address")
