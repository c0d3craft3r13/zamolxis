"""Carrying frames over Reticulum, without telling it anything.

## What Reticulum is used for, and what it is not

It moves bytes and finds routes. That is all it is asked to do here. It is not
relied on for confidentiality, for authenticity, or for hiding who is talking to
whom — those are settled above, in :mod:`mayak.sealing` and
:mod:`mayak.addressing`, before a byte reaches this module.

That separation is deliberate and worth keeping. If Reticulum changes, or is
replaced, or turns out to have a flaw in its own encryption, nothing that
protects a message changes with it.

## The destination keypair is known to both ends

An address here is derived from the pairwise secret, so *both* parties can
compute the identity that owns it — including its private key. Reticulum's own
encryption to that destination therefore gives the pair nothing, because each of
them can undo it.

This is by design, not an oversight. What matters is that nobody else can derive
the seed, so nobody else can register the destination, listen on it, or read
Reticulum's layer either. The message itself is sealed to encapsulation keys
that only the recipient holds, and that seal is what confidentiality rests on.

The consequence to keep in mind: Reticulum's transport-layer encryption must
never be counted as a second line of defence between the pair. It is not one.

## A process that is about to exit has to wait for its packets

``RNS.Packet.send()`` returning is not the packet leaving. On Linux and Android
the local interface queues frames in a transmit buffer a separate loop writes
out; everywhere, Reticulum detaches that interface at exit with
``shutdown(SHUT_RDWR)``, which discards whatever the other end has not yet read.
A process that sends and exits at once loses its last packets.

Measured rather than assumed, with ``mayak send`` against a listener on the same
machine, three tries each: exiting at once delivered **0 of 3**; staying 0.25 s
delivered 3 of 3, and so did 0.5 s and 1 s. It had been happening all along —
it is the likeliest cause of an earlier end-to-end run that lost a message in
three — and became certain once a key update added four frames to the first send.

:func:`drain` waits for the transmit buffers to empty and then for
:data:`SETTLE_BEFORE_EXIT_SECONDS`. A long-running process never needs it.

## Never announced

Destinations are registered and never announced. A registered destination
answers a path request on its own, so a sender that knows the address can still
find a route — it asks for the path at send time rather than being told in
advance by a broadcast. Announcing would publish the address's public key to
everyone in range, which is exactly the linkability the derivation exists to
remove.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Iterable

import RNS

from mayak.epoch import CONTINUATION_OVERHEAD
from mayak.fragment import WHOLE_HEADER, FragmentError, Reassembler, split
from mayak.sealing import TAG_LENGTH
from mayak.transport import Receiver, TransportError

#: Namespace for every destination this protocol creates. Two aspects, both
#: constant, so nothing about a destination's name distinguishes one user from
#: another — the address itself is the only varying part.
APP_NAME = "mayak"
ASPECT = "link"

#: How long to wait for a path before giving up on one send.
#:
#: A path request goes out and answers arrive asynchronously. Waiting forever
#: would hang a caller on an unreachable contact; not waiting at all would fail
#: every first message to a contact whose route is not yet known. Ten seconds is
#: chosen to cover a slow mesh hop without holding a user interface.
PATH_TIMEOUT_SECONDS = 10.0

#: How often to re-check while waiting for a path to appear.
_PATH_POLL_SECONDS = 0.1

#: How long a process about to exit waits once its buffers are empty.
#:
#: Four times the measured threshold on a laptop — 0.25 s delivered every message,
#: no wait delivered none — because a phone is slower and a second is cheap next
#: to a message that silently never left.
SETTLE_BEFORE_EXIT_SECONDS = 1.0

#: How long :func:`drain` waits for buffers to empty before giving up.
DRAIN_TIMEOUT_SECONDS = 10.0

#: The largest frame that fits in one encrypted Reticulum packet.
#:
#: Taken from the stack rather than written down, because it is the stack's
#: number and it has moved before: MTU is 500, of which a header and the
#: destination's own encryption take the rest. A frame larger than this is not
#: slow, it is undeliverable — the first version of this module used a round 512
#: and every send failed with "packet size 627 exceeds MTU of 500".
SINGLE_PACKET_FRAME = RNS.Packet.ENCRYPTED_MDU

#: The plaintext body length that keeps an ordinary message inside one packet.
#:
#: Every message pads to this, so content length never reaches the wire. The
#: first message of an epoch carries the encapsulation on top and is larger —
#: see :mod:`mayak.envelope` for why that trade is not close.
SINGLE_PACKET_BODY = SINGLE_PACKET_FRAME - WHOLE_HEADER - 1 - CONTINUATION_OVERHEAD


def body_for_single_packet_opening(kem) -> int:
    """Body length that keeps even an epoch opening inside one packet.

    The opening carries the encapsulation, so it is the largest message a link
    ever sends. Sizing the body to fit it means every message fits — at the cost
    of carrying less in the ordinary ones.

    Returns a negative number when the mechanism cannot fit at all: an ML-KEM-768
    ciphertext is 1088 bytes against a 383-byte packet. That is no longer fatal —
    an oversized message is split across packets — but it is worth knowing when a
    link will fragment every epoch opening, because each piece is a packet that
    can be lost.
    """
    return SINGLE_PACKET_FRAME - WHOLE_HEADER - 1 - kem.ciphertext_length - TAG_LENGTH


def body_length_for(kem) -> int:
    """The body length two devices running this mechanism will both pick.

    Body length is the padded size of every message, so the two ends must agree
    on it or their frames are different sizes on the wire — which is a thing an
    observer can sort them by, and is exactly what fixed-size framing exists to
    prevent. Deriving it from the mechanism rather than configuring it means
    agreement is automatic: same mechanism, same number, nothing to exchange.

    Where the epoch opening fits in one packet, that is the size used, so a link
    never fragments. Where it cannot — an ML-KEM-768 ciphertext is 1088 bytes
    against a 383-byte packet — the ordinary messages are sized to one packet
    and the opening is split. Sizing everything down to fit the opening would
    make every message pay for the one that carries the encapsulation.
    """
    fits = body_for_single_packet_opening(kem)
    return fits if fits > 0 else SINGLE_PACKET_BODY


class RnsTransport:
    """A :class:`mayak.transport.Transport` backed by a running Reticulum stack.

    Does not start or own Reticulum — it is handed an already-running instance,
    because on Android the stack belongs to a long-lived service and starting a
    second one would fight the first for interfaces.
    """

    def __init__(self, path_timeout: float = PATH_TIMEOUT_SECONDS) -> None:
        self._path_timeout = path_timeout
        self._lock = threading.Lock()
        self._inbound: dict[bytes, RNS.Destination] = {}
        self._receiver: Receiver | None = None
        self._reassembler = Reassembler()

        #: Packets that were not fragments at all. A destination anyone can
        #: reach will receive rubbish; counting it beats raising on it.
        self.malformed = 0

    # ---------------------------------------------------------------- sending

    def send(self, address_seed: bytes, wire: bytes) -> None:
        try:
            packets = split(wire, SINGLE_PACKET_FRAME)
        except FragmentError as too_big:
            raise TransportError(str(too_big)) from too_big

        identity = _identity_from(address_seed)
        destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, APP_NAME, ASPECT)

        if not self._await_path(destination.hash):
            raise TransportError("no path to the destination within the timeout")

        # Sent in order and without waiting between pieces. Reassembly does not
        # depend on order, and pacing belongs to the emission policy — a
        # transport that decided when to transmit would be making a decision
        # about exposure it has no information for.
        for packet in packets:
            try:
                outcome = RNS.Packet(destination, packet).send()
            except Exception as failure:  # noqa: BLE001 - the stack raises several types for one outcome
                raise TransportError(f"the packet could not be sent: {failure}") from failure

            # ``send`` reports failure by returning False rather than raising,
            # and returns None on success when no receipt was asked for — so
            # this is an identity check, not a truthiness one. Ignoring it meant
            # a packet no interface would carry was reported as sent: the caller
            # was told the message had gone, nothing was held, and it simply
            # never arrived. Found by an end-to-end run that lost one message in
            # three and said nothing about it.
            if outcome is False:
                raise TransportError("no interface would carry the packet")

    def _await_path(self, destination_hash: bytes) -> bool:
        """Ask for a path if we have none, and wait briefly for an answer.

        A destination that was never announced still answers a path request, so
        this is how a sender finds a contact that has never broadcast anything.
        """
        if RNS.Transport.has_path(destination_hash):
            return True

        RNS.Transport.request_path(destination_hash)
        deadline = time.monotonic() + self._path_timeout
        while time.monotonic() < deadline:
            if RNS.Transport.has_path(destination_hash):
                return True
            time.sleep(_PATH_POLL_SECONDS)
        return False

    # -------------------------------------------------------------- receiving

    def listen(self, address_seeds: Iterable[bytes], receiver: Receiver) -> None:
        wanted = list(address_seeds)
        with self._lock:
            self._receiver = receiver
            keep = {seed for seed in wanted}

            # Drop the destinations that have fallen out of the live window
            # before registering the new ones, so the set never grows past
            # what the caller asked for.
            for seed in list(self._inbound):
                if seed not in keep:
                    self._retire(seed)

            for seed in wanted:
                if seed not in self._inbound:
                    self._inbound[seed] = self._register(seed)

    def stop(self) -> None:
        with self._lock:
            for seed in list(self._inbound):
                self._retire(seed)
            self._receiver = None

    def _register(self, address_seed: bytes) -> RNS.Destination:
        destination = RNS.Destination(
            _identity_from(address_seed),
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            APP_NAME,
            ASPECT,
        )
        # Never announced. A registered destination answers a path request on
        # its own, which is how it stays reachable without broadcasting its key.
        destination.set_packet_callback(self._on_packet)
        return destination

    def _retire(self, address_seed: bytes) -> None:
        destination = self._inbound.pop(address_seed, None)
        if destination is None:
            return
        # Reticulum has no deregister; clearing the callback is what stops
        # anything arriving at a retired address from reaching the session.
        destination.set_packet_callback(None)

    def _on_packet(self, data: bytes, packet: object) -> None:
        """Hand the bytes up. Which address they arrived at is not reported.

        The session tries each of its live addresses to open the message, so
        telling it where the packet landed would add nothing — and taking the
        transport's word for it is exactly what binding the address into the
        seal is meant to avoid.
        """
        receiver = self._receiver
        if receiver is None:
            return
        try:
            message = self._reassembler.accept(bytes(data))
        except FragmentError:
            self.malformed += 1
            return
        if message is not None:
            receiver(message)


def drain(timeout: float = DRAIN_TIMEOUT_SECONDS, settle: float = SETTLE_BEFORE_EXIT_SECONDS) -> bool:
    """Wait until Reticulum has written everything queued, then a little longer.

    Call before a process exits, and only then — see the module docstring.
    Returns False if the buffers did not empty within ``timeout``, which a caller
    should report rather than exit quietly on: those packets are about to be lost.
    """
    deadline = time.monotonic() + timeout
    while any(_pending(interface) for interface in list(RNS.Transport.interfaces)):
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.05)
    time.sleep(settle)
    return True


def _pending(interface: object) -> int:
    """Bytes an interface has accepted and not yet handed to the kernel."""
    pending = 0
    if getattr(interface, "epoll_backend", False):
        buffer = getattr(interface, "transmit_buffer", None)
        if buffer is not None:
            pending += len(buffer)
    if getattr(interface, "writing", False):
        pending += 1
    return pending


def _identity_from(address_seed: bytes) -> RNS.Identity:
    """Build the Reticulum identity that owns a derived address.

    ``Identity.from_bytes`` carries an upstream warning against feeding it
    arbitrary data, aimed at callers who would use it to turn a password into a
    key. This seed is HKDF output over an X25519 agreement, which is the case
    the warning is not about: full-entropy key material, derived rather than
    invented.
    """
    identity = RNS.Identity.from_bytes(address_seed)
    if identity is None:
        raise TransportError("the address seed is not usable key material")
    return identity
