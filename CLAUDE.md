# Zamolxis — Project Instructions for Claude Code

Android messenger over the Reticulum mesh: LXMF messaging, LXST voice calls,
BLE/USB/TCP interfaces, offline maps, NomadNet. Kotlin + Gradle. There is no npm
in this repo.

## Modules

| Module | Role |
|---|---|
| `:app` | UI (Compose), ViewModels, repositories. Runs in the UI process. |
| `:data` | Room database, DAOs, shared data models. |
| `:rns-api` | Backend seam: `RnsBackend` and its sub-interfaces, value types, capabilities. |
| `:rns-ipc` | AIDL surface between the UI process and the `:reticulum` service process. |
| `:rns-host` | The foreground service host: BLE/USB/RNode bridges, persistence gates. |
| `:rns-backend-kt` | Native Kotlin RNS stack (`reticulum-kt` / `lxmf-kt` / `lxst-kt`). |
| `:rns-backend-py` | Chaquopy/Python RNS stack (upstream RNS + LXMF wheels). |
| `:crypto-pq` | Hybrid post-quantum crypto (ML-KEM-768 + X25519, HKDF-SHA256, AES-256-GCM). |
| `:micron` | Micron markup parser/renderer for NomadNet. |
| `:rns-stats` | Interface statistics. |
| `:detekt-rules` | Project-specific detekt rules (see below). |

Two processes: UI, and the `:reticulum` foreground service. **Python/Chaquopy must
never load in the UI process.** Backend choice is a Gradle product flavor
(`kotlinBackend` / `pythonBackend`) with flavor-specific source sets.

## Build & test

```bash
./gradlew :app:assembleNoSentryKotlinBackendDebug
./gradlew :app:testNoSentryKotlinBackendDebugUnitTest
./gradlew detekt ktlintCheck cpdCheck
```

Anything touching `:rns-backend-py` needs **Python 3.11 on PATH** — Chaquopy 17
accepts no other minor version, and `installDebugPythonRequirements` fails the
build without it.

Run the tests for the modules you touched before claiming a change works.

## Quality gates (what CI actually enforces)

- **detekt** — `maxIssues: 0`. Pre-existing debt is frozen in per-module
  `detekt-baseline*.xml`. New code must pass clean; do not regenerate a baseline to
  silence a new finding.
- **ktlint** and **CPD** — advisory (`ignoreFailures = true`). They do not block.
- **Android Lint** — progressive: `checkOnly` enforces `NewApi`; `:app` additionally
  raises `HardcodedText` to an error. Widen deliberately, one check at a time.
- **`scripts/check-hardcoded-strings.sh`** — the real lock on hardcoded UI strings,
  because Lint's `HardcodedText` cannot see Compose code. Per-file baseline in
  `scripts/hardcoded-strings-baseline.txt`; it may shrink, never grow.
- **`audit-dispatchers.sh`** — no `runBlocking`, `GlobalScope`, or
  `Dispatchers.Unconfined` in production, and no Python entry point on
  `Dispatchers.Main`. A justified `runBlocking` needs a `// THREADING: allowed — <why>`
  comment on the line.
- **Test suppression ratchet** (in `ci.yml`) — files carrying
  `@Suppress("NoRelaxedMocks")` / `@Suppress("NoVerifyOnlyTests")` are capped at their
  current counts. The numbers may go down; raising them is not an option.

## Custom detekt rules (`:detekt-rules`)

`BleLoggingTag`, `NoRelaxedMocks`, `NoVerifyOnlyTests`, `DiscardedConcurrencyReturn`,
`StateFlowPollingLoop`, `ReflectivelyKeptRequired`, `NoCallCoordinatorGetInstanceOutsideHost`,
`NoRnsFacadeInPythonBackend`.

These rules match on package names. **Never pin one to a single module path.**
`BleLoggingTag` was pinned to a pre-rename package, matched zero files for months,
and stayed green while checking nothing. When you move or rename a package, grep
`detekt-rules/` for the old name and run `./gradlew :detekt-rules:test`.

## Common pitfalls

### Chaquopy: Kotlin/Java lists to Python

A Kotlin/Java `List`/`ArrayList` passed to a Python function via Chaquopy raises
`'ArrayList' object is not iterable`. Convert first:

```kotlin
wrapperManager.withWrapper { wrapper ->
    // Java ArrayList doesn't serialize to Python — convert explicitly
    val pyList = com.chaquo.python.Python.getInstance()
        .builtins.callAttr("list", myKotlinList.toTypedArray())
    wrapper.callAttr("some_python_function", pyList)
}
```

### Chaquopy: calls from hot loops

Every `callAttr` from a Kotlin thread reacquires the GIL and stalls the RNS reactor
running on it. Never poll Python from a tight or parallel loop — throttle the
crossing (see `throttledLatchingPredicate` in `PythonRnsRuntime.kt`).

## Working rules

- Do what was asked; nothing more.
- Read a file before editing it. Prefer editing an existing file to creating one.
- No new `*.md` unless asked. Nothing new in the repo root: docs go in `docs/`,
  scripts in `scripts/`, planning notes in `.planning/`.
- Never commit secrets, credentials, or `.env` files.
- Keep new files under 500 lines. Several existing files run past 2000 — don't take
  them as the standard.
