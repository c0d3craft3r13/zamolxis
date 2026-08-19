# Security Policy

## APK Verification

All official Zamolxis releases are signed with our release certificate
(RSA 4096, SHA384withRSA, valid until 2056-08-09). This certificate is
Zamolxis's own — it is **not** the upstream Columba one, so a Columba
fingerprint will never match a Zamolxis build.

**SHA-256:**
```
BD:6C:19:81:47:50:EB:11:81:33:B0:1B:40:6D:43:F5:C5:A3:0E:8F:BD:98:93:13:2D:AD:18:F2:FC:62:9D:E9
```

**SHA-1:**
```
9E:E3:3D:F0:6C:F0:B4:F2:CA:B3:E0:FA:93:29:E8:9F:E9:AD:52:3B
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
of it, Zamolxis seals *message text* with a hybrid X25519 + ML-KEM-768 layer
whenever the other side is also running Zamolxis and has exchanged keys — on by
default, switchable to "always" or off in Settings. Three limits are worth stating
plainly:

- The first message in each direction cannot be sealed: it is what carries the key.
- Attachments — images, files, voice notes — are **not** sealed by this layer. A
  message whose text was sealed while a photo travelled without it is labelled as
  such in the conversation, and "always seal" mode refuses to send it at all.
- Routing metadata (who talks to whom, when, how large) is protected only by
  Reticulum, exactly as before.

Each message records what it actually got, and the message-detail screen shows it,
so this is auditable per message rather than a claim about the app.

**Not covered today.** The on-device message database is not encrypted at rest and
screenshots are not blocked. Treat a seized unlocked device as fully compromised.
These are known gaps with planned work, not accidents.

## Supported Versions

Only the latest release receives security updates.
