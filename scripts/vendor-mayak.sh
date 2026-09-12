#!/usr/bin/env bash
#
# Copy a committed snapshot of the Mayak protocol into vendor/python/mayak.
#
# Mayak is this project's own messaging layer, but it lives in its own
# repository (ProjectBeta) and is written, tested and reviewed there. The app
# takes it the way it takes Reticulum: a snapshot checked in under vendor/,
# pip-installed by Chaquopy from that directory, with the commit it came from
# written down. The build never reaches another checkout, and a change to the
# protocol reaches the app only as a reviewable diff here.
#
# Only committed state is copied — the tree at a named commit, via `git archive`
# — so an uncommitted edit in the other checkout cannot ship by accident.
#
# Usage:
#   scripts/vendor-mayak.sh [path-to-ProjectBeta] [commit]
#
# Defaults: ../ProjectBeta and its HEAD. Afterwards, update the commit in
# vendor/PROVENANCE.md and run MayakOnDeviceInstrumentedTest on a device.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="${1:-$ROOT/../ProjectBeta}"
TARGET="$ROOT/vendor/python/mayak"

if ! git -C "$SOURCE" rev-parse --git-dir >/dev/null 2>&1; then
    echo "not a git checkout: $SOURCE" >&2
    exit 2
fi

COMMIT="$(git -C "$SOURCE" rev-parse "${2:-HEAD}")"

rm -rf "$TARGET"
mkdir -p "$TARGET"

# The package and its packaging, nothing else: no tests, no tools, no README.
# Those are for working on the protocol, and working on it happens over there.
git -C "$SOURCE" archive "$COMMIT" mayak pyproject.toml | tar -x -C "$TARGET"

printf '%s\n' "$COMMIT" > "$TARGET/VENDORED_COMMIT"

echo "vendored mayak at $COMMIT"
