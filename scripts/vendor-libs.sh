#!/usr/bin/env bash
#
# Refresh the vendored Reticulum stack in libs/.
#
# settings.gradle.kts points its first repository at libs/, a plain Maven layout
# holding reticulum-kt, LXMF-kt and LXST-kt. They are vendored so a clone builds
# without reaching JitPack, which builds those artifacts on demand from
# repositories this project does not control.
#
# This script exists because settings.gradle.kts referenced it and it did not:
# whoever bumped a version had no documented way to regenerate the directory, and
# the previous attempt committed only the .pom/.module metadata while .gitignore
# swallowed the actual .jar/.aar files — so the "no network" build quietly went
# back to JitPack.
#
# Usage:
#   scripts/vendor-libs.sh                 # verify what is vendored is complete
#   scripts/vendor-libs.sh --fetch         # download the pinned versions again
#
# Versions come from gradle/libs.versions.toml (reticulumKt / lxmfKt / lxstKt);
# nothing is hardcoded here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/libs"
GROUP_PATH="com/github/torlando-tech"
BASE_URL="https://jitpack.io/$GROUP_PATH"

# module-relative-path : artifact-basename : packaging
# Kept explicit rather than derived: an artifact silently changing packaging
# (jar to aar) is exactly the kind of drift that turns a vendored repository into
# a repository that resolves from the network again.
read -r -d '' ARTIFACTS <<'EOF' || true
LXMF-kt:LXMF-kt:jar
LXST-kt:LXST-kt:aar
reticulum-kt/rns-core:rns-core:jar
reticulum-kt/rns-interfaces:rns-interfaces:jar
reticulum-kt/rns-android:rns-android:aar
EOF

version_of() {
    # Reads a version from gradle/libs.versions.toml by key, e.g. reticulum = "v0.0.22"
    local key="$1"
    sed -n "s/^[[:space:]]*$key[[:space:]]*=[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" \
        "$ROOT/gradle/libs.versions.toml" | head -n 1
}

RETICULUM_VERSION="$(version_of reticulumKt)"
LXMF_VERSION="$(version_of lxmfKt)"
LXST_VERSION="$(version_of lxstKt)"

version_for() {
    case "$1" in
        LXMF-kt) echo "$LXMF_VERSION" ;;
        LXST-kt) echo "$LXST_VERSION" ;;
        *) echo "$RETICULUM_VERSION" ;;
    esac
}

FETCH=0
[ "${1:-}" = "--fetch" ] && FETCH=1

missing=0
while IFS=: read -r module artifact packaging; do
    [ -z "${module:-}" ] && continue
    version="$(version_for "$artifact")"
    if [ -z "$version" ]; then
        echo "!! no version found in gradle/libs.versions.toml for $artifact" >&2
        missing=1
        continue
    fi
    dir="$LIBS/$GROUP_PATH/$module/$version"
    mkdir -p "$dir"
    for ext in "$packaging" pom module; do
        file="$dir/$artifact-$version.$ext"
        if [ "$FETCH" = "1" ]; then
            url="$BASE_URL/$module/$version/$artifact-$version.$ext"
            echo "fetching $url"
            curl -fsSL "$url" -o "$file"
        elif [ ! -s "$file" ]; then
            echo "!! missing $file" >&2
            missing=1
        fi
    done
done <<< "$ARTIFACTS"

if [ "$missing" != "0" ]; then
    cat >&2 <<'MSG'

The vendored repository is incomplete: the build will fall through to JitPack
instead of resolving locally. Run `scripts/vendor-libs.sh --fetch` and commit the
result — including the .jar/.aar files, which .gitignore explicitly un-ignores
under libs/.
MSG
    exit 1
fi

echo "libs/ is complete for reticulum=$RETICULUM_VERSION lxmf=$LXMF_VERSION lxst=$LXST_VERSION"
