#!/bin/bash

# audit-dispatchers.sh
# Threading Architecture - Dispatcher Usage Audit Script
#
# This script audits Kotlin coroutine dispatcher usage across the codebase
# to ensure proper threading architecture 

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
#
# Counters use `VAR=$((VAR + 1))`, not `((VAR++))`: post-increment evaluates to the
# OLD value, so the first call — when the counter is still 0 — makes the arithmetic
# command exit 1 and `set -e` kills the script. That is why this script used to stop
# at its first info() and never reached sections 4-9. audit-dispatchers.sh was
# already fixed this way; keep the two in sync.
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
# per-check filename filters. `find`, not `ls` + glob: under `set -euo pipefail` a
# glob that matches nothing makes `ls` exit non-zero and kills the script.
APP_SRC="app/src/main/java"
DATA_SRC="data/src/main/java"
RETICULUM_SRC=$(find . -mindepth 4 -maxdepth 4 -type d \( -name java -o -name kotlin \) -path "*/src/*" |
    sed 's|^\./||' |
    grep -v -E "^(app|data)/src/main/(java|kotlin)$" |
    grep -v -E "/src/(test|androidTest)[^/]*/" |
    sort | tr '\n' ' ' || true)

# Exclude test files and build outputs
EXCLUDE_DIRS="*/build/* */test/* */androidTest/*"

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
# anywhere in the tree — so the check matched nothing and warned forever.
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
    warning "No Python backend source found (kotlinBackend-only checkout?)"
elif [ -z "$PYTHON_MAIN_THREAD" ]; then
    success "Python interpreter entry points do not run on Dispatchers.Main"
else
    violation "Python interpreter reached from Dispatchers.Main (blocking call = ANR)"
    echo "  Found: $PYTHON_MAIN_THREAD"
fi

section "4. Checking for Undocumented Coroutine Launches"

# Find launch/async without explicit dispatcher or nearby comment
LAUNCH_PATTERNS=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "launch\s*{" 2>/dev/null || true)

if [ -n "$LAUNCH_PATTERNS" ]; then
    LAUNCH_COUNT=$(echo "$LAUNCH_PATTERNS" | wc -l)
    info "Found $LAUNCH_COUNT coroutine launch statements (using scope defaults or explicit dispatchers)"
else
    info "Found 0 coroutine launch statements"
fi

# Note: Detailed dispatcher counting skipped to avoid grep performance issues
# All critical checks (runBlocking, GlobalScope, Unconfined) are validated above

section "5. Checking Room Database Calls with Redundant Dispatchers"

# Find Room DAO calls wrapped in withContext(Dispatchers.IO)
ROOM_WITH_IO=$(find $APP_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -B 2 -A 2 "withContext.*Dispatchers\.IO" 2>/dev/null | \
    grep -E "(Dao\.|\.insert\(|\.update\(|\.delete\(|\.get)" || true)

if [ -z "$ROOM_WITH_IO" ]; then
    success "No redundant dispatchers around Room database calls"
else
    warning "Potential redundant Dispatchers.IO around Room calls (Room handles threading):"
    echo "$ROOM_WITH_IO" | head -5
    if [ "$(echo "$ROOM_WITH_IO" | wc -l)" -gt 5 ]; then
        info "... and $(($(echo "$ROOM_WITH_IO" | wc -l) - 5)) more"
    fi
fi

section "6. Checking Dispatcher Usage Patterns"

# Check for CPU-intensive work on IO dispatcher
CPU_WORK_PATTERNS="parseJson|serialize|deserialize|calculate|compute|process.*data|encrypt|decrypt"
CPU_ON_IO=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -B 5 -E "$CPU_WORK_PATTERNS" 2>/dev/null | \
    grep "Dispatchers\.IO" || true)

if [ -z "$CPU_ON_IO" ]; then
    success "No obvious CPU-intensive work on IO dispatcher"
else
    warning "Potential CPU-intensive work on IO dispatcher (should use Default):"
    echo "$CPU_ON_IO" | head -3
fi

# Check for I/O work on Default dispatcher
IO_WORK_PATTERNS="readFile|writeFile|readText|writeText|FileInputStream|FileOutputStream|Socket"
IO_ON_DEFAULT=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -B 5 -E "$IO_WORK_PATTERNS" 2>/dev/null | \
    grep "Dispatchers\.Default" || true)

if [ -z "$IO_ON_DEFAULT" ]; then
    success "No obvious I/O work on Default dispatcher"
else
    warning "Potential I/O work on Default dispatcher (should use IO):"
    echo "$IO_ON_DEFAULT" | head -3
fi

section "7. Python Call Patterns"

# Find all Python wrapper calls
PYTHON_CALLS=$(find $APP_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "wrapper.*\.callAttr\|wrapper.*\.call" 2>/dev/null || true)

PYTHON_CALL_COUNT=$(echo "$PYTHON_CALLS" | grep -c "." || echo "0")
info "Found $PYTHON_CALL_COUNT Python wrapper calls"

# Check if any use PythonExecutor (Phase 3.2)
PYTHON_EXECUTOR_USAGE=$(echo "$PYTHON_CALLS" | grep -c "PythonExecutor" || echo "0")
if [ "$PYTHON_EXECUTOR_USAGE" -gt 0 ]; then
    info "$PYTHON_EXECUTOR_USAGE Python calls use PythonExecutor pattern"
else
    info "No PythonExecutor usage found (Phase 3.2 not implemented yet)"
fi

# Show sample Python calls for manual review
info "Sample Python calls (first 5):"
echo "$PYTHON_CALLS" | head -5 | while IFS= read -r line; do
    echo "  $line"
done

section "8. Dispatcher Distribution Analysis"

# Count usage of each dispatcher type
MAIN_COUNT=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -c "Dispatchers\.Main" 2>/dev/null | awk '{s+=$1} END {print s}')

IO_COUNT=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -c "Dispatchers\.IO" 2>/dev/null | awk '{s+=$1} END {print s}')

DEFAULT_COUNT=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -c "Dispatchers\.Default" 2>/dev/null | awk '{s+=$1} END {print s}')

info "Dispatcher usage distribution:"
echo "  Dispatchers.Main: $MAIN_COUNT"
echo "  Dispatchers.IO: $IO_COUNT"
echo "  Dispatchers.Default: $DEFAULT_COUNT"

section "9. Documentation Coverage"

# Simplified documentation check
info "Documentation coverage check skipped (manual review recommended)"
info "Please review dispatcher usage in key files:"
info "  - ReticulumService.kt"
info "  - ServiceReticulumProtocol.kt"

section "10. Summary"

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
