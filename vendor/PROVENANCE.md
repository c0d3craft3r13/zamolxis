# Vendored sources

This directory holds third-party source that Zamolxis builds itself instead of
downloading. Nothing under `vendor/` was written by this project; everything here is
someone else's code, checked in deliberately so that what ships can be read, diffed
and reviewed.

## Why it is here

The Reticulum/LXMF/LXST stack used to arrive as prebuilt `.jar`/`.aar` files that
JitPack produced on demand from repositories this project does not control. Two things
were wrong with that:

- **The binaries were not reviewable.** `gradle/verification-metadata.xml` pinned their
  checksums, which proves the bytes did not change between builds — it says nothing
  about what those bytes do. Nobody here had read the code inside them.
- **JitPack stayed in the repository list as a fallback.** A moved tag, a compromised
  upstream account, or a version bump would have pulled fresh unreviewed code into a
  build with no diff for anyone to look at.

Building from checked-in source fixes both: an upstream change reaches this app only
when somebody copies it in, and that copy shows up as a reviewable diff.

## What is vendored

| Directory | Upstream | Version | Commit | License |
|---|---|---|---|---|
| `reticulum-kt/rns-core` | [torlando-tech/reticulum-kt](https://github.com/torlando-tech/reticulum-kt) | `v0.0.22` | `bc82bf4776e35eef75f2918a063cedac1d7ae0cc` | MPL-2.0 |
| `reticulum-kt/rns-interfaces` | same | `v0.0.22` | same | MPL-2.0 |
| `reticulum-kt/rns-android` | same | `v0.0.22` | same | MPL-2.0 |
| `lxmf-kt/lxmf-core` | [torlando-tech/LXMF-kt](https://github.com/torlando-tech/LXMF-kt) | `v0.0.14` | `faa86fb44ab9db2b92efa7a194b9a2899235f65c` | MPL-2.0 |
| `lxst-kt/lxst` | [torlando-tech/LXST-kt](https://github.com/torlando-tech/LXST-kt) | `v0.0.8` | `8d7d5488d55e77291fcf6c0f626a1366f0a9e77a` | MPL-2.0 |

All three are Mozilla Public License 2.0. MPL-2.0 permits this use as long as
attribution and the per-file license headers are preserved — they are, unmodified, and
each upstream's `LICENSE` sits beside its sources. MPL is file-level copyleft: a
modification to any file here stays under MPL and should be offered back upstream.

Only `src/main` was taken, plus the ProGuard rules, Room schemas and the native build
script the modules reference. Upstream's own test suites, CLI, example and
conformance-bridge modules were not vendored — this repo does not run them.

The `build.gradle.kts` in each module is **not** upstream's. Upstream published to a
Maven repository and pinned its own dependency versions; here the versions come from
this project's `gradle/libs.versions.toml`, which is what the build already resolved to
anyway — Gradle was picking the app's newer coroutines/BouncyCastle/MessagePack over the
older ones those POMs asked for. Deliberate departures from upstream's build:

- **Java 17, not 21, for `lxst`.** Upstream targeted 21; everything else in this repo is
  17. That was the reason `settings.gradle.kts` provisions a JDK 21 toolchain — Hilt's
  generated Java had to read LXST's bytecode. The constraint no longer binds; the pin was
  left in place because it costs nothing and CI already satisfies it.
- **`lifecycle-service` follows this repo's lifecycle version** (2.10.0) instead of the
  2.7.0 upstream pinned, which had left two halves of AndroidX lifecycle mismatched on
  one classpath.
- **Vendored modules are excluded from detekt and ktlint** (see the `subprojects` block
  in the root `build.gradle.kts`). Style gates written for this project's conventions
  have no authority over code we did not write, and reformatting it would destroy the
  diffability that is the whole point of vendoring.

## Local patches

`rns-core` and `lxmf-core` are byte-identical to their upstream tags. Three files in
`reticulum-kt` differ, and `lxst`'s two prebuilt `.so` files were replaced (see **Native
dependencies** above). The Kotlin changes on top of `v0.0.22`:

- **`reticulum-kt/rns-android/.../ble/BleGattServer.kt`** — startup race. Android accepts
  GATT connections the moment `openGattServer` returns, milliseconds before `addService`.
  A peer with a cached address lands in that window and reaches a server with no identity
  characteristic, so the handshake cannot happen. Adds a `serviceReady` flag and an
  `earlyCentrals` set: an early connection is *held* and released upward once the service
  is registered. (An earlier version dropped it instead, and the peer never reconnected.)
- **`reticulum-kt/rns-interfaces/.../ble/BLEInterface.kt`** — `collectDisconnections`
  cleared the address maps but not `incomingHandshakesInFlight`, so losing a link did not
  cancel its handshake: it sat out the full 30 s and then blacklisted the peer for 60.
  Adds an `incomingHandshakeJobs` map and cancels on `connectionLost`.
- **`reticulum-kt/rns-android/.../nearby/AndroidNearbyDriver.kt` was deleted** — see the
  Play Services note under **Audit** below.

`reticulum-kt/rns-interfaces/src/test/.../BLEInterfaceIncomingHandshakeTest.kt` is the
only test vendored, because it is the one that guards the change above — it fails
without the patch. It was rewritten off kotest matchers onto plain JUnit 5 assertions:
pulling in a whole assertion library for four matchers works against the point of this
directory.

These patches were developed against upstream `main` (18 commits past `v0.0.22`) and
apply cleanly to the tag. `v0.0.22` is what has been tested on real devices, so that is
what is vendored; the other 17 commits were not brought along.

To re-check that claim against a fresh upstream clone:

```bash
git clone https://github.com/torlando-tech/reticulum-kt /tmp/rns && git -C /tmp/rns checkout v0.0.22
for m in rns-core rns-interfaces rns-android; do diff -r -q "/tmp/rns/$m/src/main" "vendor/reticulum-kt/$m/src/main"; done
```

It should report `BLEInterface.kt`, `BleGattServer.kt` and the deleted
`AndroidNearbyDriver.kt`, and nothing else.

## Native dependencies

`lxst-kt/lxst/src/main/jniLibs/*/lib{opus,codec2}.so` were **rebuilt from upstream
sources in this repository** and replace the binaries upstream shipped.

Upstream committed prebuilt `.so` files whose provenance did not match its own
documentation. `lxst/scripts/build_native_deps.sh` describes them as cross-compiled from
xiph/opus `v1.5.2` and drowe67/codec2 `1.2.0`; the DWARF paths inside the binaries said
otherwise:

```
/home/tyler/repos/public/columba/external/codec2_talkie/libopus-android/src/opus/...
/home/tyler/repos/public/columba/external/codec2_talkie/libcodec2-android/src/codec2/...
```

— a personal working tree, built out of a vendored `codec2_talkie` checkout rather than
the upstream sources the script names. Most likely ordinary reuse of a known build
harness, but unverified, and it meant the audio path shipped bytes nobody in this project
could account for.

What is here now was built from:

| Library | Upstream | Tag | Commit |
|---|---|---|---|
| libopus | [xiph/opus](https://github.com/xiph/opus) | `v1.5.2` | `ddbe48383984d56acd9e1ab6a090c54ca6b735a6` |
| libcodec2 | [drowe67/codec2](https://github.com/drowe67/codec2) | `1.2.0` | `06d4c11` |

Cross-compiled with NDK `28.2.13676358` and CMake 3.22.1, `ANDROID_PLATFORM=android-24`,
`ANDROID_STL=c++_shared`, `CMAKE_BUILD_TYPE=Release`, shared libraries, tests and
programs off — the flags `build_native_deps.sh` documents, with the NDK bumped from r21e
to the one this project builds with. Stripped with `llvm-strip --strip-unneeded`, which
is also why no build-host paths are embedded any more.

One Windows-only wrinkle, in case the build is repeated there: codec2 cross-compiles a
host `generate_codebook` through an `ExternalProject`, and its install step copies
`generate_codebook` while MinGW produces `generate_codebook.exe`. Copying the binary to
the extension-less name lets the build continue. Nothing in codec2's sources was patched.

**These are not byte-identical to the binaries they replace, and the symbol table is
deliberately narrower.** The old builds also exported `opus_*24` (24-bit PCM entry points
that are not in opus 1.5.2) and `codec2_*_450` / `codec2_*_450pwb`. Neither the JNI
wrappers in `src/main/cpp` nor `Codec2.kt` reference any of them — the app selects only
modes 3200, 2400, 1600, 1400, 1300, 1200 and 700C, and `Codec2.kt`'s mode table has no
450 entry at all. Every symbol `liblxst_codec2_jni`, `liblxst_opus_jni` and the two Oboe
engines actually import was checked against the rebuilt libraries before the swap.

The extra symbols in the old binaries are themselves evidence for the provenance problem:
`opus_decode24` does not exist in opus 1.5.2, so whatever produced that file was not the
version the script claims.

## Audit

The sources were read before being vendored, looking for what would make depending on
someone else's repository dangerous. Findings, in full:

**Nothing malicious was found.** Specifically:

- **No HTTP client anywhere.** No `java.net.URL`, `HttpURLConnection`, OkHttp, Retrofit,
  `WebView`, or `openConnection` in any of the five modules. There is no code path that
  can reach a web server.
- **No hardcoded remote host.** Every network endpoint is either localhost (the shared
  instance probe on `127.0.0.1:37428`, the I2P SAM bridge on `127.0.0.1:7656`), a
  link-local IPv6 multicast group derived from the group id `"reticulum"` — the upstream
  Reticulum default, matching the Python implementation — or an address the user
  configures. The only IP literal in a string is inside a KDoc example.
- **No dynamic code loading or process execution.** No `DexClassLoader`,
  `PathClassLoader`, `ProcessBuilder`, `Runtime.exec`, or script engine. `Runtime` is
  touched only for heap statistics. `System.loadLibrary` appears six times, all of them
  the expected LXST native libraries.
- **Reflection is used in six places, all benign**: `Class.forName("android.os.Build")`
  twice for platform detection, `jdk.net.ExtendedSocketOptions` for `SO_REUSEPORT`,
  `android.util.Log` as a logging bridge from a pure-JVM module, and two
  `getDeclaredField` calls into the library's own `Reticulum` class to set a
  private-setter property.
- **No obfuscated or encoded payloads.** One `Base64.getDecoder()` call, in the I2P SAM
  client, decoding that protocol's own destination format.
- **No filesystem access outside the app's own directories.** No `getExternalStorage`,
  `MediaStore`, `/sdcard`, `/data/data` or `/proc` reference in any Kotlin source. (The
  string `/proc/cpuinfo` does appear inside the prebuilt `libopus.so` — that is Opus's
  standard runtime detection of ARM NEON support.)
- **Cryptography is faithful and conservative.** `SecureRandom` for every
  security-relevant byte, with no `setSeed` anywhere. X25519 clamping per RFC 7748. The
  token format is encrypt-then-MAC with a fresh random IV per message and a
  constant-time HMAC comparison. Nothing implements a primitive by hand; it all composes
  BouncyCastle. `Math.random()` is used once, for pathfinder retransmit jitter, where it
  is not security-relevant.
- **No secrets in logs.** Only identity *hashes* — public identifiers — are logged.
- **2883 lines of C/C++** in LXST contain zero calls to sockets, files, `getenv`, or
  process APIs. It is audio DSP, ring buffers and JNI glue.
- **Library manifests declare no exported components.** `rns-android` requests only
  permissions a mesh stack needs, and its `BootReceiver` ships `android:enabled="false"`.
  It deliberately does *not* declare `ACCESS_FINE_LOCATION`, with a comment explaining
  that capping it in a library would strip precise location from the consuming app.
  `lxst` declares `RECORD_AUDIO` and nothing else.
- **No Gradle script does anything at build time** beyond declaring dependencies — no
  downloads, no `exec`, no `doLast` hooks.

Things worth knowing that are not attacks:

- **`rns-android` used to pull in Google Play Services; it no longer does.** Upstream
  shipped `nearby/AndroidNearbyDriver.kt`, a Nearby Connections implementation of
  rns-interfaces' `NearbyDriver`, which dragged `play-services-nearby` and
  `coroutines-play-services` into the process that runs the mesh stack. Nothing in this
  app ever constructed it. The file was deleted along with those two dependencies and the
  three WiFi permissions its manifest declared for it (`ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`) — the app declares all three itself for
  `LocalHotspotManager`, so the merged manifest is unchanged; the library just stopped
  being a second, invisible source of them. The GMS-free `NearbyDriver` / `NearbyInterface`
  abstractions stay in rns-interfaces, so restoring Nearby means supplying a driver.
  (The app independently uses `play-services-location`, so this is not the only GMS edge
  in the build — but it is no longer one the vendored stack creates.)
- **The vendored code uses `GlobalScope` and `runBlocking`** in about ten places, which
  this project's own `audit-dispatchers.sh` forbids. `vendor/` is excluded from that
  audit rather than rewritten, for diffability. The exclusion is spelled out in the
  script even though its current `find` depth already misses this directory.
- **`rns-core` ships public `*ForTest` hooks in production code**, including
  `Channel.outboundMessageTapForTest`, a settable tap on outbound messages. It is null
  unless in-process code sets it, so it is not a remote backdoor — but it is a wide
  surface for a published library.
- **`LXMRouter.kt` uses a raw `println`** (not the logger) when decrypting a propagated
  message, reporting the payload size and whether a private key is present. No key
  material leaks, but it writes to stdout/logcat unconditionally.

## Re-syncing with upstream

There is no automation, on purpose — the review is the point.

1. Clone the upstream repository and check out the new tag.
2. `diff -r` its `src/main` against the directory here and **read the diff**. That is the
   step this whole arrangement exists to make possible.
3. Copy the sources over, keeping each module's `build.gradle.kts` (they are ours).
4. Re-apply the local patches above. Do not rely on a stored patch file — recover them
   from this repository instead, which cannot go stale:
   `git diff <upstream-tag-currently-vendored> HEAD -- vendor/reticulum-kt/` after
   checking out the commit that introduced this directory, or simply diff the two files
   named above against the old upstream tag before overwriting them.
5. Update the version, commit and table in this file.
6. Run `./gradlew :vendor:reticulum-kt:rns-interfaces:test` and build both flavors.

Do **not** re-add a JitPack repository or a version-catalog coordinate for these
libraries to make an upgrade quicker. That is the door this directory closed.
