# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-28)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** `zamolxis/rebrand-and-post-quantum` — rebrand, post-quantum sealing,
encryption at rest, and repairing quality gates that had stopped checking anything.

## Current Position

Branch: `zamolxis/rebrand-and-post-quantum` (v2.2.1-beta + 31 commits)
Last activity: 2026-08-23

The phase numbering below belongs to the v0.7.4-beta milestone and has not advanced
since January. Work did not stop — it moved off that plan onto this branch without the
plan being updated, which is why this file said "Phase 5 of 6, Memory Optimization" for
seven months. Phase 6 (Native Stability Verification) was never started, and the
COLUMBA-E memory growth it was meant to close is still open.

## Milestone Summary

**v0.7.4-beta Bug Fixes — stalled, last touched 2026-01-29**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 3 | ANR Elimination | ANR-01 | **Complete** |
| 4 | Relay Loop Resolution | RELAY-03 | **Complete** |
| 5 | Memory Optimization | MEM-01 | **Stalled** (1/3 plans, no work since January) |
| 6 | Native Stability Verification | NATIVE-01 | Not started |

**Since then, off-plan, on this branch**

| Stream | What landed | Commits |
|--------|-------------|---------|
| Rebrand + vendoring | Renamed to Zamolxis; Reticulum stack vendored into `libs/` so a fresh clone builds without JitPack | `e92a01d`, `76c1d07`, `4f9b2a3` |
| Post-quantum sealing | Hybrid X25519 + ML-KEM-768 over message text and attachments, fail-closed; scope written into SECURITY.md | `40c8d7c`, `62d92b1`, `2418b8b` |
| App lock | PIN over the app, microphone permission asked up front | `90bb42d`, `a6adec9` |
| Encryption at rest | Message database on SQLCipher, device-bound Keystore passphrase, in-place conversion of existing installs, excluded from Android backup | `52c18ae`, `cf2cb2d`, `8e83e64`, `8543d6c` |
| Quality gates that were not running | BLE logging-tag detekt rule matched zero files; ktlint linted no Kotlin in any Android module; two detekt baselines were read by nothing | `c83cca7`, `268c2f0`, `864f1e5` |
| Correctness | Python polled from inside the stamp-search loop (GIL contention); attachment pick read unbounded into memory and OOM'd | `574f388`, `06567dd` |

## Performance Metrics

**Not tracked since 2026-01-29.** The figures that were here (3 plans, ~32 min average)
describe January's phase work only. Nothing on this branch went through the plan/execute
loop that produced them, so there is no honest way to extend the table — treat plan
velocity as unknown rather than as the stale numbers.

## Accumulated Context

### Sentry Analysis (2026-01-29)

**COLUMBA-3 (Relay Loop):**
- Still happening on v0.7.3-beta despite fix
- Stacktrace: `PropagationNodeManager.recordSelection` line 840
- Seer suggests: Use `SharingStarted.WhileSubscribed` instead of eager StateFlow
- **FIXED in Phase 4** - Changed to WhileSubscribed(5000L), pending post-deployment verification

**COLUMBA-M (ANR):**
- `DebugViewModel.<init>` -> `loadIdentityData` -> `getOrCreateDestination`
- Makes synchronous IPC call to service during ViewModel init on main thread
- **FIXED in Phase 3**

**COLUMBA-E (OOM):**
- Known ~1.4 MB/min memory growth in Python/Reticulum layer
- **INSTRUMENTED in Phase 5** - Memory profiling infrastructure added (tracemalloc + native heap monitoring)
- Investigation still pending as of 2026-08-23. The instrumentation is in place; nobody
  has read its output. Unrelated to the separate attachment OOM fixed in `06567dd`,
  which was an unbounded read at file-pick time, not the Python-side growth.

### Decisions

| Decision | Rationale | Phase |
|----------|-----------|-------|
| WhileSubscribed(5000L) for relay StateFlows | Standard Android timeout - survives screen rotation without restarting upstream | 04-01 |
| Keep state machine, debounce, loop detection | Defense-in-depth - WhileSubscribed addresses root cause, guards handle edge cases | 04-01 |
| Use tracemalloc instead of memory_profiler | tracemalloc is stdlib (no dependencies), lower overhead, sufficient for leak detection | 05-01 |
| 5-minute snapshot interval | Balances detection speed with overhead; leak grows at ~1.4 MB/min so 5min = ~7MB delta | 05-01 |
| Debug-only via BuildConfig flag | Zero overhead in release builds; profiling instrumentation stays in codebase for future debugging | 05-01 |

### Roadmap Evolution

v0.7.3 milestone complete. Next milestone (v0.7.4) will address:
- #338: Duplicate notifications after service restart
- #342: Location permission dialog regression
- Native memory growth investigation

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

### Patterns Established

- **WhileSubscribed(5000L)**: Standard timeout for Room-backed StateFlows that should stop collecting when UI is not observing
- **Turbine test pattern**: Keep collector active inside test block when testing code that accesses StateFlow.value with WhileSubscribed
- **BuildConfig feature flags**: Clean pattern for debug-only functionality with zero release overhead
- **Synchronized multi-layer monitoring**: Align Python and Android monitoring intervals for easy correlation

### Blockers/Concerns

**Post-deployment verification needed:**
- RELAY-03-C: 48-hour zero "Relay selection loop detected" warnings in Sentry
- Fix is based on Sentry AI (Seer) recommendation + Android best practices

## Session Continuity

Last session: 2026-08-23
Stopped at: supply-chain and release-trust work on `zamolxis/rebrand-and-post-quantum`
Resume file: None

Open, in no particular order:
- Phase 6 (Native Stability Verification) and the COLUMBA-E memory growth behind it.
- ~5,100 ktlint findings sit in `config/ktlint-baseline.xml`, all formatting. The baseline
  matches by line number, so editing a file resurfaces its own entries; the way out is one
  deliberate whole-tree format, which is a 590-file diff and has not been decided.
- Gradle dependency verification (`gradle/verification-metadata.xml`) not yet generated.
- Python dependencies are the weakest link in the supply chain and are not covered by any
  of the above: `cryptography>=42.0.0` and `u-msgpack-python` are unpinned ranges resolved
  at build time (see `rns-backend-py/build.gradle.kts`).
