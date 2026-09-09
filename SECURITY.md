# Security Policy

## APK Verification

All official Zamolxis releases are signed with our release certificate
(RSA 4096, APK Signature Scheme v2, valid until 2054-01-24). This certificate
is Zamolxis's own — it is **not** the upstream Columba one, so a Columba
fingerprint will never match a Zamolxis build.

The fingerprints below belong to the key introduced with 2.3.0 and differ from
those published before it. Nothing was signed with the earlier key: no release
had ever been produced under it, so no installed build carries it.

**SHA-256:**
```
25:E6:F2:34:65:76:BE:DF:01:B0:18:9F:5F:91:E5:5B:67:97:22:F5:6D:07:3E:F3:23:54:F1:3D:B6:A7:E5:A9
```

**SHA-1:**
```
DB:2D:57:61:C1:81:47:52:95:10:03:5A:21:7E:34:AC:82:03:4E:A1
```

### Verifying Before Installation

With Android SDK tools installed:

```bash
apksigner verify --print-certs zamolxis-x.x.x.apk
```

Compare the SHA-256 digest in the output with the fingerprint above.

### SHA256 Checksums

Release APKs include a `.sha256` file:

```bash
sha256sum -c zamolxis-x.x.x.apk.sha256
```

### If Verification Fails

Do not install. Delete the APK and download only from
[GitHub Releases](https://github.com/c0d3craft3r13/zamolxis/releases).
Report suspicious APKs via
[GitHub Issues](https://github.com/c0d3craft3r13/zamolxis/issues).

**Note:** Android automatically verifies that app updates are signed with the
same certificate, so subsequent updates are protected after you've verified your
first installation.

## Reporting Vulnerabilities

**Do not open a public issue for a vulnerability.** Zamolxis carries private
messages and identity keys, so a public report discloses the flaw to attackers
before a fixed release exists.

Report privately via
[GitHub Security Advisories](https://github.com/c0d3craft3r13/zamolxis/security/advisories/new).
Include steps to reproduce, affected version, and potential impact.

You can expect an initial response within 7 days. Once a fix ships, we will
credit you in the advisory unless you ask us not to.

Public [GitHub Issues](https://github.com/c0d3craft3r13/zamolxis/issues) remain
the right place for hardening suggestions and questions about this policy —
anything that does not expose users if it is read by everyone.

## Threat Model

Zamolxis is built for people whose adversary controls the network: journalists,
field operators, and anyone who cannot assume an ISP or carrier is neutral. What
that does and does not cover:

**Covered.** Message content and metadata in transit are end-to-end encrypted by
Reticulum. There are no accounts, no phone numbers, no central server to subpoena
or seize, and no directory that reveals who talks to whom. Delivery works over
Bluetooth LE, LoRa, and local Wi-Fi with no internet at all.

**Partly covered: post-quantum.** Reticulum's transport encryption is classical,
so traffic captured today could be decrypted by a future quantum adversary. On top
of it, Zamolxis seals the message payload — text, images, files, voice notes, and
the quoted text of a reply — with a hybrid X25519 + ML-KEM-768 layer whenever the
other side is also running Zamolxis and has exchanged keys. On by default,
switchable to "always" or off in Settings. Three limits are worth stating plainly:

- The first message in each direction cannot be sealed: it is what carries the key.
- An attachment over 4 MB is sealed only as far as its text. Sealing is not
  streamed, so a large payload would need several times its own size in memory;
  above that limit the attachment travels with Reticulum's encryption alone, the
  message says so, and "always seal" mode refuses to send it.
- Routing metadata is protected only by Reticulum, exactly as before: who talks to
  whom, when, how large, the reply target, reactions, telemetry and profile icon.
  The protocol layer has to read those to deliver and route the message at all.

Each message records what it actually got, and the message-detail screen shows it,
so this is auditable per message rather than a claim about the app.

**Partly covered: not standing out on the air.** An announce is broadcast across the
mesh and its `app_data` travels in the clear — Reticulum signs it but does not
encrypt it. What this app announces is therefore byte-identical to what upstream
LXMF announces, and a test holds it to vectors taken from that reference: a captured
announce cannot tell this app apart from any other LXMF node. It used to carry a
post-quantum key fingerprint, which labelled every announce as ours and labelled it
harder the more protection the user had switched on.

That is one channel of several, and the others are not closed. A sealed message is
about 1.1 KB larger than the same message unsealed, which is more than a single
Reticulum packet holds, so it travels as a link and a multi-packet transfer where an
ordinary short message travels as one packet. Counting packets still separates a
protected conversation from an unprotected one. And no software change hides the
fact that a radio transmitted at all: on LoRa, direction-finding works on the
physical signal regardless of what is inside it.

**Covered: the message database at rest.** The database holding messages,
conversations, contacts and identities is encrypted with SQLCipher. The passphrase
is 256 random bits generated on the device at first launch and stored wrapped by a
hardware-backed Android Keystore key, which never leaves the device. Nothing derives
it from a PIN or password: the background service has to store messages that arrive
while the app is locked or closed, so a key that needs a human present would mean
losing those messages. An install that predates this is converted on first launch.

What that does and does not buy you:

- A device seized while powered off, or a copy of the app's data pulled off it,
  yields ciphertext.
- A device seized *unlocked*, or one with root access to the running app, yields
  everything — the app is running, so the key is in use. The app PIN is a lock on
  the UI, not on the database.
- The database is deliberately excluded from Android cloud backup and device
  transfer, because a restored copy could never be opened on other hardware. Moving
  to a new phone goes through the export below instead.

**Covered: moving to another phone.** An export is written into a sealed container
rather than a password-protected archive. One random 256-bit key encrypts the data;
that key is then wrapped once per way of opening the file. The password slot
stretches what you type with Argon2id — memory-hard, so a graphics card runs tens of
guesses in parallel where PBKDF2 would let it run tens of thousands. The recovery
slot holds 256 bits of randomness shown once at export, which cannot be guessed at
all and is the reason a weak password is not the ceiling on an export's strength.
Either slot opens the file on its own. The archive is streamed straight into the
container, so no plaintext copy is ever written to flash and then deleted — on flash,
deletion is not erasure. Chunks authenticate their own position, so a truncated file
is refused rather than yielding a prefix.

**Covered: who a message is from.** Reticulum encrypts a message to its recipient
but does not prove who wrote it; the proof is the LXMF signature, and a message
carries its sender's address as a plain claim next to it. A message whose signature
does not verify against the identity it names is dropped before anything in the app
sees it — including the reaction, telemetry and profile-icon side channels, which a
forged message must not be able to drive either.

Checking a signature needs the sender's public key, and a sender whose key is not
held cannot be checked by anyone. Those messages are delivered, because otherwise
no one could ever make first contact, but they are not treated as verified. What
must never happen is that gap opening under a name you trust, so the keys of saved
contacts are loaded into the stack by the background service as soon as it starts —
not only by the app, which may not be running when mail arrives. A message from a
saved contact that still could not be checked is refused rather than shown: their
key is on file, so either it is not really from them, or the identity cache lost
it. The key is put back on the way out, so the honest version of that fixes itself
on their next message and the dishonest one keeps failing.

**Covered: the screen.** Screenshots, screen recording and casting are blocked, and
Android is told to keep no thumbnail of the app for the Recents switcher — without
that, a locked app still shows its last open conversation to anyone who swipes up.
On by default and switchable in Settings, because some people need to file a bug
report more than they need this.

**Not covered today.** The interface-configuration database (`interface_database`)
and Reticulum's routing state (`reticulum.db`) are not encrypted; `reticulum.db`
holds ratchet private keys, so both are excluded from Android cloud backup and
device transfer, but on the device itself they are plaintext. The post-quantum layer
is opportunistic by default: when the other side has not advertised a key, or the
link is a sub-kbps radio, the message goes with Reticulum's encryption alone and
says so — "always" mode refuses to send instead. Treat a seized unlocked device as
fully compromised. These are known gaps with planned work, not accidents.

## Supported Versions

Only the latest release receives security updates.
