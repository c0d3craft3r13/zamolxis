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

## A conversation has to survive a restart

Each conversation replaces the key it receives on — see :mod:`mayak.keyring` —
and learns epoch roots as openings arrive — see :mod:`mayak.epoch`. The node
keeps both in the device file and saves whenever a conversation says something
changed that a crash must not lose: a new key before it is announced, a new
epoch as it is learned, a replay window before its message is shown.

Before this, a restarted device could not read a contact who was part-way
through an epoch until that contact opened another — up to a week. A restart
test found it.

Saves can now come from two threads — a message arriving on the transport's
thread can confirm a key while the caller is adding a contact — so saving is
serialised. Two unsynchronised saves would race on the store's temporary file.

## Wiping is the operation the rest of the design exists for

:meth:`wipe` destroys the store and drops every key held in memory. What is left
on the device is a file of ciphertext nobody has a key for and a process with no
secrets in it. That is the strongest claim available on hardware that does not
erase flash on request, and it is stated rather than dressed up.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from mayak import device as device_format
from mayak.contacts import Contact, ContactBook, ContactError, Trust
from mayak.envelope import Inbound
from mayak.epoch import EpochRecord
from mayak.kem import Kem
from mayak.keyring import ReceiveKey, ReceiveKeyring
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
class KeyStatus:
    """What an interface may show about a conversation's keys, without the keys."""

    #: Receiving keys this conversation still holds.
    held: int

    #: Whether the contact has used the newest one.
    newest_confirmed: bool

    #: Whether the conversation has moved off the device's invitation key.
    invitation_key_retired: bool

    #: How long ago the newest key was created.
    newest_age_seconds: float


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
        stored = saved.conversations if saved is not None else {}
        self._keyrings: dict[bytes, ReceiveKeyring] = {
            identity: self._keyring_from(conversation.keys)
            for identity, conversation in stored.items()
            if identity in self._book
        }
        #: Receiving epochs from the last run, for conversations not yet reopened.
        self._receiving: dict[bytes, tuple[list[EpochRecord], dict[bytes, float]]] = {
            identity: (conversation.epochs, conversation.retired)
            for identity, conversation in stored.items()
            if identity in self._book
        }
        self._save_lock = threading.RLock()
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
        """Remove a contact, close the conversation, destroy its keys, and save."""
        self._require_usable()
        with self._save_lock:
            session = self._sessions.pop(identity_key, None)
            if session is not None:
                session.stop()
            self._keyrings.pop(identity_key, None)
            self._receiving.pop(identity_key, None)
            self._book.forget(identity_key)
            self.save()

    def key_status(self, identity_key: bytes) -> KeyStatus:
        """How a conversation's keys stand, for showing to a person."""
        self._require_usable()
        self._require_contact(identity_key)
        ring = self._keyring_for(identity_key)
        newest = ring.newest
        return KeyStatus(
            held=len(ring.keys),
            newest_confirmed=newest.confirmed,
            invitation_key_retired=all(key.introduction is False or key.superseded is not None for key in ring.keys),
            newest_age_seconds=self._clock() - newest.created,
        )

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
        with self._save_lock:
            device_format.save(
                self._store,
                device_format.Device(
                    us=self._us if self._store_keys else None,
                    book=self._book,
                    conversations={
                        identity: device_format.StoredConversation(
                            keys=_stored(ring),
                            epochs=self._receiving_of(identity)[0],
                            retired=self._receiving_of(identity)[1],
                        )
                        for identity, ring in list(self._keyrings.items())
                    },
                ),
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
        with self._save_lock:
            self._wiped = True
            self._store.wipe()
        self._sessions.clear()
        self._keyrings.clear()
        self._receiving.clear()
        self._book = ContactBook()
        self._us = Us(private_key=b"", public_key=b"", kem_private_key=b"", kem_public_key=b"")
        self._wiped = True

    @property
    def wiped(self) -> bool:
        return self._wiped

    # ------------------------------------------------------------- internal

    def _session_for(self, contact: Contact) -> Session:
        # Under the save lock: a save from the transport's thread walks the
        # conversations this adds to.
        with self._save_lock:
            return self._open_session(contact)

    def _open_session(self, contact: Contact) -> Session:
        session = self._sessions.get(contact.identity_key)
        if session is not None:
            return session

        identity = contact.identity_key
        session = Session(
            self._kem,
            self._us,
            Peer(public_key=identity, kem_public_key=contact.kem_key),
            self._transport_factory(),
            self._body_length,
            lambda message, sender=contact: self._deliver(sender, message),
            clock=self._clock,
            allow_classical_only=self._allow_classical_only,
            keyring=self._keyring_for(identity),
            receiving_state=self._receiving.pop(identity, None),
            on_peer_key=lambda key, who=identity: self._peer_rotated(who, key),
            on_state_changed=self._save_from_a_conversation,
        )
        self._sessions[identity] = session
        return session

    def _receiving_of(self, identity: bytes) -> tuple[list[EpochRecord], dict[bytes, float]]:
        session = self._sessions.get(identity)
        if session is not None:
            return session.receiving_state
        return self._receiving.get(identity, ([], {}))

    def _keyring_for(self, identity: bytes) -> ReceiveKeyring:
        ring = self._keyrings.get(identity)
        if ring is None:
            ring = ReceiveKeyring.introduced(self._us.kem_private_key, self._us.kem_public_key, clock=self._clock)
            self._keyrings[identity] = ring
        return ring

    def _keyring_from(self, stored: list[device_format.StoredKey]) -> ReceiveKeyring:
        keys = [
            ReceiveKey(
                # The invitation key is never in a keyring on disk; it is the
                # device's own, filled in here from wherever that is kept.
                private=self._us.kem_private_key if entry.introduction else entry.private,
                public=self._us.kem_public_key if entry.introduction else entry.public,
                created=entry.created,
                confirmed=entry.confirmed,
                superseded=entry.superseded,
                introduction=entry.introduction,
            )
            for entry in stored
        ]
        return ReceiveKeyring(keys, clock=self._clock)

    def _peer_rotated(self, identity: bytes, key: bytes) -> None:
        # Authenticated by the conversation it arrived in, so trust is untouched.
        if identity not in self._book:
            return
        self._book.rotate_key(identity, key)
        self._save_from_a_conversation()

    def _save_from_a_conversation(self) -> None:
        # Called from whichever thread delivered the frame. A wiped node has
        # nothing to save and must not raise into the transport's thread.
        with self._save_lock:
            if self._wiped:
                return
            self.save()

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


def _stored(ring: ReceiveKeyring) -> list[device_format.StoredKey]:
    return [
        device_format.StoredKey(
            private=b"" if key.introduction else key.private,
            public=b"" if key.introduction else key.public,
            created=key.created,
            confirmed=key.confirmed,
            superseded=key.superseded,
            introduction=key.introduction,
        )
        for key in ring.keys
    ]


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
