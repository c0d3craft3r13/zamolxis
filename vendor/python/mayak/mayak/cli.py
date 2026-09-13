"""Working the protocol by hand, so it can be used before there is an app.

## What this is for

Everything below this module is a library with no opinion about who is driving
it. That is right, and it also means the protocol has never been operated by a
person — only by tests, which agree with the code by construction. A command
line is the cheapest way to find out what the library is actually like to use,
and the first honest end-to-end check: two machines, two passphrases, no shared
fixture deriving both sides' keys from one seed.

It is not the product. It is the thing that proves the product is possible, and
the reference for what an application has to do.

## Two decisions the library refuses, made here

:mod:`mayak.node` will not decide **when to transmit** or **whom to trust**, and
says why. Something has to, so this does:

- **Transmitting.** ``listen`` owns the radio for as long as it runs, so it may
  keep a timer: it retries held messages every ``--flush-seconds`` and rolls
  addresses over when the epoch turns. An application that also owns a radio
  silence switch must take that decision back. ``send`` owns nothing — it hands
  over one message and exits, and says so when it could not.
- **Trust.** Unverified contacts are written to, and every line that shows a
  message says what the trust was. A courier and a journalist want opposite
  defaults, so this one shows rather than blocks, and refuses to be quiet
  about it.

## The mechanism, and what happens where there is none

``seal`` refuses to run without a post-quantum half.
:func:`post_quantum_mechanism` is the seam one arrives through, and on a build
with ``cryptography`` 47 or later it finds ML-KEM-768 and nothing needs saying.

Where it finds none — Android, where Chaquopy's newest ``cryptography`` is
42.0.8 — this refuses to start unless ``--insecure-classical-only`` is given in
as many words, and says on every run what that costs: traffic recorded today is
readable by whoever has a quantum computer later. The flag is long and ugly on
purpose, and is ignored by a build that does not need it — asking to be weakened
for no reason is a mistake worth not honouring.

## Everything is printed flushed

``RNS.Reticulum`` replaces ``sys.stdout`` and ``sys.stderr`` with the null
device when the process exits, so anything still sitting in a buffer at that
moment is lost — a success line vanishes the moment the output is piped
anywhere. Found here rather than reasoned about: the first end-to-end run
delivered its message and printed nothing about it.

## Text is forced to UTF-8, in and out

A Windows console hands Python whatever code page the machine was set up with —
cp1251 on the machine this was written on. That breaks a messenger twice over,
and the end-to-end check found both by typing one Cyrillic reply:

- **Printing** a message in a script that page does not cover raises
  ``UnicodeEncodeError``, which kills ``listen``. One message with an emoji in
  it and the process that was receiving is gone.
- **Reading** a typed message decodes UTF-8 bytes as cp1251, so the message that
  went out was mojibake — sealed, delivered, and unreadable, with nothing
  anywhere reporting a problem.

So all three streams are reconfigured, with replacement rather than strictness:
a character this machine cannot render costs a glyph rather than the process.

## The passphrase

Read from a file the caller names, or asked for at the terminal. Never from an
argument and never from the environment: a command line is visible to every
process on the machine, and so is an environment block.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import queue
import sys
import threading
import time
from collections.abc import Sequence
from pathlib import Path

from mayak.contacts import IDENTITY_KEY_LENGTH, Contact, ContactError, Trust
from mayak.envelope import Inbound
from mayak.kem import HybridKem, Kem, X25519Kem
from mayak.mechanisms import post_quantum_mechanism
from mayak.node import Node, NodeError
from mayak.rns_transport import RnsTransport, body_length_for, drain
from mayak.store import EncryptedStore, StoreError
from mayak.transport import TransportError

#: Where a device keeps itself when the caller does not say otherwise.
DEFAULT_DEVICE_FILE = Path.home() / ".mayak" / "device.mayak"

#: What an invitation looks like when it is pasted into a message or read off a
#: screen. The prefix is there so a person can tell what they have been sent,
#: and so a future layout can be refused rather than misread.
INVITE_PREFIX = "mayak1:"

#: How often ``listen`` wakes to check for held messages and epoch rollover.
DEFAULT_FLUSH_SECONDS = 60.0

#: How long it sleeps between those checks. Short enough that Ctrl+C is
#: answered promptly, which matters more than the wake-up cost of a process
#: someone is watching.
_POLL_SECONDS = 0.5


class CliError(Exception):
    """Something the person running this needs to be told, without a traceback."""


def make_text_survivable() -> None:
    """Read and write any script, on a console that was configured for one.

    Input as well as output: a message typed in one encoding and read in another
    is sealed and delivered as mojibake, which is worse than failing, because
    nothing reports it. Guarded, because a stream that is not a text wrapper — a
    test capturing into a buffer — has nothing to reconfigure and needs nothing.
    """
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError):
                pass


def say(*parts: str, error: bool = False) -> None:
    """Print, and make sure it has actually left the process.

    Reticulum swaps the real streams for the null device on the way out, so a
    line that is only buffered is a line nobody ever sees — see the module
    docstring.
    """
    print(*parts, file=sys.stderr if error else sys.stdout, flush=True)


# --------------------------------------------------------------- mechanisms


def build_kem(*, classical_only: bool) -> tuple[Kem, bool]:
    """Return the mechanism to use and whether it had to be weakened.

    The second half of the pair is what every command prints a warning from. A
    build with a real post-quantum member ignores the flag entirely — asking for
    a downgrade you do not need is a mistake worth not honouring.
    """
    quantum = post_quantum_mechanism()
    if quantum is not None:
        return HybridKem(X25519Kem(), quantum), False
    if not classical_only:
        raise CliError(
            "no post-quantum mechanism is installed, so sealing would be classical-only.\n"
            "Anything recorded today would be readable by whoever has a quantum computer later.\n"
            "Pass --insecure-classical-only to proceed anyway.",
        )
    return X25519Kem(), True


# ------------------------------------------------------------- invitations


def invitation(identity_key: bytes, kem_key: bytes) -> str:
    """The two public keys as one string somebody can send."""
    raw = base64.urlsafe_b64encode(identity_key + kem_key).decode("ascii").rstrip("=")
    return INVITE_PREFIX + raw


def read_invitation(text: str, kem: Kem) -> tuple[bytes, bytes]:
    """Take an invitation apart, refusing anything that is not one.

    The encapsulation key length is checked against the mechanism in use. That
    catches two people running different mechanisms here, where it can be said
    plainly, rather than later as messages that never open.
    """
    offered = text.strip()
    if not offered.startswith(INVITE_PREFIX):
        raise CliError(f"that does not look like an invitation; they start with {INVITE_PREFIX!r}")

    body = offered[len(INVITE_PREFIX) :]
    try:
        raw = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
    except (ValueError, TypeError) as unreadable:
        raise CliError("the invitation is damaged and cannot be read") from unreadable

    expected = IDENTITY_KEY_LENGTH + kem.public_key_length
    if len(raw) != expected:
        raise CliError(
            f"the invitation carries {len(raw)} bytes of key and this build expects {expected}.\n"
            f"That usually means the other device is not running {kem.name}.",
        )
    return raw[:IDENTITY_KEY_LENGTH], raw[IDENTITY_KEY_LENGTH:]


def fingerprint_of(identity_key: bytes, kem_key: bytes) -> str:
    """Our own fingerprint, by the same route a contact's is computed.

    Through :class:`~mayak.contacts.Contact` rather than a second copy of the
    hash, because the number printed here and the number the other person sees
    must be the same number, and two implementations of one hash is how they
    stop being.
    """
    return Contact(name="me", identity_key=identity_key, kem_key=kem_key).spoken_fingerprint


# ------------------------------------------------------------------ wiring


def passphrase_for(arguments: argparse.Namespace, *, confirm: bool = False) -> bytes:
    """Where the passphrase comes from: a named file, or the terminal."""
    if arguments.passphrase_file:
        path = Path(arguments.passphrase_file)
        try:
            # Only the first line, and without its newline: an editor that adds
            # one must not change the key the store is encrypted under.
            return path.read_text(encoding="utf-8").splitlines()[0].encode("utf-8")
        except (OSError, IndexError) as unreadable:
            raise CliError(f"the passphrase file could not be read: {unreadable}") from unreadable

    entered = getpass.getpass("passphrase: ").encode("utf-8")
    if confirm and entered != getpass.getpass("again: ").encode("utf-8"):
        raise CliError("the two entries differ")
    if not entered:
        raise CliError("a store with no passphrase is not encrypted")
    return entered


def _store(arguments: argparse.Namespace, *, confirm: bool = False) -> EncryptedStore:
    try:
        return EncryptedStore(Path(arguments.file), passphrase_for(arguments, confirm=confirm))
    except StoreError as refused:
        raise CliError(str(refused)) from refused


def _open(
    arguments: argparse.Namespace,
    *,
    on_message=lambda contact, message: None,
    confirm_passphrase: bool = False,
) -> tuple[Node, Kem]:
    kem, weakened = build_kem(classical_only=arguments.insecure_classical_only)
    if weakened:
        say(
            "warning: sealing with X25519 alone. Recorded traffic is not protected "
            "against an adversary who later has a quantum computer.",
            error=True,
        )

    store = _store(arguments, confirm=confirm_passphrase)
    try:
        node = Node.open(
            kem,
            RnsTransport,
            body_length_for(kem),
            store,
            on_message,
            allow_classical_only=weakened,
        )
    except (StoreError, NodeError) as refused:
        raise CliError(str(refused)) from refused
    return node, kem


def _start_reticulum(arguments: argparse.Namespace) -> None:
    """Attach to Reticulum, without configuring anything on its behalf.

    Which interfaces exist, whether transport is enabled and what is on the air
    are the operator's decisions and belong in Reticulum's own config file. A
    messenger that wrote interfaces into it would be putting a radio on the air
    that nobody asked for.
    """
    import RNS

    RNS.Reticulum(arguments.rns_config, loglevel=arguments.rns_loglevel)


def _find(node: Node, name: str) -> Contact:
    """Resolve a contact by the name a person typed.

    Names are not unique and are not what addresses come from, so two matches
    are refused rather than guessed between — writing to the wrong person
    because they share a first name is not a mistake to make quietly.
    """
    matches = [contact for contact in node.contacts if contact.name == name]
    if not matches:
        raise CliError(f"no contact called {name!r}")
    if len(matches) > 1:
        lines = "\n".join(f"  {contact.spoken_fingerprint}" for contact in matches)
        raise CliError(f"{len(matches)} contacts are called {name!r}; they differ by fingerprint:\n{lines}")
    return matches[0]


def _describe(contact: Contact) -> str:
    marker = {Trust.VERIFIED: "verified", Trust.UNVERIFIED: "unverified", Trust.CHANGED: "KEYS CHANGED"}
    return f"{contact.name} [{marker[contact.trust]}] {contact.spoken_fingerprint}"


def _spoken(fingerprint: str) -> str:
    return " ".join(fingerprint[at : at + 4] for at in range(0, len(fingerprint), 4))


def _print_invitation(identity_key: bytes, kem_key: bytes) -> None:
    say(f"fingerprint of this invitation: {fingerprint_of(identity_key, kem_key)}")
    say()
    say("send this to one person who should be able to reach you. It works once:")
    say(invitation(identity_key, kem_key))


# ---------------------------------------------------------------- commands


def command_init(arguments: argparse.Namespace) -> int:
    path = Path(arguments.file)
    if path.exists():
        raise CliError(f"{path} already exists; wipe it first if you mean to start over")

    node, _ = _open(arguments, confirm_passphrase=True)
    say(f"device created at {path}")
    _print_invitation(*node.invite())
    return 0


def command_invite(arguments: argparse.Namespace) -> int:
    """A new one-time invitation. Each person gets their own — see mayak.invitations."""
    node, _ = _open(arguments)
    _print_invitation(*node.invite())
    return 0


def command_whoami(arguments: argparse.Namespace) -> int:
    node, _ = _open(arguments)
    open_count = node.open_invitations
    say(f"open invitations: {open_count}")
    say("Invitations work once. For someone new, run: mayak invite")
    return 0


def command_add(arguments: argparse.Namespace) -> int:
    node, kem = _open(arguments)
    identity_key, kem_key = read_invitation(arguments.invitation, kem)
    try:
        contact = node.add_contact(arguments.name, identity_key, kem_key)
    except (ContactError, NodeError) as refused:
        raise CliError(str(refused)) from refused

    say(f"added {contact.name}")
    say(f"fingerprint: {contact.spoken_fingerprint}")
    say()
    say("They are unverified until you have compared that fingerprint with them")
    say("by some route nobody could have stood in the middle of. Then:")
    say(f"  mayak verify {contact.name} '{contact.spoken_fingerprint}'")
    return 0


def command_contacts(arguments: argparse.Namespace) -> int:
    node, _ = _open(arguments)
    contacts = sorted(node.contacts, key=lambda contact: contact.name)
    if not contacts:
        say("no contacts yet")
        return 0
    for contact in contacts:
        say(_describe(contact))
        ours = node.our_fingerprint_for(contact.identity_key)
        if ours is None:
            say("  you, as they know you: not known until they write")
        else:
            say(f"  you, as they know you: {_spoken(ours)}")
        if node.key_status(contact.identity_key).introduction_disputed:
            say(
                "  WARNING: they named an invitation already used by someone else or never issued here."
                " Compare fingerprints before trusting anything from them.",
            )
    return 0


def command_verify(arguments: argparse.Namespace) -> int:
    node, _ = _open(arguments)
    contact = _find(node, arguments.name)
    try:
        updated = node.contacts.verify(contact.identity_key, arguments.fingerprint)
    except ContactError as mismatch:
        raise CliError(str(mismatch)) from mismatch
    node.save()
    say(f"{updated.name} is verified")
    return 0


def command_forget(arguments: argparse.Namespace) -> int:
    node, _ = _open(arguments)
    contact = _find(node, arguments.name)
    node.forget_contact(contact.identity_key)
    say(f"{contact.name} is forgotten. Anything they sealed to these keys stays unreadable here.")
    return 0


def command_send(arguments: argparse.Namespace) -> int:
    node, _ = _open(arguments)
    contact = _find(node, arguments.name)
    if contact.trust is Trust.CHANGED:
        say(f"warning: {contact.name}'s keys changed since you verified them", error=True)

    _start_reticulum(arguments)
    try:
        handed_over = node.send(contact.identity_key, arguments.text.encode("utf-8"))
    except (NodeError, TransportError, ValueError) as refused:
        # Every refusal the protocol raises is a ValueError underneath — a frame
        # that will not hold the content, an epoch that will not seal, a message
        # of more pieces than a transfer may have. To a person at a terminal
        # they are all the same thing: a line saying what went wrong.
        raise CliError(str(refused)) from refused

    # This command exits next, and a process that exits straight after sending
    # loses its last packets — see mayak.rns_transport.drain.
    if not drain():
        say("warning: Reticulum had not written everything out when this command stopped waiting", error=True)

    if handed_over:
        say(f"sent to {contact.name}")
        return 0

    # Held messages live in memory and are not written to the store — see
    # Node.save. Saying so is the difference between a queue and a loss.
    say(f"{contact.name} could not be reached, and this command does not stay running.")
    say("Nothing was kept. Write from `mayak listen` instead, which holds and retries.")
    return 1


def _typed_lines() -> queue.Queue[str | None]:
    """Whatever is typed, off the main thread.

    Reading input in the loop that also receives would mean nothing arrives
    while somebody is deciding what to write. A thread and a queue keep the two
    apart; ``None`` means the input ended, which happens whenever this is run
    with its input closed and must not be mistaken for a reason to stop.
    """
    lines: queue.Queue[str | None] = queue.Queue()

    def read() -> None:
        try:
            for line in sys.stdin:
                lines.put(line.rstrip("\n"))
        except (OSError, ValueError):
            pass
        lines.put(None)

    threading.Thread(target=read, daemon=True).start()
    return lines


def _write_from_terminal(node: Node, typed: str) -> None:
    """Handle one line somebody typed into ``listen``."""
    name, _, body = typed.partition(":")
    if not body.strip():
        say("write as `Bob: your message`, or /quit", error=True)
        return

    try:
        contact = _find(node, name.strip())
        handed_over = node.send(contact.identity_key, body.strip().encode("utf-8"))
    except CliError as told:
        say(str(told), error=True)
        return
    except (NodeError, TransportError, ValueError) as refused:
        say(str(refused), error=True)
        return

    if handed_over:
        say(f"{time.strftime('%H:%M:%S')} → {contact.name}: {body.strip()}")
    else:
        say(f"{contact.name} could not be reached; held and retried from here.")


def command_listen(arguments: argparse.Namespace) -> int:
    def arrived(contact: Contact, message: Inbound) -> None:
        stamp = time.strftime("%H:%M:%S")
        trust = "" if contact.trust is Trust.VERIFIED else f" [{contact.trust.value}]"
        try:
            body = message.content.decode("utf-8")
        except UnicodeDecodeError:
            body = repr(message.content)
        say(f"{stamp} {contact.name}{trust}: {body}")

    node, _ = _open(arguments, on_message=arrived)
    _start_reticulum(arguments)
    node.listen()

    say(f"listening for {len(node.contacts)} contacts.")
    say("Write as `Bob: your message`. /quit or Ctrl+C to stop.")

    typed = _typed_lines()
    last_flush = time.monotonic()
    try:
        while True:
            if node.needs_rollover():
                node.roll_over()

            try:
                line = typed.get(timeout=_POLL_SECONDS)
            except queue.Empty:
                line = ""
            if line is None:
                # Input ended. Nothing to do about it: this is still a listener.
                typed = queue.Queue()
            elif line.strip() in ("/quit", "/exit"):
                break
            elif line.strip():
                _write_from_terminal(node, line)

            now = time.monotonic()
            if node.waiting and now - last_flush >= arguments.flush_seconds:
                last_flush = now
                delivered = node.flush()
                if delivered:
                    say(f"{delivered} held message(s) went out")
    except KeyboardInterrupt:
        pass
    finally:
        node.stop()
        drain()
    if node.waiting:
        say(f"{node.waiting} message(s) were still waiting and are gone.", error=True)
    return 0


def command_wipe(arguments: argparse.Namespace) -> int:
    path = Path(arguments.file)
    if not path.exists():
        say(f"{path} does not exist")
        return 0

    if not arguments.yes:
        say(f"This destroys {path}: the identity keys and every contact.")
        say("It cannot be undone, and nothing is backed up anywhere.")
        if input("Type WIPE to confirm: ").strip() != "WIPE":
            say("nothing was wiped")
            return 1

    node, _ = _open(arguments)
    node.wipe()
    say("wiped.")
    say("The file is overwritten and removed. On a phone's flash the old blocks may")
    say("survive until the device reuses them — what survives is ciphertext whose key")
    say("no longer exists anywhere.")
    return 0


# ------------------------------------------------------------------ parsing


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="mayak", description=__doc__.splitlines()[0])
    parser.add_argument("--file", default=str(DEFAULT_DEVICE_FILE), help="the device file (default: %(default)s)")
    parser.add_argument("--passphrase-file", default=None, help="read the passphrase from this file instead of asking")
    parser.add_argument(
        "--insecure-classical-only",
        action="store_true",
        help="seal with X25519 alone when no post-quantum mechanism is installed",
    )
    parser.add_argument("--rns-config", default=None, help="Reticulum config directory (default: Reticulum's own)")
    parser.add_argument("--rns-loglevel", type=int, default=2, help="Reticulum log level 0-7 (default: %(default)s)")

    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("init", help="create a device and its keys").set_defaults(run=command_init)
    commands.add_parser("invite", help="make a one-time invitation for one person").set_defaults(run=command_invite)
    commands.add_parser("whoami", help="how many invitations are open").set_defaults(run=command_whoami)

    add = commands.add_parser("add", help="record someone from their invitation")
    add.add_argument("name")
    add.add_argument("invitation")
    add.set_defaults(run=command_add)

    commands.add_parser("contacts", help="list who this device can reach").set_defaults(run=command_contacts)

    verify = commands.add_parser("verify", help="record that a fingerprint was compared in person")
    verify.add_argument("name")
    verify.add_argument("fingerprint")
    verify.set_defaults(run=command_verify)

    forget = commands.add_parser("forget", help="remove a contact")
    forget.add_argument("name")
    forget.set_defaults(run=command_forget)

    send = commands.add_parser("send", help="write to a contact and exit")
    send.add_argument("name")
    send.add_argument("text")
    send.set_defaults(run=command_send)

    listen = commands.add_parser("listen", help="stay up, receive, and retry what is held")
    listen.add_argument("--flush-seconds", type=float, default=DEFAULT_FLUSH_SECONDS)
    listen.set_defaults(run=command_listen)

    wipe = commands.add_parser("wipe", help="destroy the device file")
    wipe.add_argument("--yes", action="store_true", help="do not ask")
    wipe.set_defaults(run=command_wipe)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    make_text_survivable()
    arguments = build_parser().parse_args(argv)
    try:
        return arguments.run(arguments)
    except CliError as told:
        say(f"mayak: {told}", error=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
