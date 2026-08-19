<p align="center">
  <img src="./zamolxis-icon.png" width="200" height="200" alt="Zamolxis" />
</p>

# Zamolxis

Zamolxis is a messaging and voice app for the [Reticulum](https://github.com/markqvist/Reticulum) network on Android. Send [LXMF](https://github.com/markqvist/LXMF) messages and make [LXST](https://github.com/markqvist/LXST/tree/master/LXST) voice calls without relying on the internet, cell towers, or any central servers.

It is built for people who cannot assume the network is neutral — reporters, field operators, and anyone who would rather not route their private conversations through an infrastructure someone else controls. No accounts, no phone numbers, no directory, nothing to seize.

## What You Can Do

- **Message without infrastructure** — Send messages even when the internet is down, throttled, or shut off
- **Connect multiple ways** — Bluetooth LE for people near you, Wi-Fi at home, LoRa radio via [RNode](https://github.com/markqvist/RNode_Firmware) for distance, or TCP to reach any Reticulum node worldwide
- **Stay private** — End-to-end encryption with no accounts, no tracking, and no central servers
- **Share location** — Share your position securely with chosen contacts, viewable on a dedicated map
- **Download maps for offline use** — Vector and raster maps in MBTiles format
- **Browse NomadNetwork** — Access nomadnet pages over Reticulum
- **Build your network** — Relay traffic for others and extend the mesh
- **Keep your identity** — Generate your messaging identity on-device
- **Manage multiple identities** — Swap between identities freely
- **Export and import identities** — Back up your keys or migrate devices. Imports from other Reticulum clients such as [Sideband](https://github.com/markqvist/Sideband)
- **Share your identity via QR code** — Built-in scanner and generator
- **Custom color themes** — Restyle it however you like

## Getting Started

Download the latest release from [Releases](https://github.com/c0d3craft3r13/zamolxis/releases) and install it on your Android device. See [SECURITY.md](./SECURITY.md) for APK verification instructions — verify before you install.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/c0d3craft3r13/zamolxis"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="60" alt="Get it on Obtainium"></a>

## About Reticulum

[Reticulum](https://github.com/markqvist/Reticulum) is a networking stack that lets devices communicate directly with each other, forming resilient mesh networks. It is optimized for low bandwidth, high latency links and can run over nearly any medium. Zamolxis uses [LXMF](https://github.com/markqvist/LXMF) (Lightweight Extensible Message Format) to carry messages across the network, and a native Android implementation of [ble-reticulum](https://github.com/torlando-tech/ble-reticulum) for messaging over BLE with other Android and Linux devices.

Want to learn more? Visit [Reticulum's documentation](https://reticulum.network/).

## Why "Zamolxis"

Zamolxis was the god of the Dacians, associated with immortality. Herodotus tells that he withdrew into an underground chamber for three years while his people mourned him as dead — and then returned. A fitting name for a network that goes quiet and comes back.

## Security

Zamolxis carries private messages and identity keys. Report vulnerabilities privately through [GitHub Security Advisories](https://github.com/c0d3craft3r13/zamolxis/security/advisories/new), never as a public issue. See [SECURITY.md](./SECURITY.md) for the threat model, including what is *not* yet covered.

## License

Zamolxis is released under the Mozilla Public License 2.0 — see [LICENSE.md](./LICENSE.md).

It is a fork of [Zamolxis](https://github.com/torlando-tech/columba) by the Zamolxis Contributors, used under the same license. The upstream project is not affiliated with Zamolxis and does not endorse it.
