#!/bin/bash

# audit-dispatchers.sh
# Threading Architecture - Dispatcher Usage Audit Script (CI-Optimized)
#
# This simplified version focuses on critical violations only for fast CI checks.
# For comprehensive analysis, see audit-dispatchers-full.sh

set -euo pipefail

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
VIOLATIONS=0
WARNINGS=0
INFO=0

# Output file
REPORT_FILE="dispatcher-audit-report.txt"
echo "Dispatcher Audit Report - $(date)" > "$REPORT_FILE"
echo "========================================" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

# Helper functions
violation() {
    echo -e "${RED}❌ VIOLATION:${NC} $1"
    echo "❌ VIOLATION: $1" >> "$REPORT_FILE"
    VIOLATIONS=$((VIOLATIONS + 1))
}

warning() {
    echo -e "${YELLOW}⚠️  WARNING:${NC} $1"
    echo "⚠️  WARNING: $1" >> "$REPORT_FILE"
    WARNINGS=$((WARNINGS + 1))
}

info() {
    echo -e "${BLUE}ℹ️  INFO:${NC} $1"
    echo "ℹ️  INFO: $1" >> "$REPORT_FILE"
    INFO=$((INFO + 1))
}

success() {
    echo -e "${GREEN}✅ PASS:${NC} $1"
    echo "✅ PASS: $1" >> "$REPORT_FILE"
}

section() {
    echo ""
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo -e "${GREEN}$1${NC}"
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo "" >> "$REPORT_FILE"
    echo "═══════════════════════════════════════" >> "$REPORT_FILE"
    echo "$1" >> "$REPORT_FILE"
    echo "═══════════════════════════════════════" >> "$REPORT_FILE"
}

# Find source directories.
#
# RETICULUM_SRC used to be hardcoded to `reticulum/src/main/java`. That module was
# split into :rns-api / :rns-ipc / :rns-host / :rns-backend-kt / :rns-backend-py, so
# the path stopped existing and every `find` below silently scanned nothing there —
# the audit went blind to the entire RNS layer while still reporting PASS. Discover
# the dirs instead of naming them, so the next rename can't repeat that.
#
# Covers main + flavor source sets (kotlinBackend/pythonBackend/sentry/noSentry) and
# both java/ and kotlin/ roots; test source sets are excluded here and again by the
# per-check filename filters.
APP_SRC="app/src/main/java"
DATA_SRC="data/src/main/java"
# `find`, not `ls` + glob: under `set -euo pipefail` a glob that matches nothing
# makes `ls` exit non-zero and kills the whole script at the assignment.
RETICULUM_SRC=$(find . -mindepth 4 -maxdepth 4 -type d \( -name java -o -name kotlin \) -path "*/src/*" |
    sed 's|^\./||' |
    grep -v -E "^(app|data)/src/main/(java|kotlin)$" |
    grep -v -E "/src/(test|androidTest)[^/]*/" |
    sort | tr '\n' ' ' || true)

# Drop `file:line:` hits that aren't code: imports, `//` comments, and — the case
# the old filters missed — `*` KDoc continuation lines. A doc comment explaining
# the runBlocking convention was itself reported as a violation of it.
strip_noncode() {
    grep -v -E "^[^:]*:[0-9]*:[[:space:]]*(import |//|\*|/\*)"
}

section "1. Checking for runBlocking in Production Code"

# Check for runBlocking (should be 0 in production code after Phase 1)
# Allow exceptions marked with "// THREADING: allowed" inline comment
# Ignore import statements and pure comment lines
RUNBLOCKING_MATCHES=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "runBlocking" 2>/dev/null | \
    strip_noncode | \
    grep -v "THREADING: allowed" || true)

if [ -z "$RUNBLOCKING_MATCHES" ]; then
    success "No runBlocking found in production code (or all instances are allowed)"
else
    while IFS= read -r line; do
        violation "runBlocking found: $line"
    done <<< "$RUNBLOCKING_MATCHES"
fi

section "2. Checking for Forbidden Patterns"

# Check for GlobalScope (should never be used)
GLOBALSCOPE_MATCHES=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "GlobalScope" 2>/dev/null | \
    strip_noncode || true)

if [ -z "$GLOBALSCOPE_MATCHES" ]; then
    success "No GlobalScope usage found"
else
    while IFS= read -r line; do
        violation "GlobalScope found (use structured concurrency): $line"
    done <<< "$GLOBALSCOPE_MATCHES"
fi

# Check for Dispatchers.Unconfined (should never be used)
UNCONFINED_MATCHES=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "Dispatchers\.Unconfined" 2>/dev/null | \
    strip_noncode || true)

if [ -z "$UNCONFINED_MATCHES" ]; then
    success "No Dispatchers.Unconfined usage found"
else
    while IFS= read -r line; do
        violation "Dispatchers.Unconfined found (never use): $line"
    done <<< "$UNCONFINED_MATCHES"
fi

section "3. Checking Python Interpreter Entry Points Stay Off the Main Thread"

# This check used to require `Dispatchers.Main.immediate` around a
# `callAttr("initialize")` call in :app. Both premises are gone: the interpreter
# moved behind AIDL into :rns-backend-py, and that call site no longer exists
# anywhere in the tree — so the check matched nothing and reported INFO forever.
#
# The live invariant is the opposite one, documented on
# PythonRnsRuntime.applyAndroidEnvPatches: "every PyObject call here runs on
# Dispatchers.IO". Chaquopy calls block, so reaching the interpreter from the main
# thread is an ANR. Flag entry points with Dispatchers.Main in the 5 preceding
# lines (same -B 5 heuristic the other sections use).
PYTHON_SRC=$(find rns-backend-py/src -mindepth 2 -maxdepth 2 -type d \( -name kotlin -o -name java \) 2>/dev/null |
    grep -v -E "/src/(test|androidTest)[^/]*/" | sort | tr '\n' ' ' || true)

PYTHON_MAIN_THREAD=$(find $PYTHON_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -B 5 -E 'Python\.start\(|\.callAttr\(' 2>/dev/null | \
    grep -E "Dispatchers\.Main" || true)

if [ -z "$PYTHON_SRC" ]; then
    info "No Python backend source found (kotlinBackend-only checkout?)"
elif [ -z "$PYTHON_MAIN_THREAD" ]; then
    success "Python interpreter entry points do not run on Dispatchers.Main"
else
    violation "Python interpreter reached from Dispatchers.Main (blocking call = ANR)"
    echo "$PYTHON_MAIN_THREAD"
fi

section "4. Summary - CI Optimized Check Complete"

info "Sections 5-9 skipped for CI performance (non-critical checks)"
info "All critical threading violations checked (runBlocking, GlobalScope, Unconfined, Python init)"
info "For comprehensive analysis, run audit-dispatchers-full.sh locally"

echo ""
echo "═══════════════════════════════════════"
echo "AUDIT SUMMARY"
echo "═══════════════════════════════════════"
echo "❌ Violations: $VIOLATIONS"
echo "⚠️  Warnings:   $WARNINGS"
echo "ℹ️  Info:       $INFO"
echo ""

if [ $VIOLATIONS -eq 0 ]; then
    echo -e "${GREEN}✅ No critical violations found!${NC}"
else
    echo -e "${RED}❌ $VIOLATIONS critical violations require fixing${NC}"
fi

if [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}⚠️  $WARNINGS warnings should be reviewed${NC}"
fi

echo ""
echo "Full report saved to: $REPORT_FILE"
echo ""

# Exit with error if violations found
exit $VIOLATIONS
