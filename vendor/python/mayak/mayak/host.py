"""Mayak inside an application: one object to start, talk to, and stop.

## Why this exists beside the command line

:mod:`mayak.cli` is a person at a terminal: it opens the device, does one thing,
and exits. An app on a phone is the opposite shape — a long-running service that
receives while nobody is looking, and a screen in another process that asks it
for things. Everything the service needs from Mayak is here, and nothing in it
knows about Android.

What crosses into the app is plain: strings, booleans, and dicts of strings. An
app written in another language — Kotlin, through Chaquopy — should not have to
understand a :class:`~mayak.contacts.Contact` to show one.

## What it decides that the node refuses to

:class:`~mayak.node.Node` leaves *when to transmit* to whatever owns the radio.
A host is that owner. It hands held messages back to the network when the outbox
says they are due, and not at all while the app has asked for silence — see
:meth:`Host.set_transmitting`.
Silence holds nothing back in secret: writing during it is refused, so a screen
can say so instead of showing a message as sent.

## The device file

One file, sealed under a passphrase the app supplies. On a phone that passphrase
is random and kept wrapped by the platform keystore, because the service has to
open the file after a reboot with nobody there to type anything.

Whether the file is also bound to hardware is a setting a person can change —
see :mod:`mayak.bound_store` for what binding costs and what it does not reach.
Changing it stops the node, rewrites the file, and starts it again. Messages
waiting for an unreachable contact are held in memory only, so a change of mode
drops them, the same as a restart does; :meth:`Host.set_bound` returns how many.

## Messages that arrive

They are handed to the app's callback on the thread that received them, as a
dict. A conversation saves its receiving state *before* the message is handed
over — a message shown once must not be replayable — so a callback that fails
loses that message for good. That is counted in :meth:`Host.status` rather than
raised into Reticulum's thread, where it would stop receiving altogether.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from pathlib import Path

from mayak import invite_codes
from mayak.bound_store import forget_orphaned_stores, is_bound, open_store, rebind
from mayak.contacts import Contact, ContactError
from mayak.envelope import Inbound
from mayak.kem import Kem
from mayak.node import Node, NodeError, TransportFactory
from mayak.store import Store, StoreError
from mayak.transport import TransportError
from mayak.vault import KeyVault

#: How often the host looks for held messages that are due, and for conversations
#: whose addresses have moved on.
#:
#: Not how often anything is retried: the outbox spaces and scatters its own
#: attempts — see :mod:`mayak.outbox` — and a look finds nothing due most of the
#: time. This bounds only how late a due attempt can be.
FLUSH_SECONDS = 5.0

_LOCK_POLL_SECONDS = 0.2

#: What a message event says it is.
EVENT_MESSAGE = "message"

#: Called with one event: a dict of strings.
EventSink = Callable[[dict[str, str]], None]


class HostError(ValueError):
    """Something an app asked for that cannot be done, in words it can show."""


class Host:
    """One device file, and the node that runs it, for an application."""

    def __init__(
        self,
        path: str | Path,
        passphrase: bytes,
        on_event: EventSink,
        *,
        kem: Kem,
        vault: KeyVault | None,
        transport_factory: TransportFactory,
        body_length: int,
        clock: Callable[[], float] = time.time,
        flush_seconds: float = FLUSH_SECONDS,
        allow_classical_only: bool = False,
    ) -> None:
        if not passphrase:
            raise HostError("a device file with no passphrase is not encrypted")
        self._path = Path(path)
        self._passphrase = bytes(passphrase)
        self._on_event = on_event
        self._kem = kem
        self._vault = vault
        self._transport_factory = transport_factory
        self._body_length = body_length
        self._clock = clock
        self._flush_seconds = flush_seconds
        self._allow_classical_only = allow_classical_only

        self._lock = threading.RLock()
        self._node: Node | None = None
        self._transmitting = True
        self._driver: threading.Thread | None = None
        self._stopping = threading.Event()
        #: Messages that opened and could not be handed to the app.
        self._undelivered = 0
        #: Retries that failed with something other than an unreachable contact.
        self._driver_failures = 0

    # ------------------------------------------------------------ lifecycle

    def start(self) -> None:
        """Open the device file — creating it on first use — and start receiving."""
        self._start(None)

    def _start(self, store: Store | None) -> None:
        with self._lock:
            if self._node is not None:
                return
            try:
                if self._vault is not None:
                    # The app has one device file. Keys of any other are left by
                    # files that are gone, and open nothing.
                    forget_orphaned_stores(self._vault, [self._path])
                if store is None:
                    store = open_store(self._path, self._passphrase, self._vault)
                node = Node.open(
                    self._kem,
                    self._transport_factory,
                    self._body_length,
                    store,
                    self._arrived,
                    clock=self._clock,
                    allow_classical_only=self._allow_classical_only,
                )
            except (StoreError, NodeError) as refused:
                raise HostError(str(refused)) from refused
            node.listen()
            self._node = node
            self._stopping.clear()
            self._driver = threading.Thread(target=self._drive, name="mayak-host", daemon=True)
            self._driver.start()

    def stop(self) -> int:
        """Stop receiving and retrying. Returns how many held messages were dropped."""
        with self._lock:
            node, self._node = self._node, None
            self._stopping.set()
            driver, self._driver = self._driver, None
        if driver is not None:
            driver.join(timeout=self._flush_seconds + 1)
        if node is None:
            return 0
        dropped = node.waiting
        node.stop()
        return dropped

    @property
    def running(self) -> bool:
        return self._node is not None

    # ------------------------------------------------------------ the file

    @property
    def bound(self) -> bool:
        """Whether the device file is bound to this phone's hardware."""
        return is_bound(self._path)

    @property
    def can_bind(self) -> bool:
        return self._vault is not None

    def set_bound(self, bind: bool) -> int:
        """Bind the device file to hardware, or unbind it. Returns held messages dropped.

        Nothing is dropped when the file already is what was asked for.
        """
        with self._lock:
            if bind == self.bound:
                return 0
            if bind and self._vault is None:
                raise HostError("there is no hardware keystore on this phone to bind the device file to")
            was_running = self.running
            dropped = self.stop()
            converted: Store | None = None
            try:
                converted = rebind(self._path, self._passphrase, self._vault, bind=bind)
            except StoreError as refused:
                raise HostError(str(refused)) from refused
            finally:
                if was_running:
                    # The converted store has already stretched the passphrase;
                    # opening the file afresh would do it again, and on a phone
                    # that is over a second.
                    self._start(converted)
            return dropped

    def wipe(self) -> None:
        """Destroy the device file, its hardware keys, and every key in memory."""
        with self._lock:
            node = self._node
            self.stop()
            if node is not None:
                node.wipe()
                return
            if not self._path.exists():
                return
            try:
                open_store(self._path, self._passphrase, self._vault).wipe()
            except StoreError as refused:
                raise HostError(str(refused)) from refused

    # ------------------------------------------------------------- contacts

    def invite(self) -> str:
        """A new one-time invitation, as text to hand to one person."""
        with self._lock:
            identity_key, kem_key = self._require_node().invite()
        return invite_codes.encode(identity_key, kem_key)

    def invitation_fingerprint(self, invitation: str) -> str:
        """The fingerprint of an invitation, to show beside it."""
        return invite_codes.spoken_fingerprint(*self._read_invitation(invitation))

    def add_contact(self, name: str, invitation: str) -> str:
        """Add someone from the invitation they sent. Returns their id."""
        identity_key, kem_key = self._read_invitation(invitation)
        with self._lock:
            node = self._require_node()
            try:
                contact = node.add_contact(name.strip(), identity_key, kem_key)
            except (ContactError, NodeError) as refused:
                raise HostError(str(refused)) from refused
            node.listen()
        return contact.identity_key.hex()

    def contacts(self) -> list[dict[str, str]]:
        """Every contact, as something a screen can show."""
        with self._lock:
            node = self._require_node()
            return [self._describe(node, contact) for contact in sorted(node.contacts, key=lambda c: c.name)]

    def verify(self, contact_id: str, fingerprint: str) -> None:
        """Record that a fingerprint was compared out loud and matched."""
        with self._lock:
            node = self._require_node()
            try:
                node.contacts.verify(_identity(contact_id), fingerprint)
            except ContactError as mismatch:
                raise HostError(str(mismatch)) from mismatch
            node.save()

    def forget(self, contact_id: str) -> None:
        with self._lock:
            try:
                self._require_node().forget_contact(_identity(contact_id))
            except NodeError as refused:
                raise HostError(str(refused)) from refused

    # ------------------------------------------------------------ messaging

    def send(self, contact_id: str, text: str) -> bool:
        """Write to a contact. True if it went out, False if held for a retry."""
        with self._lock:
            if not self._transmitting:
                raise HostError("radio silence is on; nothing is sent until it is off")
            node = self._require_node()
            try:
                return node.send(_identity(contact_id), text.encode("utf-8"))
            except (NodeError, TransportError, ValueError) as refused:
                raise HostError(str(refused)) from refused

    def set_transmitting(self, transmitting: bool) -> None:
        """Allow or forbid anything this host would put on the air."""
        with self._lock:
            self._transmitting = bool(transmitting)

    def status(self) -> dict[str, str]:
        with self._lock:
            node = self._node
            return {
                "running": _flag(node is not None),
                "bound": _flag(self.bound),
                "can_bind": _flag(self.can_bind),
                "vault": self._vault.name if self._vault is not None else "",
                "kem": self._kem.name,
                "transmitting": _flag(self._transmitting),
                "contacts": str(len(node.contacts)) if node is not None else "0",
                "open_invitations": str(node.open_invitations) if node is not None else "0",
                "waiting": str(node.waiting) if node is not None else "0",
                "undelivered": str(self._undelivered),
                "driver_failures": str(self._driver_failures),
            }

    # ------------------------------------------------------------- internal

    def _arrived(self, contact: Contact, message: Inbound) -> None:
        event = {
            "kind": EVENT_MESSAGE,
            "contact": contact.identity_key.hex(),
            "name": contact.name,
            "trust": contact.trust.value,
            "text": message.content.decode("utf-8", errors="replace"),
            "received_at": repr(self._clock()),
        }
        try:
            self._on_event(event)
        except Exception:  # noqa: BLE001 - never raise into the transport's thread; see the module docstring
            self._undelivered += 1

    def _drive(self) -> None:
        while not self._stopping.wait(self._flush_seconds):
            # Not a plain ``with``: stop() can be called by a thread already
            # holding the lock — set_bound does — and then waits for this thread
            # to end. Waiting on the lock here would hold both until the join
            # gave up.
            while not self._lock.acquire(timeout=_LOCK_POLL_SECONDS):
                if self._stopping.is_set():
                    return
            try:
                node = self._node
                if node is None or self._stopping.is_set():
                    return
                if node.needs_rollover():
                    node.roll_over()
                if self._transmitting and node.waiting:
                    node.flush()
            except Exception:  # noqa: BLE001 - one bad retry must not end retrying
                self._driver_failures += 1
            finally:
                self._lock.release()

    def _describe(self, node: Node, contact: Contact) -> dict[str, str]:
        ours = node.our_fingerprint_for(contact.identity_key)
        return {
            "id": contact.identity_key.hex(),
            "name": contact.name,
            "trust": contact.trust.value,
            "fingerprint": contact.spoken_fingerprint,
            "our_fingerprint": _spoken(ours) if ours is not None else "",
            "disputed": _flag(node.key_status(contact.identity_key).introduction_disputed),
        }

    def _read_invitation(self, invitation: str) -> tuple[bytes, bytes]:
        try:
            return invite_codes.decode(invitation, self._kem)
        except invite_codes.InvitationError as refused:
            raise HostError(str(refused)) from refused

    def _require_node(self) -> Node:
        if self._node is None:
            raise HostError("Mayak is not running")
        return self._node


def for_this_device(path: str | Path, passphrase: bytes, on_event: EventSink) -> Host:
    """A host over the running Reticulum, with this device's hardware and ML-KEM.

    Refuses to run classical-only. The command line lets a person insist on it
    with a flag; an app has no such person, and a phone that quietly sealed with
    X25519 alone would be recording traffic for a quantum computer to read.
    """
    from mayak.mechanisms import hybrid_mechanism
    from mayak.rns_transport import RnsTransport, body_length_for
    from mayak.vault import hardware_vault

    kem = hybrid_mechanism()
    if kem is None:
        raise HostError("this build has no post-quantum mechanism, and Mayak does not run without one")
    return Host(
        path,
        passphrase,
        on_event,
        kem=kem,
        vault=hardware_vault(),
        transport_factory=RnsTransport,
        body_length=body_length_for(kem),
    )


def _identity(contact_id: str) -> bytes:
    try:
        return bytes.fromhex(contact_id)
    except ValueError as unreadable:
        raise HostError("that is not a contact id") from unreadable


def _flag(value: bool) -> str:
    return "true" if value else "false"


def _spoken(fingerprint: str) -> str:
    return " ".join(fingerprint[at : at + 4] for at in range(0, len(fingerprint), 4))
