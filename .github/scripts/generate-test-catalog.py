#!/usr/bin/env python3
"""Generate the "Test evidence" docs page from Surefire results.

Reads every module's target/surefire-reports/TEST-*.xml (the
authoritative record of what actually ran) and enriches each test with
its rationale block extracted from the test sources
(*/src/test/kotlin/**): the "What is tested / Success criteria / Why it
matters" comment run at the top of the test body. Writes
docs/tests/test-evidence.md, which the Docs workflow feeds into MkDocs.

The page is GENERATED - it must never be checked in or edited by hand,
otherwise it becomes a drifting mirror of the suite. MkDocs' --strict
build fails if this script did not run first, which is the intended
guard.

Usage: python3 .github/scripts/generate-test-catalog.py [output_md]
"""

import html
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

OUTPUT = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/tests/test-evidence.md")

FUN_RE = re.compile(r"^\s*fun `([^`]+)`\(")
QUESTIONS = [
    ("What is tested?", re.compile(r"What is tested:")),
    ("How is success determined?", re.compile(r"Success criteria:")),
    ("Why does it matter?", re.compile(r"Why it matters:")),
]
# The rationale run is directly followed by the Given/When/Then comments;
# everything from the first stage label on is test structure, not rationale.
STAGES_RE = re.compile(r"\b(?:Given|When|Then|And)\b\s*(?:\([^)]*\))?:")


def extract_rationales(src_dir: Path) -> dict:
    """Map (top-level test class, test method name) -> {question: text}.

    The rationale block is the run of '//' comment lines directly after
    the test function's opening line, cut off at the first Given/When/
    Then stage label; method names are sentence-shaped and unique per
    file, so the file's class name plus the method name is a sufficient
    key.

    A detected rationale must answer all three questions (the
    CONTRIBUTING.md contract); a partial block fails the run with the
    exact file and test, so incomplete evidence can never be published
    as documented.
    """
    rationales = {}
    incomplete = []
    for kt in sorted(src_dir.rglob("*Test.kt")):
        clazz = kt.stem
        lines = kt.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            m = FUN_RE.match(line)
            if not m:
                continue
            comment = []
            for follow in lines[i + 1:]:
                stripped = follow.strip()
                if stripped.startswith("//"):
                    comment.append(stripped[2:].strip())
                elif stripped == "" and not comment:
                    continue
                else:
                    break
            text = " ".join(comment)
            stage = STAGES_RE.search(text)
            if stage:
                text = text[: stage.start()]
            parsed = {}
            spans = [(q.search(text), label) for label, q in QUESTIONS]
            spans = [(mm.start(), mm.end(), label) for mm, label in spans if mm]
            spans.sort()
            for idx, (start, end, label) in enumerate(spans):
                stop = spans[idx + 1][0] if idx + 1 < len(spans) else len(text)
                answer = text[end:stop].strip()
                if answer:
                    parsed[label] = answer
            if parsed:
                if len(parsed) < len(QUESTIONS):
                    missing = [q.pattern for label, q in QUESTIONS if label not in parsed]
                    incomplete.append(f"{kt}: `{m.group(1)}` lacks {', '.join(missing)}")
                rationales[(clazz, m.group(1))] = parsed
    if incomplete:
        sys.exit(
            "error: incomplete test rationale blocks (CONTRIBUTING.md requires"
            " all three labels):\n  " + "\n  ".join(incomplete)
        )
    return rationales


def load_suites(reports_dir: Path):
    """Group testcases: top-level class -> nested group -> [test names]."""
    suites = defaultdict(lambda: defaultdict(list))
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}
    times = defaultdict(float)
    for report in sorted(reports_dir.glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        totals["tests"] += int(root.get("tests", 0))
        totals["failures"] += int(root.get("failures", 0))
        totals["errors"] += int(root.get("errors", 0))
        totals["skipped"] += int(root.get("skipped", 0))
        totals["time"] += float(root.get("time", 0.0))
        for case in root.iter("testcase"):
            classname = case.get("classname", "")
            simple = classname.rsplit(".", 1)[-1]
            top, _, group = simple.partition("$")
            suites[top][group].append(case.get("name", ""))
            times[top] += float(case.get("time", 0.0))
    return suites, totals, times


def md_escape(text: str) -> str:
    return html.escape(text, quote=False)


def main() -> None:
    modules = sorted(p.parts[0] for p in Path(".").glob("*/target/surefire-reports"))
    if not modules:
        sys.exit("error: no target/surefire-reports found in any module - run `mvn verify` first")

    grand = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}
    per_module = {}
    for module in modules:
        rationales = extract_rationales(Path(module) / "src" / "test" / "kotlin")
        suites, totals, times = load_suites(Path(module) / "target" / "surefire-reports")
        per_module[module] = (suites, totals, times, rationales)
        for key in grand:
            grand[key] += totals[key]

    # Anchors the way python-markdown's toc extension assigns them: the
    # slug of the heading text, with _1, _2, ... appended for repeats in
    # DOCUMENT order. Both modules contain equally named test classes
    # (the twin symmetry), so the summary table must link the exact
    # per-module anchor, not just the slug of the first occurrence.
    anchors = {}
    seen = defaultdict(int)
    for module in modules:
        for top in sorted(per_module[module][0]):
            slug = top.lower()
            anchors[(module, top)] = slug if seen[slug] == 0 else f"{slug}_{seen[slug]}"
            seen[slug] += 1

    out = []
    out.append("# Test evidence")
    out.append("")
    out.append(
        "> Generated by the Docs workflow from the Surefire results of the"
        " test run and the rationale comments in the test sources. Do not"
        " edit by hand."
    )
    out.append("")
    out.append(
        f"The run executed **{grand['tests']} tests**"
        f" ({grand['failures']} failures, {grand['errors']} errors,"
        f" {grand['skipped']} skipped) in {grand['time']:.1f}s across both"
        " modules, against a real HTTP peer (the JDK's own HTTP server) but"
        " without Docker or any external service - see"
        " [CONTRIBUTING](https://github.com/Inqudium/legatium/blob/main/CONTRIBUTING.md)."
    )
    out.append("")
    out.append("| Module | Component under test | Tests | Time |")
    out.append("|---|---|---:|---:|")
    for module in modules:
        suites, _, times, _ = per_module[module]
        for top in sorted(suites, key=lambda t: -sum(len(v) for v in suites[t].values())):
            count = sum(len(v) for v in suites[top].values())
            out.append(f"| `{module}` | [`{top}`](#{anchors[(module, top)]}) | {count} | {times[top]:.1f}s |")
    out.append("")

    for module in modules:
        suites, totals, _, rationales = per_module[module]
        out.append(f"## {module}")
        out.append("")
        out.append(f"{totals['tests']} tests.")
        out.append("")
        for top in sorted(suites):
            count = sum(len(v) for v in suites[top].values())
            out.append(f"### {top}")
            out.append("")
            out.append(f"{count} tests.")
            out.append("")
            for group in sorted(suites[top], key=lambda g: (g == "", g)):
                if group:
                    out.append(f"#### {md_escape(group)}")
                    out.append("")
                for name in sorted(suites[top][group]):
                    out.append(f"**{md_escape(name)}**")
                    out.append("")
                    rationale = rationales.get((top, name))
                    if rationale:
                        out.append('??? quote "Rationale"')
                        for label, answer in rationale.items():
                            out.append(f"    **{label}** {md_escape(answer)}")
                            out.append("")
                        out.append("")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    documented = sum(
        1
        for module in modules
        for top in per_module[module][0]
        for g in per_module[module][0][top]
        for n in per_module[module][0][top][g]
        if (top, n) in per_module[module][3]
    )
    print(f"wrote {OUTPUT}: {grand['tests']} tests, {documented} with rationale blocks")


if __name__ == "__main__":
    main()
