"""Everything on one device, in one object.

## What it is for

Below this, each module does one thing and knows nothing about the rest. That is
right for building and wrong for using: an application should not have to wire a
sealer to an opener to an outbox to a transport for every contact, and get the
order right each time.

So a node holds the contact book, a session per contact, and the store, and
exposes the operations a person actually performs: add someone, write to them,
read what arrived, save, wipe.

## What it refuses to decide

**When to transmit.** :meth:`flush` and :meth:`send_cover` are called by
whatever owns the radio. A node with a timer inside it would be transmitting
behind the back of radio silence, which is the one decision that must never be
made by convenience.

**Whether to talk to an unverified contact.** The node reports the trust and
carries on. A courier and a journalist want opposite defaults, and a library
that picked one would be wrong for the other half of its users.

## Where the keys live

A node can be handed its keys — by a platform keystore, by a test — or it can
keep them in its own store and be opened with nothing but the passphrase
(:meth:`Node.open`). The store holds the keys and the contact book in one file,
because a wipe has to destroy both and two files means two wipes; see
:mod:`mayak.device`.

A node that was handed its keys does not write them back. That is not a default
worth guessing at: copying a keystore-held private key into a file as a side
effect of adding a contact is exactly the kind of quiet leak nobody goes looking
for.

## Wiping is the operation the rest of the design exists for

:meth:`wipe` destroys the store and drops every key held in memory. What is left
on the device is a file of ciphertext nobody has a key for and a process with no
secrets in it. That is the strongest claim available on hardware that does not
erase flash on request, and it is stated rather than dressed up.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass

from mayak import device as device_format
from mayak.contacts import Contact, ContactBook, ContactError, Trust
from mayak.envelope import Inbound
from mayak.kem import Kem
from mayak.session import Peer, Session, Us
from mayak.store import Store
from mayak.transport import Transport

#: Called with the contact a message came from and the message itself.
Delivery = Callable[[Contact, Inbound], None]

#: Makes a transport for one contact. A node cannot share one transport across
#: contacts, because a transport owns the set of addresses it listens on and
#: each contact's set is different.
TransportFactory = Callable[[], Transport]


class NodeError(ValueError):
    """The node was asked for something it cannot do."""


@dataclass(frozen=True)
class Received:
    """A message, and who it turned out to be from."""

    contact: Contact
    message: Inbound


class Node:
    """One device: its contacts, its conversations, and what it keeps."""

    def __init__(
        self,
        kem: Kem,
        us: Us,
        transport_factory: TransportFactory,
        body_length: int,
        store: Store,
        on_message: Delivery,
        *,
        clock: Callable[[], float] = time.time,
        allow_classical_only: bool = False,
        store_keys: bool = False,
    ) -> None:
        self._kem = kem
        self._us = us
        self._transport_factory = transport_factory
        self._body_length = body_length
        self._store = store
        self._on_message = on_message
        self._clock = clock
        self._allow_classical_only = allow_classical_only
        self._store_keys = store_keys

        saved = device_format.load(store)
        if saved is not None and saved.us is not None and saved.us != us:
            # Pointing a node at a store that belongs to somebody else is a
            # mistake with no safe reading: carrying on would either overwrite
            # their identity or answer at addresses derived from it.
            raise NodeError("this store holds another identity's keys")
        self._book = saved.book if saved is not None else ContactBook()
        self._sessions: dict[bytes, Session] = {}
        self._wiped = False

    @classmethod
    def open(
        cls,
        kem: Kem,
        transport_factory: TransportFactory,
        body_length: int,
        store: Store,
        on_message: Delivery,
        *,
        clock: Callable[[], float] = time.time,
        allow_classical_only: bool = False,
    ) -> Node:
        """Open the device this store holds, creating one on first use.

        The keys come out of the store and go back into it, so a caller needs
        nothing but the passphrase that opens the store. Use the constructor
        instead when the keys belong somewhere else — a platform keystore has no
        business being copied into a file.

        Opening an existing device does not rewrite it. Saving on every open
        would cost a key derivation nobody asked for and would stamp the file
        with the time it was last looked at, which on a device that may be
        seized is a fact worth not recording.
        """
        saved = device_format.load(store)
        has_keys = saved is not None and saved.us is not None
        node = cls(
            kem,
            saved.us if has_keys else device_format.new_keys(kem),
            transport_factory,
            body_length,
            store,
            on_message,
            clock=clock,
            allow_classical_only=allow_classical_only,
            store_keys=True,
        )
        if not has_keys:
            node.save()
        return node

    @property
    def public_keys(self) -> tuple[bytes, bytes]:
        """What a contact needs to reach this device: identity, then encapsulation."""
        self._require_usable()
        return self._us.public_key, self._us.kem_public_key

    # ------------------------------------------------------------- contacts

    @property
    def contacts(self) -> ContactBook:
        """The contact book. Changes to it are saved by :meth:`save`."""
        self._require_usable()
        return self._book

    def add_contact(self, name: str, identity_key: bytes, kem_key: bytes) -> Contact:
        """Record someone new and open a conversation with them."""
        self._require_usable()
        contact = self._book.add(Contact(name=name, identity_key=identity_key, kem_key=kem_key))
        self.save()
        return contact

    def forget_contact(self, identity_key: bytes) -> None:
        """Remove a contact, close the conversation, and save."""
        self._require_usable()
        session = self._sessions.pop(identity_key, None)
        if session is not None:
            session.stop()
        self._book.forget(identity_key)
        self.save()

    # ------------------------------------------------------------ messaging

    def listen(self) -> None:
        """Start accepting messages from every known contact."""
        self._require_usable()
        for contact in self._book:
            self._session_for(contact).listen()

    def stop(self) -> None:
        for session in self._sessions.values():
            session.stop()

    def send(self, identity_key: bytes, content: bytes) -> bool:
        """Write to a contact. Held and retried if they cannot be reached.

        Returns whether the network took it. False is not a failure — the
        message is held and goes out on the next :meth:`flush` — but it is the
        difference between "sent" and "waiting", and an interface that cannot
        tell them apart will confidently say the wrong one.
        """
        self._require_usable()
        return self._hand_over(self._require_contact(identity_key), lambda session: session.send(content))

    def send_cover(self, identity_key: bytes) -> bool:
        """Send a message that says nothing, to a contact who already hears from us."""
        self._require_usable()
        return self._hand_over(self._require_contact(identity_key), lambda session: session.send_cover())

    def _hand_over(self, contact: Contact, attempt: Callable[[Session], object]) -> bool:
        session = self._session_for(contact)
        held = session.held
        attempt(session)
        return session.held == held

    def flush(self) -> int:
        """Retry everything held, for every contact. Returns how many got through.

        Driven, never timed — see the module docstring.
        """
        self._require_usable()
        return sum(session.flush() for session in self._sessions.values())

    @property
    def waiting(self) -> int:
        """Messages held across all conversations."""
        return sum(session.waiting for session in self._sessions.values())

    def needs_rollover(self) -> bool:
        """Whether any conversation's addresses have moved on."""
        return any(session.needs_rollover() for session in self._sessions.values())

    def roll_over(self) -> None:
        """Re-register every conversation on its current addresses."""
        self._require_usable()
        for session in self._sessions.values():
            session.listen()

    # ---------------------------------------------------------- persistence

    def save(self) -> None:
        """Write the contact book. Messages in flight are not persisted.

        Holding the outbox across a restart would mean writing sealed messages
        to disk beside the keys that addressed them, and a device that is seized
        mid-conversation would give up what it was about to say. A message that
        did not go out before the device stopped is a message the sender can
        send again.
        """
        self._require_usable()
        device_format.save(
            self._store,
            device_format.Device(us=self._us if self._store_keys else None, book=self._book),
        )

    def wipe(self) -> None:
        """Destroy the store and drop every key held in memory.

        This is why the keys share a file with the contact book. An identity key
        that survives a wipe re-derives every address the device ever used the
        moment one contact's key turns up, so keeping it in a second file would
        mean the wipe destroyed the list and left the thing that indexes it.

        The node is unusable afterwards, deliberately: a wiped node that kept
        answering would be a node that quietly rebuilt what was just destroyed.
        """
        self.stop()
        self._store.wipe()
        self._sessions.clear()
        self._book = ContactBook()
        self._us = Us(private_key=b"", public_key=b"", kem_private_key=b"", kem_public_key=b"")
        self._wiped = True

    @property
    def wiped(self) -> bool:
        return self._wiped

    # ------------------------------------------------------------- internal

    def _session_for(self, contact: Contact) -> Session:
        session = self._sessions.get(contact.identity_key)
        if session is not None:
            return session

        session = Session(
            self._kem,
            self._us,
            Peer(public_key=contact.identity_key, kem_public_key=contact.kem_key),
            self._transport_factory(),
            self._body_length,
            lambda message, sender=contact: self._deliver(sender, message),
            clock=self._clock,
            allow_classical_only=self._allow_classical_only,
        )
        self._sessions[contact.identity_key] = session
        return session

    def _deliver(self, contact: Contact, message: Inbound) -> None:
        # The contact is looked up again rather than captured, so a message that
        # arrives after a key change is reported with the trust it has now.
        current = self._book.get(contact.identity_key) or contact
        self._on_message(current, message)

    def _require_contact(self, identity_key: bytes) -> Contact:
        contact = self._book.get(identity_key)
        if contact is None:
            raise NodeError("no such contact")
        return contact

    def _require_usable(self) -> None:
        if self._wiped:
            raise NodeError("this node has been wiped")


def trust_of(node: Node, identity_key: bytes) -> Trust:
    """What is known about how a contact's keys arrived.

    A free function rather than a method because it is a question about a
    contact, not about the node, and because nothing here acts on the answer —
    see the module docstring on why the node does not decide.
    """
    contact = node.contacts.get(identity_key)
    if contact is None:
        raise ContactError("no such contact")
    return contact.trust
