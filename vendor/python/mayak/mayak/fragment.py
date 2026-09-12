"""Splitting what will not fit, and putting it back together.

## Why this exists

A Reticulum packet carries 383 bytes. An epoch opening carries an ML-KEM
ciphertext of 1088. No arrangement of the body makes that fit, so a message that
opens an epoch has to travel in pieces.

It is the transport's problem rather than the protocol's: what fits in a packet
is a property of the network, and a protocol that knew about it would have to be
edited every time the network changed. Everything above hands down whole
messages and is never told whether they were split.

## The shape

    whole      0x00 || message
    fragment   0x01 || transfer (4) || index (1) || total (1) || piece

A message that fits pays one byte. One that does not pays seven per piece.
Marking the whole case rather than always fragmenting keeps the common message —
every message after the first of an epoch — at its full size.

Two bytes for index and total cap a transfer at 255 pieces, which at this packet
size is about ninety kilobytes. That is far past anything this protocol sends in
one message, and small enough that a malicious `total` cannot make a receiver
reserve much.

## What it leaks, and what it must not

A watcher sees how many pieces a message took, which is its size class. That is
already visible from the packet count and cannot be hidden without sending
padding packets that carry nothing — a trade that belongs to the emission
policy, not here.

What it must not do is let anyone else's pieces into a reassembly. The transfer
identifier is random per message, so pieces cannot be steered into another
transfer by guessing; and a piece that arrives for a transfer nobody started is
kept only until the deadline, so a flood of them costs bounded memory rather
than unbounded.
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field

WHOLE = 0x00
PIECE = 0x01

#: transfer id, index, total.
_TRANSFER_ID_LENGTH = 4
FRAGMENT_HEADER = 1 + _TRANSFER_ID_LENGTH + 1 + 1

#: What a message that fits pays.
WHOLE_HEADER = 1

MAX_PIECES = 255

#: How long an incomplete transfer is held before it is given up on.
#:
#: Long enough for a slow multi-hop route to finish; short enough that pieces
#: nobody will complete do not accumulate. A receiver under flood drops the
#: oldest first, so this also bounds what an attacker can pin down.
REASSEMBLY_TIMEOUT_SECONDS = 120.0

#: Most incomplete transfers held at once, whatever the timeout says.
MAX_PENDING_TRANSFERS = 32


class FragmentError(ValueError):
    """A piece could not be parsed, or a message could not be split."""


def split(message: bytes, packet_size: int) -> list[bytes]:
    """Cut ``message`` into pieces that each fit ``packet_size``.

    A message that fits comes back as a single whole-marked packet, which is the
    common case and costs one byte.
    """
    if packet_size <= FRAGMENT_HEADER:
        raise FragmentError(f"a packet of {packet_size} bytes has no room for a fragment header")

    if len(message) + WHOLE_HEADER <= packet_size:
        return [bytes([WHOLE]) + message]

    room = packet_size - FRAGMENT_HEADER
    pieces = [message[at : at + room] for at in range(0, len(message), room)]
    if len(pieces) > MAX_PIECES:
        raise FragmentError(f"{len(pieces)} pieces exceeds the {MAX_PIECES} a transfer may have")

    transfer = os.urandom(_TRANSFER_ID_LENGTH)
    total = len(pieces)
    return [
        bytes([PIECE]) + transfer + bytes([index]) + bytes([total]) + piece for index, piece in enumerate(pieces)
    ]


@dataclass
class _Pending:
    total: int
    pieces: dict[int, bytes] = field(default_factory=dict)
    started: float = 0.0

    def complete(self) -> bool:
        return len(self.pieces) == self.total


class Reassembler:
    """Collects pieces until a message is whole again.

    Holds no keys and reads nothing: every piece it handles is ciphertext, and
    a transfer it cannot complete is forgotten rather than reported. A caller
    that wanted to know about losses would be asking the wrong layer — the
    sender learns from the absence of a reply, not from the transport.
    """

    def __init__(self, clock=time.time) -> None:
        self._clock = clock
        self._pending: dict[bytes, _Pending] = {}

        #: Transfers given up on. Counted so a test can prove they were, and so
        #: an operator can see a link that is losing pieces rather than guessing.
        self.abandoned = 0

    @property
    def pending(self) -> int:
        return len(self._pending)

    def accept(self, packet: bytes) -> bytes | None:
        """Take one packet; return the message if this completed it.

        :raises FragmentError: on a packet that is not a fragment at all. A
            transport that received rubbish should count it, not reassemble it.
        """
        if not packet:
            raise FragmentError("an empty packet carries nothing")

        # Before the marker check, not after: expiry used to run only when a
        # fragment arrived, so a device that received nothing but whole messages
        # held abandoned pieces until the next fragmented one — which on a quiet
        # link could be never.
        self._expire()

        marker = packet[0]
        if marker == WHOLE:
            return packet[1:]
        if marker != PIECE:
            raise FragmentError(f"unknown fragment marker {marker}")

        if len(packet) <= FRAGMENT_HEADER:
            raise FragmentError("a fragment with no content is not a fragment")

        transfer = packet[1 : 1 + _TRANSFER_ID_LENGTH]
        index = packet[1 + _TRANSFER_ID_LENGTH]
        total = packet[2 + _TRANSFER_ID_LENGTH]
        piece = packet[FRAGMENT_HEADER:]

        if total == 0 or index >= total:
            raise FragmentError("the piece does not fit the transfer it claims")

        held = self._pending.get(transfer)
        if held is None:
            self._make_room()
            held = _Pending(total=total, started=self._clock())
            self._pending[transfer] = held
        elif held.total != total:
            # Two transfers claiming the same identifier disagree about its
            # size. One of them is not what it says, and neither can be trusted.
            del self._pending[transfer]
            self.abandoned += 1
            return None

        held.pieces[index] = piece
        if not held.complete():
            return None

        del self._pending[transfer]
        return b"".join(held.pieces[position] for position in range(held.total))

    def _expire(self) -> None:
        deadline = self._clock() - REASSEMBLY_TIMEOUT_SECONDS
        stale = [transfer for transfer, held in self._pending.items() if held.started <= deadline]
        for transfer in stale:
            del self._pending[transfer]
            self.abandoned += 1

    def _make_room(self) -> None:
        """Drop the oldest transfer when too many are open at once.

        Without this, pieces of transfers that will never complete accumulate
        for as long as the timeout allows, which is a cheap way to make a
        receiver hold memory on someone else's behalf.
        """
        while len(self._pending) >= MAX_PENDING_TRANSFERS:
            oldest = min(self._pending, key=lambda transfer: self._pending[transfer].started)
            del self._pending[oldest]
            self.abandoned += 1
