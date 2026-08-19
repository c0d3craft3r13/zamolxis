#!/usr/bin/env bash
# Hardcoded UI string lock for Zamolxis (Compose).
#
# Android Lint's HardcodedText only inspects XML layouts — it does NOT see
# Compose Kotlin code, which is where Zamolxis's UI lives. This script is the
# real lock: it fails the build when hardcoded user-facing strings appear in
# NEW files or INCREASE in files already grandfathered by the baseline.
#
# Baseline: scripts/hardcoded-strings-baseline.txt (per-file counts, sorted).
# Regenerate intentionally after an extraction batch shrinks the numbers:
#   scripts/check-hardcoded-strings.sh --update-baseline
#
# Tracked patterns (user-visible text):
#   Text("...")                 — Compose text
#   contentDescription = "..."  — a11y labels
#   Toast.makeText(..., "...")  — toasts
#   title = "..." / label = "..." — common named args

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
BASELINE="$ROOT/scripts/hardcoded-strings-baseline.txt"

PATTERN='Text\("|text = "|contentDescription = "|Toast\.makeText\([^\n]*"|title = "|label = "'

current_counts() {
    grep -rE "$PATTERN" "$SRC" --include='*.kt' -l 2>/dev/null | while read -r f; do
        n=$(grep -cE "$PATTERN" "$f" || true)
        rel="${f#"$ROOT"/}"
        echo "$rel $n"
    done | sort
}

if [[ "${1:-}" == "--update-baseline" ]]; then
    current_counts > "$BASELINE"
    echo "Baseline updated: $(wc -l < "$BASELINE") files"
    exit 0
fi

if [[ ! -f "$BASELINE" ]]; then
    echo "ERROR: baseline not found at $BASELINE"
    echo "Run: scripts/check-hardcoded-strings.sh --update-baseline"
    exit 2
fi

fail=0
while IFS=' ' read -r file count; do
    [[ -z "$file" ]] && continue
    old=$(grep -F "$file " "$BASELINE" | awk '{print $2}' || true)
    if [[ -z "$old" ]]; then
        echo "NEW hardcoded strings in $file ($count occurrences) — extract to strings.xml"
        fail=1
    elif (( count > old )); then
        echo "INCREASED hardcoded strings in $file: $old -> $count — extract to strings.xml"
        fail=1
    fi
done < <(current_counts)

if (( fail )); then
    echo
    echo "Hardcoded string lock FAILED. Put user-facing text into"
    echo "app/src/main/res/values/strings.xml (+ values-ru) and use stringResource()."
    exit 1
fi

total=$(awk '{s+=$2} END {print s+0}' "$BASELINE")
echo "OK: no new hardcoded UI strings (grandfathered: $total occurrences, extraction in progress)"
