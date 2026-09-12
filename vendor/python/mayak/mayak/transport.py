"""Moving a sealed frame from one device to another, without knowing what it is.

## Why this is an interface

Everything below this line is someone else's network. Reticulum today; a plain
socket in a test; something else on a device that cannot run Reticulum at all.
None of that should be able to reach the layers that hold keys, and none of
those layers should have to know which one is underneath.

So a transport does exactly two things — carry bytes to an address, and hand
back bytes that arrived at one — and it is given no way to interpret either.
It never sees a key, never learns which messages are cover and which are real,
and cannot tell one contact from another except by the addresses it was handed.
A compromised transport learns the same things a wiretap learns, and nothing
more.

## Addresses are key material

An address here is the 64 bytes :mod:`mayak.addressing` derives — the private
key of the identity that owns the destination. That is deliberate: only someone
who can derive it can listen on it, so there is no registry to consult and
nothing to publish.

It also means a transport holds private key material for the destinations it
listens on, which is why the mapping from seed to destination lives inside each
implementation rather than being handed around.
"""

from __future__ import annotations

import threading
from collections import defaultdict
from collections.abc import Callable, Iterable
from typing import Protocol, runtime_checkable

#: Called with the bytes that arrived. The receiver works out which of its live
#: addresses it was sent to by trying to open it, so the transport does not say.
Receiver = Callable[[bytes], None]


class TransportError(RuntimeError):
    """The message could not be handed to the network."""


@runtime_checkable
class Transport(Protocol):
    """Carry bytes to an address; deliver bytes that arrive at ours."""

    def send(self, address_seed: bytes, wire: bytes) -> None:
        """Deliver ``wire`` to the destination owned by ``address_seed``.

        Raises :class:`TransportError` if it could not be handed over at all.
        Whether it *arrives* is not this method's promise — no transport worth
        using pretends to know that synchronously.
        """

    def listen(self, address_seeds: Iterable[bytes], receiver: Receiver) -> None:
        """Start accepting messages at each address, replacing any previous set.

        Replacing rather than adding is what lets an epoch roll over cleanly: a
        caller hands in the three live addresses each time and the one that fell
        out of the window stops being listened on, without having to remember
        which one it was.
        """

    def stop(self) -> None:
        """Stop listening. Safe to call twice; safe to call having never listened."""


class LoopbackTransport:
    """Both ends in one process, for tests and for two identities on one device.

    Carries no bytes anywhere. Every instance sharing a :class:`Switchboard`
    can reach every other, which makes a full conversation testable without a
    network, a clock, or a radio.

    It is not a simulation of a network: nothing is delayed, dropped or
    reordered. Tests that need those should say so explicitly rather than
    inheriting them by accident from a fixture.
    """

    def __init__(self, switchboard: Switchboard) -> None:
        self._switchboard = switchboard
        self._addresses: tuple[bytes, ...] = ()
        self._receiver: Receiver | None = None

    def send(self, address_seed: bytes, wire: bytes) -> None:
        self._switchboard.deliver(address_seed, wire)

    def listen(self, address_seeds: Iterable[bytes], receiver: Receiver) -> None:
        self.stop()
        self._addresses = tuple(address_seeds)
        self._receiver = receiver
        for address in self._addresses:
            self._switchboard.register(address, receiver)

    def stop(self) -> None:
        for address in self._addresses:
            self._switchboard.unregister(address, self._receiver)
        self._addresses = ()
        self._receiver = None


class Switchboard:
    """Who is listening where, for :class:`LoopbackTransport`.

    Messages to an address nobody listens on are dropped in silence, which is
    what a real network does with a destination that has no path. Counting them
    is left to the test that cares.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._listeners: dict[bytes, list[Receiver]] = defaultdict(list)
        self.undeliverable = 0

    def register(self, address_seed: bytes, receiver: Receiver) -> None:
        with self._lock:
            self._listeners[address_seed].append(receiver)

    def unregister(self, address_seed: bytes, receiver: Receiver | None) -> None:
        if receiver is None:
            return
        with self._lock:
            listeners = self._listeners.get(address_seed)
            if listeners and receiver in listeners:
                listeners.remove(receiver)
            if listeners is not None and not listeners:
                del self._listeners[address_seed]

    def deliver(self, address_seed: bytes, wire: bytes) -> None:
        with self._lock:
            listeners = list(self._listeners.get(address_seed, ()))
        if not listeners:
            with self._lock:
                self.undeliverable += 1
            return
        for receiver in listeners:
            receiver(wire)
