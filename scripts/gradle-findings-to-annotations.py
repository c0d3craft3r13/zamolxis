#!/usr/bin/env python3
"""Convert detekt/CPD XML reports into GitHub Actions ::error annotations.

Used by ci.yml so a red detekt/CPD step names its findings in the job
annotations — visible without log-download permissions.

Scans every module's build/reports/detekt/detekt.xml (checkstyle format)
and build/reports/cpd/cpdCheck.xml (PMD CPD format). Emits at most 40
annotations (GitHub shows only a handful per step anyway); prints the
total count so truncation is visible.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAX_ANNOTATIONS = 40

emitted = 0
total = 0


def annotate(path: str, line: int, message: str) -> None:
    global emitted, total
    total += 1
    if emitted >= MAX_ANNOTATIONS:
        return
    emitted += 1
    # Workflow commands can't contain raw newlines or some symbols.
    safe = message.replace("\n", " ").replace("\r", " ")[:400]
    rel = Path(path)
    if rel.is_absolute():
        try:
            rel = rel.relative_to(ROOT)
        except ValueError:
            pass
    print(f"::error file={rel},line={line}::{safe}")


def from_checkstyle(report: Path) -> None:
    try:
        tree = ET.parse(report)
    except ET.ParseError:
        return
    for file_el in tree.getroot().iter("file"):
        name = file_el.get("name", "")
        for err in file_el.iter("error"):
            annotate(
                name,
                int(err.get("line", "1")),
                f"{err.get('source', 'detekt')}: {err.get('message', '')}",
            )


def from_cpd(report: Path) -> None:
    try:
        tree = ET.parse(report)
    except ET.ParseError:
        return
    ns = {"cpd": "https://pmd-code.org/schema/cpd-report"}
    root = tree.getroot()
    duplications = root.findall("cpd:duplication", ns) or root.findall("duplication")
    for dup in duplications:
        files = dup.findall("cpd:file", ns) or dup.findall("file")
        if not files:
            continue
        first = files[0]
        others = ", ".join(
            f"{f.get('path', '?')}:{f.get('line', '?')}" for f in files[1:]
        )
        annotate(
            first.get("path", ""),
            int(first.get("line", "1")),
            f"cpd: {dup.get('lines', '?')}-line duplication, also in {others}",
        )


def main() -> int:
    for report in sorted(ROOT.glob("*/build/reports/detekt/detekt.xml")):
        from_checkstyle(report)
    for report in sorted(ROOT.glob("*/build/reports/cpd/cpdCheck.xml")):
        from_cpd(report)
    print(f"annotations: emitted {emitted} of {total} findings", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
