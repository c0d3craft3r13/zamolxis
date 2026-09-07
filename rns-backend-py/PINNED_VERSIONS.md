# Pinned Python wheel versions — `:rns-backend-py`

The Python flavor ships **upstream RNS/LXMF as the protocol stack**. These are
the only dependencies that carry protocol-correctness weight, so they are
pinned and bumps require a deliberate PR.

**The three Reticulum packages are no longer fetched at all.** They used to be
installed as `git+https://github.com/torlando-tech/...@<sha>`. The SHA pins were
sound — a git commit hash cannot be moved — but every build reached a repository
this project does not control, pip installs are not covered by
`gradle/verification-metadata.xml`, and nobody could review an upstream change
without cloning it themselves. Their sources are now checked into
`vendor/python/` and pip builds them from there. The SHAs below record which
upstream revision each tree was taken from; `vendor/PROVENANCE.md` carries the
audit and the diff against upstream `markqvist`.

The installed payload was verified byte-for-byte against what the git installs
produced: all 116 `.py` files identical, same `.dist-info` versions.

**For anything still fetched, pin to a commit SHA, not a branch tip.** Branch
tips move; a build done today and a build done next month must produce the same
protocol behaviour. This mirrors `release/v0.10.x`'s reproducibility discipline
(its commit `63c4a2b` did the same).

The pip block lives in `build.gradle.kts`'s
`chaquopy { defaultConfig { pip { ... } } }` — there is no `requirements.txt`
(per the dual-build plan, pip pinning moved into the Gradle build script).

## Current pins

| Package | Source | Taken from | Notes |
|---|---|---|---|
| `rns` (Reticulum) | `vendor/python/Reticulum` (vendored source) | [torlando-tech/Reticulum](https://github.com/torlando-tech/Reticulum) **`5b3a6ee4f25e2925cf84d4a2b108e6a708fbd395`** | RNS 1.4.2 with socket cleanup, narrow PHY-stats RPC backoff, ratchet file-handle fixes, and deterministic AutoInterface listener/peer teardown. The former known-destinations recombine migration is obsolete in 1.4.2: recombination is ignored and the retained load path already migrates legacy four-field entries. |
| `lxmf` (LXMF) | `vendor/python/LXMF` (vendored source) | [torlando-tech/LXMF](https://github.com/torlando-tech/LXMF) **`8912186e48b482a76bf04e2ac4b6c8940991aecc`** | LXMF 1.1.0 with validated external native stamping, cooperative cancellation and stale-result rejection, plus `receiving_interface` and `receiving_hops` on opportunistic delivery. |
| `ble-reticulum` | `vendor/python/ble-reticulum` (vendored source) | [torlando-tech/ble-reticulum](https://github.com/torlando-tech/ble-reticulum) **`07d941304c9a1dc3a8e58087b3b974ff3d229e56`** | Provides `BLEInterface` + `bluetooth_driver` that the bundled `ble_modules/` adapters subclass. SHA is the tip of `main` as of 2026-05-14; builds as `ble-reticulum-0.2.2`. |
| `cryptography` | PyPI | `>=42.0.0` | Range, not pinned — Chaquopy resolves a native wheel for the target ABI. Acceptable: it's a well-tested transitive dep, not a protocol-correctness surface. Currently resolves to 42.0.8. This is the last dependency in the Python flavor whose exact bytes are not fixed by this repository. |
| `u-msgpack-python` | PyPI | unpinned | Sideband-compatible telemetry + LXST signalling wire format. Pure-Python, stable API. |

## Decisions made during the Phase B restore

### `patches/` tree — intentionally NOT restored

`release/v0.10.x`'s `python/patches/RNS/{Destination.py,__init__.py}` carried
context-manager fixes for RNS file-handle leaks (ratchet I/O + the `log()`
function). They are **not restored** here because:

1. The pinned RNS fork commit `5b3a6ee4` **already includes** the ratchet I/O
   context-manager fixes, while upstream RNS 1.4.2 includes the equivalent
   `log()` context-manager fix. The plan's instruction is to skip the `patches/`
   tree when the pinned commit already has the fixes — it does.
2. The patch *deployment* mechanism — `reticulum_wrapper.py::_deploy_rns_patches()`,
   which copied the patched files over the pip-installed RNS at runtime — is
   **not** being restored (the slim-Python design deletes `reticulum_wrapper.py`).
   Restoring `patches/` without a deployer would be dead weight.

If a future RNS pin regresses on those fixes, the correct response is to bump
the pin to a fork commit that has them — **not** to re-introduce a runtime
file-patcher.

### `TorClientInterface.py` — not restored (Tor out of scope)

`release/v0.10.x` shipped `python/TorClientInterface.py` (a `TCPClientInterface`
subclass routing over a local SOCKS5/Tor proxy). It is small and self-contained
(`import RNS` + stdlib only), but Tor support is not in scope for the first
Python-flavor cut. It can be re-added as a single file later if wanted —
restore with `git show 66d983f^:python/TorClientInterface.py`.

## Restored interface adapters — known gap inherited from `66d983f^`

`ble_modules/android_ble_interface.py` does
`from drivers.android_ble_driver import AndroidBLEDriver`, but `66d983f^`'s
Python tree has only `drivers/__init__.py` (no `drivers/android_ble_driver.py`).
At v0.10.x runtime the BLE interface files were *deployed* into the RNS
interfaces directory (`~/.reticulum/interfaces/`) alongside `BLEInterface.py` /
`bluetooth_driver.py` from the `ble-reticulum` wheel, and `drivers/` was
populated there. That deployment step lived in the deleted `reticulum_wrapper.py`.

**This is restored verbatim and left as-is.** Wiring BLE-on-Python so
`Transport.find_interfaces()` discovers `AndroidBLEInterface` is an on-device
integration task (BLE is not on the Phase B verification checklist). When that
work happens, either `PythonRnsRuntime` grows an interface-deployment step or
the `from drivers.android_ble_driver import` line is repointed at
`ble_modules.android_ble_driver`.
