# Reproducible builds

A release APK is only worth verifying if a third party can rebuild it and get the same
bytes. This page records what currently reproduces, what does not, and why — measured,
not asserted.

**Status as of 2026-08-24**

| Variant | Reproducible | Notes |
|---|---|---|
| `noSentryKotlinBackend` release | **Yes**, byte-identical | All four APKs (3 ABI splits + universal) |
| `noSentryPythonBackend` release | **No** | Two APK entries differ, both from `.pyc` headers, below |

## Rebuilding a release

```bash
git clone https://github.com/torlando-tech/columba.git
cd columba
git checkout <tag>            # e.g. v2.2.1-beta
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleNoSentryKotlinBackendRelease --no-build-cache
sha256sum app/build/outputs/apk/noSentryKotlinBackend/release/*.apk
```

Requirements: JDK 21 or newer (the build pins javac to 21 and downloads it via toolchains
if absent), and the Android SDK. The `pythonBackend` variant additionally needs **Python
3.11 on `PATH`** — Chaquopy 17 accepts no other minor version and the build fails without
it. Nothing else: the Reticulum stack is vendored under `libs/`, so no network is needed
for those artifacts.

A fresh clone has no `local.properties`; point `ANDROID_HOME` at the SDK instead of
creating one, so the SDK path never enters the build inputs.

`--no-build-cache` matters. Without it a second build on the same machine can return
cached outputs and produce identical hashes while having verified nothing.

## Comparing against a release

The checksums published with each release are of the **signed** APKs. A third-party
rebuild produces unsigned ones, so the published SHA-256 cannot be reproduced by anyone
except the key holder — comparing the two directly will always fail, and that is expected,
not a defect.

To compare, ignore the signature: unzip both, drop `META-INF/` and the APK Signing Block,
and compare the remaining entries. `apksigcopier` automates the reverse (copying a
signature onto a rebuild) and is the usual tool for this.

Closing that gap properly means publishing the unsigned APK's hash alongside the signed
one. That has not been done yet.

## How the current status was measured

Two independent builds of the same commit:

- Build A in the working checkout, build B from a separate `git clone` at a different
  filesystem path, both with `--no-build-cache`, both from the same commit.
- Compared with `sha256sum`, and where hashes differed, entry by entry inside the APK,
  down to the ZIP headers and the raw bytes.

`noSentryKotlinBackend` came out byte-identical on all four APKs, including from a
checkout whose Kotlin sources had CRLF line endings — Kotlin compilation is insensitive
to them.

`noSentryPythonBackend` did not, and the differences were chased to their causes rather
than left as "it differs". After fixing the first cause and rebuilding, exactly two of the
2,365 APK entries still differ.

### What the method cannot tell you

- **Same machine, same OS, same JDK, same Gradle dependency cache.** This shows the build
  does not depend on its own path or on stale state. It does not show that a Linux CI
  runner and a developer's Windows box agree.
- **Same day.** The `pythonBackend` variant resolves some Python dependencies at build
  time from unpinned specifications — `cryptography>=42.0.0` and `u-msgpack-python` in
  `rns-backend-py/build.gradle.kts`. This build baked in `cryptography 42.0.8` and
  `u-msgpack-python 2.8.0`. A rebuild in six months will quietly pick different ones, and
  no same-day comparison can detect that. Pinning them is the single highest-value change
  left for reproducibility, and it is a supply-chain fix as much as a build one: an
  unpinned crypto library resolved at build time is a dependency nobody reviewed.
- **Unsigned only.** Signing was not exercised; the release key is not available locally.

## Known differences and their causes

### Fixed: line endings (`.gitattributes`)

Every difference between the two `pythonBackend` builds except the one below traced to
CRLF versus LF. Chaquopy packages `rns-backend-py/src/main/python/**` into
`assets/chaquopy/app.imy` verbatim, and `app/src/main/res/raw/license_mpl2.txt` ships as a
raw resource, so on Windows with `core.autocrlf=true` the checkout decided the bytes in
the APK.

`.gitattributes` now pins those paths to LF. An existing Windows checkout keeps whatever
it has until the files are checked out again:

```bash
git add --renormalize .
```

### Open: `.pyc` headers embed the build time

This is the only cause left. 240 of the 377 entries inside
`assets/chaquopy/requirements-common.imy` differ between builds. The compiled bytecode is
byte-identical in every single one: only the 16-byte header differs, and within it the
source modification time — the two builds recorded 23:14:55 and 23:31:48, the times pip
installed the packages.

They are timestamp-based `.pyc` files (PEP 552 flags = 0). Two ways out, neither taken
here because both are decisions rather than fixes:

- **`pyc { pip = false }`** in `rns-backend-py/build.gradle.kts`. One line, and the
  property exists — it ships pip-installed packages as source, the way app sources are
  already shipped (`pyc { src = false }`, for its own reasons). The cost is paid at
  runtime: Python compiles those modules on the device at first import, and the assets
  grow. Whether a slower first start is worth a reproducible build is a product call.
- **`SOURCE_DATE_EPOCH`**, which makes Python emit hash-based `.pyc`. Harder than it
  looks: the variable must reach the Gradle *daemon*'s environment, not just the shell
  that launched the build. Untested, and so not claimed as a fix.

`assets/chaquopy/build.json` is the second differing entry, but only because it records
hashes of the asset above; it will agree once that one does.

## Dependency verification

`gradle/verification-metadata.xml` records a SHA-256 for every artifact the build
resolves, and Gradle refuses to use anything whose hash is not listed. It is active for
every build simply by existing — no flag, no opt-in.

SHA-256 only, no PGP. Signature verification would make every build depend on a keyserver
being reachable and on upstreams actually signing their releases, which many Android
artifacts do not. Be clear about what this does and does not buy: it pins exactly the
bytes this project was built and tested against, so a repository serving something else
later is caught. It says nothing about whether those bytes were trustworthy to begin with.

To add artifacts after a dependency change:

```bash
./gradlew --refresh-dependencies --write-verification-metadata sha256 <the failing task>
```

`--refresh-dependencies` is not optional, and this is the part that costs an afternoon if
you skip it. Without it Gradle reuses resolutions it already has and never revisits parts
of the graph, so the metadata comes out missing exactly the entries the next clean build
demands. A BOM that only appears during a forced re-resolution was missing three times in
a row here for that reason.

Also note the metadata only covers configurations that were actually resolved in the run
that wrote it. A task path nobody exercised — a variant, a test source set, an
instrumented build — will fail on its first CI run with "artifacts failed verification".
That is the expected way to discover a gap; add the entries with the command above.

## What would make this verifiable by others

In rough order of value:

1. Pin the Python dependencies to exact versions.
2. Publish the unsigned APK hash in the release notes next to the signed one.
3. Settle the `.pyc` question — `pyc { pip = false }`, `SOURCE_DATE_EPOCH`, or accepting
   the difference and documenting the two entries to exclude when comparing.
4. Rebuild each release on a second machine with a different OS before publishing.
