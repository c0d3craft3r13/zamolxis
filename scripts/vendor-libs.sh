#!/usr/bin/env bash
#
# Verify libs/ — the local Maven repository for artifacts that are not published to
# Maven Central.
#
# History: this script used to fetch the Reticulum stack (reticulum-kt / LXMF-kt /
# LXST-kt) from JitPack into libs/. That stack is no longer a binary dependency at
# all — its sources are checked in under vendor/ and built as Gradle modules, and
# JitPack has been removed from settings.gradle.kts. See vendor/PROVENANCE.md.
#
# What is left here is usb-serial-for-android, whose author publishes through
# JitPack only. It is pinned by sha256 in gradle/verification-metadata.xml, so this
# script no longer asks whether a file is *present* — presence was never the
# interesting question — but whether the bytes in libs/ are the bytes that were
# verified. There is no --fetch mode any more: re-fetching from a repository this
# project does not control is the thing being avoided. To change a version, let
# Gradle resolve it once with the repository temporarily restored, check the new
# checksum into verification-metadata.xml, and copy the artifact in deliberately.
#
# Usage:
#   scripts/vendor-libs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/libs"
METADATA="$ROOT/gradle/verification-metadata.xml"

status=0
found=0

while IFS= read -r file; do
    found=$((found + 1))
    name="$(basename "$file")"
    actual="$(sha256sum "$file" | cut -d' ' -f1)"

    # The pinned sha256 sits on the line after `<artifact name="...">`.
    expected="$(grep -A2 "<artifact name=\"$name\">" "$METADATA" |
        sed -n 's/.*<sha256 value="\([0-9a-f]*\)".*/\1/p' | head -n 1)"

    if [ -z "$expected" ]; then
        echo "!! $name is in libs/ but nothing pins it in gradle/verification-metadata.xml" >&2
        status=1
    elif [ "$actual" != "$expected" ]; then
        echo "!! $name does not match its pinned checksum" >&2
        echo "     expected $expected" >&2
        echo "     actual   $actual" >&2
        status=1
    else
        echo "ok  ${file#"$ROOT"/}"
    fi
done < <(find "$LIBS" -type f \( -name '*.jar' -o -name '*.aar' -o -name '*.module' \) | sort)

if [ "$found" = "0" ]; then
    echo "!! libs/ holds no artifacts — the build will not resolve usb-serial-for-android," >&2
    echo "   and with JitPack gone there is no fallback." >&2
    exit 1
fi

if [ "$status" != "0" ]; then
    echo >&2
    echo "libs/ does not match gradle/verification-metadata.xml. Do not 'fix' this by" >&2
    echo "regenerating the metadata: work out where the bytes came from first." >&2
    exit 1
fi

echo "libs/ verified: $found artifact(s) match their pinned checksums"
