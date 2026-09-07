<p align="center">
  <img src="./zamolxis-icon.png" width="180" height="180" alt="Zamolxis" />
</p>

# Zamolxis

**[Русский](README.md) | English | [Română](README.ro.md)**

[![CI](https://github.com/c0d3craft3r13/zamolxis/actions/workflows/ci.yml/badge.svg)](https://github.com/c0d3craft3r13/zamolxis/actions/workflows/ci.yml)

Zamolxis is a messenger and voice app for Android that depends on no internet, no cell towers and no servers of any kind.
Messages and calls travel straight between devices over Bluetooth, Wi-Fi, radio (LoRa), or through whatever Reticulum nodes happen to be reachable.

No accounts.
No phone numbers.
No sign-up.
Nothing to block and nothing to seize.

---

<p align="center">
  <img src="./docs/images/hero-banner.jpg" alt="Zamolxis — the network that cannot be switched off" />
</p>

<p align="center">
  <a href="./docs/media/promo-clip.mp4">
    <img src="./docs/images/promo.webp" width="760" alt="A message travelling phone to phone across a night city" />
  </a>
</p>

<p align="center"><sub>Click to open the full clip</sub></p>

---

### Who this is for

- Journalists and activists working under censorship or network shutdowns
- People on expeditions, in remote areas, and in emergencies
- Anyone who would rather their private conversations did not pass through someone else's servers

---

### What sets Zamolxis apart

| Capability                                | Ordinary messengers | Columba¹ | **Zamolxis**                 |
|-------------------------------------------|---------------------|----------|------------------------------|
| Works without the internet                 | No                  | Yes      | **Yes**                      |
| No accounts, no central servers            | No                  | Yes      | **Yes**                      |
| Post-quantum encryption                    | Rarely              | No       | **Yes (X25519 + ML-KEM-768)**|
| Conversations encrypted on the device      | Partly              | No       | **Yes (SQLCipher)**          |
| PIN lock on the app                        | Yes                 | No       | **Yes**                      |
| **Duress PIN** (destroys the data)         | Almost nowhere      | No       | **Yes**                      |
| Screenshots and screen recording blocked   | Rarely              | No       | **Yes**                      |
| Group chats                                | Yes                 | No       | **Yes**                      |
| Voice calls                                | Yes                 | Yes      | **Yes**                      |
| Offline maps and safe location sharing     | No                  | Yes      | **Yes**                      |

<sub>¹ Columba is the project Zamolxis grew out of. The comparison reflects its upstream as of 21 August 2026.</sub>

---

### What matters most

**1. Protection against coercion — the duress PIN**
Once an ordinary PIN is set, a second one can be added: the emergency PIN.
It looks exactly the same. If you are forced to unlock the app, you enter the emergency PIN. Everything is destroyed beyond recovery and the app opens as if it had just been installed. No warning, no confirmation, and no trace that a second PIN ever existed.

The emergency PIN works even while the app is temporarily locked out after failed attempts — otherwise whoever took the phone and tried a few guesses would have taken the escape hatch with it.

**2. Protection against the future**
Between Zamolxis users, messages are additionally sealed with hybrid post-quantum encryption (X25519 + ML-KEM-768). Even traffic recorded today will be extremely hard to decrypt later.

By default this runs in a "where possible" mode: the seal is applied when the other side understands it and the link can carry the extra bytes — on slow LoRa radio those bytes cost seconds of airtime. For anyone who wants a guarantee, settings offer a strict mode: refuse to send at all if the message cannot be sealed.

<p align="center">
  <img src="./docs/images/feature-post-quantum.jpg" width="620" alt="A sealed letter set in Dacian ornament" />
</p>

**3. Data under lock**
Every conversation on the phone lives in a database encrypted with SQLCipher under a key held in Android's hardware-backed keystore. Copying the database off the device does not make it readable.

**4. The screen under lock**
Screenshots, screen recording and casting are blocked, and Android keeps no thumbnail of the app for the Recents switcher — without that, a locked app would still show the last open conversation to anyone who swiped up. On by default, switchable in settings.

**5. Genuine independence**
It works over Bluetooth for people near you, over radio at distance, and through network nodes whenever they are reachable. The internet is optional.

<p align="center">
  <img src="./docs/images/feature-mesh.jpg" width="620" alt="A message hopping phone to phone across a night city" />
</p>

**6. No trace at all**
No accounts, no central server, no user directory. Nothing to seize and nothing to block.

---

### What it does today

- Messages and voice calls without the internet
- Group chats
- Duress PIN — emergency destruction of the data
- A growing delay after failed PIN attempts
- Screenshot and screen-recording blocking
- Several identities on one device
- Safe location sharing and offline maps
- Browsing NomadNetwork pages
- Key backup and moving your identity to another phone
- Fully customisable appearance

Message history deliberately does **not** leave the device: the database is encrypted with a key that never leaves the phone, so a copy elsewhere would not open anyway. What transfers is your keys and identity, not your messages.

---

### How it works, in plain words

<p align="center">
  <img src="./docs/images/how-it-works.png" alt="A key made on the phone, a QR exchange, delivery over Bluetooth, Wi-Fi, radio and relay, read only by the recipient" />
</p>

Picture a network where phones, radios and computers find each other on their own and pass messages onward — a living chain.
No single "main" server is needed.

The internet goes down — the link holds.
The towers are switched off — the link holds.
You are somewhere with no coverage at all — a radio module will do.

Zamolxis is built on the open [Reticulum](https://reticulum.network/) protocol, one of the most resilient ways to communicate without centralised infrastructure.

<p align="center">
  <img src="./docs/images/feature-direct.jpg" width="620" alt="Two phones messaging directly, the cell tower crossed out" />
</p>

---

### Installing

1. Download the APK from the [Releases](https://github.com/c0d3craft3r13/zamolxis/releases) page
2. Verify the signature — the procedure is in [SECURITY.md](./SECURITY.md); do not skip it
3. Install the app and create your identity inside it

Or install through Obtainium:

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/c0d3craft3r13/zamolxis">
  <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="56" alt="Get it on Obtainium" />
</a>

---

### Security

Zamolxis is built for situations where the adversary may control the network or physically get hold of the device.

- Messages are end-to-end encrypted, and post-quantum sealed between Zamolxis users
- The on-device database is encrypted
- There is an ordinary PIN, a delay after failed attempts, and a **duress PIN** that destroys the data
- The screen is protected from screenshots, recording and casting

The full threat model, and what it does **not** yet cover, is in [SECURITY.md](./SECURITY.md).

Found a vulnerability? Report it **only** through [GitHub Security Advisories](https://github.com/c0d3craft3r13/zamolxis/security/advisories/new). Never open a public issue describing one.

---

### Why the name "Zamolxis"

Zamolxis was a Dacian god associated with immortality.
As the story goes, he withdrew into an underground chamber for three years and everyone took him for dead. Then he came back.

A fitting name for a network that can go quiet — and reappear.

---

### Supporting the project

Zamolxis is free and open source.

**USDT (TRC-20, Tron network):**
`TNPzvsfsdNC3XrxJPB2NMVh1nZSPcvZzxC`

<p align="center">
  <img src="./docs/images/donate-usdt-trc20.png" width="160" alt="QR code for USDT TRC-20 donations" />
</p>

---

### Licence

Mozilla Public License 2.0 — see [LICENSE.md](./LICENSE.md).

The project is based on code from [Columba](https://github.com/torlando-tech/columba) but develops independently.
The original project's authors are unaffiliated with Zamolxis and do not endorse it.
