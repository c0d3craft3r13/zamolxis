"""What to do with a message when the other person is not there.

## The problem, and the shape of the answer

A mesh has no uptime guarantee. A contact is behind a hill, or asleep, or has
the radio off, and a message sent to them simply does not arrive. Somebody has
to decide whether to try again, when, and for how long — and "somebody" must not
be the user, who has already pressed send and gone back to watching the road.

So a message that could not be handed over is held and retried. Nothing clever:
attempts spaced further apart each time, a ceiling on how long anything is kept,
and then it is given up on and said so.

## Why the retries are spaced, and why they wander

Retrying immediately and repeatedly is what an unreachable contact turns into: a
device transmitting hard, on a schedule, at a destination nobody is answering.
That is a beacon, and on a radio it is a beacon pointing at the operator.

So the wait grows — each attempt roughly twice the last — and every wait is
jittered. Two devices that lost the same contact at the same moment would
otherwise retry in lockstep forever, and a listener would learn that they are a
pair without reading a byte. The jitter is not politeness to the network; it is
the difference between two independent-looking devices and two obviously
related ones.

## What is held, and what is not

The **sealed bytes**, not the message. By the time anything reaches here it has
been sealed, and the outbox has no key with which to look inside. A seized
device therefore yields an outbox of ciphertext addressed to rotating addresses,
which is what the rest of the design already assumes an observer has.

The address is held with it, and that address belongs to an epoch. A message
held long enough for its epoch to pass is no longer deliverable — the recipient
has stopped listening there — so :data:`MAX_AGE_SECONDS` is deliberately shorter
than an address epoch rather than a round number picked for comfort.
"""

from __future__ import annotations

import random
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field

#: How long to wait before the first retry.
FIRST_RETRY_SECONDS = 30.0

#: The longest any two attempts are spaced, however many have failed.
MAX_RETRY_SECONDS = 30 * 60.0

#: How much each wait is scattered, as a fraction either way.
#:
#: Without it two devices that lost the same contact at the same moment retry in
#: step forever, which tells a listener they are a pair. A quarter is enough to
#: destroy the pattern within a couple of attempts.
JITTER = 0.25

#: How long a message is kept before it is given up on.
#:
#: Shorter than an address epoch on purpose. A message held past its epoch is
#: addressed where the recipient has stopped listening, so keeping it longer
#: would mean retransmitting into a destination that cannot answer — effort and
#: exposure spent on something that already cannot arrive.
MAX_AGE_SECONDS = 20 * 60 * 60.0

#: Most messages held at once. Beyond this the oldest is dropped: an outbox that
#: grew without limit would be a way to fill a device's memory by being absent.
MAX_HELD = 256


@dataclass
class Held:
    """One message waiting for its recipient to become reachable."""

    address_seed: bytes
    wire: bytes
    queued: float
    attempts: int = 0
    next_attempt: float = 0.0
    #: Set when the message is abandoned, so a caller can say why.
    reason: str = ""

    def expired(self, now: float) -> bool:
        return (now - self.queued) >= MAX_AGE_SECONDS


@dataclass
class Outcome:
    """What one pass over the outbox did."""

    sent: list[Held] = field(default_factory=list)
    still_waiting: int = 0
    abandoned: list[Held] = field(default_factory=list)


class Outbox:
    """Holds what could not be handed over, and tries again later.

    Deliberately not a thread of its own. It is driven by whoever already
    decides when this device may transmit — the same thing that owns radio
    silence and cover traffic — because a queue that retried on its own timer
    would be transmitting behind that decision's back.
    """

    def __init__(self, clock: Callable[[], float] = time.time, jitter: Callable[[], float] | None = None) -> None:
        self._clock = clock
        #: Injected so a test can make the schedule exact. Real use wants the
        #: scatter; a test wants to know precisely when the next attempt is due.
        self._jitter = jitter or (lambda: random.uniform(-JITTER, JITTER))
        self._held: list[Held] = []

        #: Given up on, for the life of this outbox. Counted rather than
        #: reported one by one, because a caller that wanted each failure would
        #: be asking for a notification per unreachable contact.
        self.abandoned = 0

    def __len__(self) -> int:
        return len(self._held)

    def __iter__(self) -> Iterator[Held]:
        return iter(list(self._held))

    def hold(self, address_seed: bytes, wire: bytes) -> Held:
        """Keep a message that could not be sent, and schedule a retry."""
        now = self._clock()
        self._make_room()
        message = Held(
            address_seed=address_seed,
            wire=wire,
            queued=now,
            next_attempt=now + self._wait_for(0),
        )
        self._held.append(message)
        return message

    def due(self, now: float | None = None) -> list[Held]:
        """The messages whose next attempt has come round."""
        moment = self._clock() if now is None else now
        return [message for message in self._held if message.next_attempt <= moment]

    def attempt(self, send: Callable[[bytes, bytes], None]) -> Outcome:
        """Try everything that is due, once.

        ``send`` is called with the address and the bytes, and is expected to
        raise if the message could not be handed over. Anything it raises is
        treated as "not now" rather than "never" — the outbox cannot tell the
        difference between an unreachable contact and a radio that is switched
        off, and both are worth trying again.
        """
        now = self._clock()
        outcome = Outcome()

        for message in list(self._held):
            if message.expired(now):
                self._give_up(message, "held longer than its address epoch", outcome)
                continue
            if message.next_attempt > now:
                continue

            try:
                send(message.address_seed, message.wire)
            except Exception:  # noqa: BLE001 - any failure means "try later"
                message.attempts += 1
                message.next_attempt = now + self._wait_for(message.attempts)
                continue

            self._held.remove(message)
            outcome.sent.append(message)

        outcome.still_waiting = len(self._held)
        return outcome

    def forget(self, message: Held) -> None:
        """Drop a message without sending it."""
        if message in self._held:
            self._held.remove(message)

    def _give_up(self, message: Held, reason: str, outcome: Outcome) -> None:
        message.reason = reason
        self._held.remove(message)
        self.abandoned += 1
        outcome.abandoned.append(message)

    def _wait_for(self, attempts: int) -> float:
        """How long until attempt number ``attempts + 1``.

        Doubling, capped, then scattered. The cap matters as much as the
        doubling: without it the wait passes the age limit and the last attempts
        never happen, so a message would be abandoned having been tried fewer
        times than it looks.
        """
        base = min(FIRST_RETRY_SECONDS * (2**attempts), MAX_RETRY_SECONDS)
        return base * (1.0 + self._jitter())

    def _make_room(self) -> None:
        while len(self._held) >= MAX_HELD:
            oldest = min(self._held, key=lambda message: message.queued)
            oldest.reason = "the outbox was full"
            self._held.remove(oldest)
            self.abandoned += 1
