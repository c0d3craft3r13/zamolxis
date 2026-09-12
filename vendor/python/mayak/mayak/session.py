"""A conversation with one contact: what to send, where, and what to ignore.

## What a session is

Everything needed to talk to one person, and nothing about anyone else. Two
long-term keypairs — ours and theirs — a mechanism, a transport, and the body
length the link uses. From those it can work out every address the pair will
ever use, in either direction, at any moment, without asking anybody.

Sessions are deliberately per-contact rather than per-device. The old design had
one address for everyone because the layer underneath allowed only one; here a
device simply holds as many sessions as it has contacts, and the addresses of
one tell an observer nothing about the others.

## Two pieces of state, and why they are separate

A session holds a **sealer** and an **opener**. The sealer owns the epoch we
send under; the opener owns the epochs we have been told about. They are not
one object because the two directions are not one conversation: each side opens
its own epoch when it needs to, and neither waits for the other.

## Cover traffic never reaches the caller

A frame marked as cover is opened, checked, and dropped. It does not reach
:attr:`on_message`, it is not stored, and nothing above this layer can be
written in a way that shows it to anyone — which is the failure that would make
the whole idea pointless, because an empty message appearing in a conversation
is worse than no cover at all.

This is built in rather than left to callers to remember. The Android app
learned that the hard way: its filter for side-channel messages had to be
centralised after two code paths disagreed about it and empty bubbles reached
the screen.

## A message longer than a frame becomes several frames

Frames are one size, and that size is small — a few hundred bytes on a
Reticulum packet. A person writing a paragraph does not care. So a message that
does not fit is cut into pieces, each sealed into its own full-size frame, and
put back together on the other side.

This is :mod:`mayak.fragment` used a second time, at a different layer and for a
different reason. The transport splits what the *network* cannot carry; this
splits what a *frame* cannot hold. Sharing the code is deliberate — two
implementations of reassembly is two sets of bugs — but the layers stay separate,
because a frame size is a property of this protocol and a packet size is a
property of somebody else's network.

Every message pays one byte for the marker that says it was not split. That is
the cost of the common case never having to carry a piece count, and of a long
message being indistinguishable from several short ones: the frames are the same
size either way, and how many there are is all a watcher gets.

A partial send — some pieces handed over, the rest held because the contact
became unreachable mid-message — completes on the next :meth:`flush`, provided
it happens before the receiver gives up on the transfer. It is not lost quietly:
the pieces are in the outbox like any other held message.

## An epoch is opened by a frame that says nothing

The first frame of an epoch carries the encapsulation, so it is larger on the
wire than every frame after it. Letting a real message ride in it would undo
most of what fixed-size framing is for: the first message of every epoch would
be identifiable by length alone, and a watcher who can see where a conversation
starts can see a great deal.

So the epoch is opened by a cover frame. Every frame that carries a message is
then a continuation, all the same size, and none of them is the one that
started anything.

It also makes reassembly work. A continuation cannot be opened before the
epoch it belongs to is known, so a long message whose first piece opened the
epoch would be destroyed by a network that reordered two packets. With the
epoch already open, the pieces are order-independent — which is what the replay
window was built to allow.

The same opening frame goes out :data:`EPOCH_OPENING_REPEATS` times — the same
bytes, not a second frame. Losing it is not the loss of one message: nothing in
the epoch can be opened without it, and there is no feedback channel to ask for
it again, so a single dropped packet would silence a conversation until the
epoch expired. The copy costs one frame of airtime and is refused by the
receiver's replay window, which is exactly what that window is for.

## An unreachable contact is held, not lost

A mesh has no uptime guarantee, so a send that cannot be handed over is kept and
retried rather than reported as a failure the user has to act on. The retries
back off and wander, because a device hammering an absent contact on a fixed
schedule is a beacon — see :mod:`mayak.outbox`.

Retrying is driven, never timed. The session does not run a loop: whatever
decides when this device may transmit calls :meth:`flush`, so the queue cannot
transmit behind that decision's back.

## Rollover is the caller's to schedule

A session knows when its addresses change and will tell anyone who asks, but it
owns no timer. Waking a device is a decision with battery and emission
consequences, and those belong to whatever is managing the radio — not to a
conversation.

## One clock, held by the session

Time is taken from a clock the session is given rather than passed into each
call. Sending can afford a parameter; receiving cannot — a transport hands back
bytes whenever they arrive and has no idea what time the caller thinks it is. A
session whose sends and receives could disagree about the epoch would work in
every test that only sent, and fail on a real device at midnight.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, replace

from mayak.addressing import EPOCH_SECONDS, EpochAddress, addresses_in_flight, epoch_at, pairwise_secret
from mayak.envelope import EnvelopeError, Inbound, content_capacity, open_message, prepare
from mayak.epoch import Opener, Sealer
from mayak.fragment import WHOLE, WHOLE_HEADER, FragmentError, Reassembler, split
from mayak.frame import Kind
from mayak.kem import Kem
from mayak.outbox import Outbox
from mayak.transport import Transport, TransportError

#: Called with a message a person should see. Cover never reaches it.
MessageHandler = Callable[[Inbound], None]

#: How many copies of the frame that opens an epoch are sent.
#:
#: Two, not one: without the opening nothing in the epoch can be read, and
#: nothing in this protocol can ask for it again. Two, not more: each copy is
#: airtime spent on a frame that carries nothing, and it takes two independent
#: losses rather than one to lose the conversation.
EPOCH_OPENING_REPEATS = 2


@dataclass(frozen=True)
class Peer:
    """The long-term public keys of the other end.

    Two keys, not one. The identity key decides which addresses the pair uses;
    the encapsulation key decides who can read what is sent there. Keeping them
    apart means either can be rotated on its own schedule.
    """

    public_key: bytes
    kem_public_key: bytes


@dataclass(frozen=True)
class Us:
    """Our own halves of the same two pairs."""

    private_key: bytes
    public_key: bytes
    kem_private_key: bytes
    kem_public_key: bytes


class Session:
    """One conversation, addressed by derivation rather than by announcement."""

    def __init__(
        self,
        kem: Kem,
        us: Us,
        peer: Peer,
        transport: Transport,
        body_length: int,
        on_message: MessageHandler,
        *,
        clock: Callable[[], float] = time.time,
        allow_classical_only: bool = False,
    ) -> None:
        self._us = us
        self._peer = peer
        self._transport = transport
        self._body_length = body_length
        self._on_message = on_message
        self._clock = clock
        self._secret = pairwise_secret(us.private_key, peer.public_key)
        self._listening_epoch: int | None = None

        self._outbox = Outbox(clock=clock)

        #: Pieces of messages too long for one frame. Fed only with content that
        #: has already been opened and authenticated, so nothing a stranger
        #: sends can enter a transfer.
        self._pieces = Reassembler(clock=clock)

        self._sealer = Sealer(
            kem,
            peer.kem_public_key,
            clock=clock,
            allow_classical_only=allow_classical_only,
        )
        self._opener = Opener(
            kem,
            us.kem_private_key,
            clock=clock,
            allow_classical_only=allow_classical_only,
        )

        #: Frames that arrived and did not open. Not an error to be raised — a
        #: destination anyone can send to will receive noise, and a session that
        #: threw on every stray packet would be trivial to shut down remotely.
        self.unopened = 0

        #: Cover frames received and dropped. Counted only so a test can prove
        #: they arrived and still did not reach the caller.
        self.cover_received = 0

        #: Messages this session could not hand to the network and kept instead.
        #: Exposed so a caller can tell "sent" from "held" without reaching into
        #: the outbox — the difference is the whole of what a user is told.
        self.held = 0

    @property
    def waiting(self) -> int:
        """Messages held for a contact who could not be reached."""
        return len(self._outbox)

    @property
    def capacity(self) -> int:
        """The longest message that fits in one frame on this link.

        Longer is not refused — it is split (see the module docstring). This is
        the threshold at which a message starts costing more than one frame,
        which is what a caller that cares about airtime wants to know.
        """
        return content_capacity(self._body_length) - WHOLE_HEADER

    def listen(self) -> list[EpochAddress]:
        """Start accepting messages at every address currently live.

        Safe to call repeatedly; each call replaces the previous set, so an
        address that has fallen out of the window stops being listened on.
        """
        now = self._clock()
        live = addresses_in_flight(self._secret, self._us.public_key, now)
        self._transport.listen([address.seed for address in live], self._receive)
        self._listening_epoch = epoch_at(now)
        return live

    def stop(self) -> None:
        self._transport.stop()
        self._listening_epoch = None

    def send(self, content: bytes) -> EpochAddress:
        """Send a message a person wrote."""
        return self._send(Kind.MESSAGE, content)

    def send_cover(self) -> EpochAddress:
        """Send a message that says nothing, shaped exactly like one that does."""
        return self._send(Kind.COVER, b"")

    def needs_rollover(self) -> bool:
        """Whether the live addresses have moved on since :meth:`listen`."""
        return self._listening_epoch is not None and epoch_at(self._clock()) != self._listening_epoch

    def seconds_until_rollover(self) -> float:
        """How long the current addresses remain live.

        Offered so a caller can schedule its own wake-up. The session does not
        set a timer: waking a device costs battery and, on a radio, exposure.
        """
        now = self._clock()
        return (epoch_at(now) + 1) * EPOCH_SECONDS - now

    def flush(self) -> int:
        """Try again for everything held. Returns how many got through.

        Called by whatever owns the decision to transmit, rather than from a
        timer of the session's own — a queue that retried on its own schedule
        would be putting packets on the air behind that decision's back.
        """
        return len(self._outbox.attempt(self._transport.send).sent)

    def _send(self, kind: Kind, content: bytes) -> EpochAddress:
        """Seal a message into one frame, or into as many as it takes.

        Returns the address the first frame went to. The rest go to the same
        one — an epoch that rolled over between two pieces of one message would
        change it, which is why the receiver tries every live address rather
        than trusting the first.
        """
        if self._sealer.opening_next:
            # Opened by cover, so no message ever rides the one frame that is a
            # different size — see the module docstring.
            opening = self._open_epoch()
            if kind is Kind.COVER:
                # What just opened the epoch already says nothing. A further
                # cover frame would be airtime spent to repeat it.
                return opening

        records = split(content, content_capacity(self._body_length))
        first: EpochAddress | None = None
        for record in records:
            address = self._emit(kind, record)
            first = first or address
        assert first is not None  # split never returns an empty list
        return first

    def _open_epoch(self) -> EpochAddress:
        """Open an epoch with a frame that says nothing, sent more than once.

        The same bytes each time, which is the point: a second *frame* would be
        a continuation and no use at all if the opening were the packet that got
        lost. The copy is refused by the receiver's replay window.

        Cover goes through here too. A cover frame that opened an epoch on its
        own would put the whole epoch on one packet nobody would ever repeat,
        which is the failure this exists to remove.
        """
        outbound = prepare(
            self._sealer,
            self._secret,
            self._peer.public_key,
            Kind.COVER,
            bytes([WHOLE]),
            self._body_length,
            self._clock(),
        )
        for _ in range(EPOCH_OPENING_REPEATS):
            try:
                self._transport.send(outbound.address.seed, outbound.wire)
            except TransportError:
                # Held once, not once per copy: the outbox would otherwise send
                # the same frame twice again on every retry, for ever.
                self._outbox.hold(outbound.address.seed, outbound.wire)
                self.held += 1
                break
        return outbound.address

    def _emit(self, kind: Kind, record: bytes) -> EpochAddress:
        """Seal one record into one frame and hand it over, or hold it."""
        outbound = prepare(
            self._sealer,
            self._secret,
            self._peer.public_key,
            kind,
            record,
            self._body_length,
            self._clock(),
        )
        try:
            self._transport.send(outbound.address.seed, outbound.wire)
        except TransportError:
            # Held rather than raised. A contact behind a hill is ordinary, and
            # the sealed bytes are already addressed — there is nothing to
            # recompute later except the attempt itself.
            self._outbox.hold(outbound.address.seed, outbound.wire)
            self.held += 1
        return outbound.address

    def _receive(self, wire: bytes) -> None:
        try:
            inbound = open_message(
                self._opener,
                self._secret,
                self._us.public_key,
                wire,
                self._clock(),
            )
        except (EnvelopeError, ValueError):
            # Anyone can send bytes to a destination. Noise is expected, not
            # exceptional, and raising here would let a stranger stop the session.
            self.unopened += 1
            return

        if inbound.kind is Kind.COVER:
            # Before reassembly, not after. Cover that could contribute a piece
            # to a real message would be cover that changes what someone reads.
            self.cover_received += 1
            return

        try:
            content = self._pieces.accept(inbound.content)
        except FragmentError:
            # The seal held, so this came from the contact — but it is not a
            # piece of anything. A build that sent something this one cannot
            # parse is a mismatch to count, not a reason to stop.
            self.unopened += 1
            return

        if content is None:
            return

        self._on_message(replace(inbound, content=content))
