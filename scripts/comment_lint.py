#!/usr/bin/env python3
"""Flag over-long or history-narrating comments in Kotlin and Go. Usage: comment_lint.py [paths...]

Heuristic: rewording or splitting a comment evades it. Skip a block with `comment-lint: allow <reason>`.
"""
import re
import sys
from pathlib import Path

MAX_LINES = 2
NARRATION = re.compile(
    r"\b(previously|now also|was changed|moved (from|to)|we decided|as discussed|"
    r"per (the )?review|the user (asked|wants)|this session|task \d+|phase \d+)\b|(?<![\w&/])#\d+\b", re.I)
TAG = re.compile(r"^\s*\*?\s*@\w+")
SUFFIXES = {".kt", ".kts", ".go"}


def blocks(lines):
    """Yield (line number, prose lines without @tags, first raw line) per comment block."""
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("/*"):
            start, body = i, []
            while True:
                text = lines[i].strip()
                body.append(text)
                if "*/" in text:
                    break
                i += 1
                if i == len(lines):
                    break
            prose = [b for b in (re.sub(r"^/\*\*?|\*/$|^\*", "", t).strip() for t in body) if b]
            yield start + 1, [p for p in prose if not TAG.match(p)], body[0]
        elif s.startswith("//") and not s.startswith("//go:"):
            start, prose = i, []
            while i < len(lines) and lines[i].strip().startswith("//") and not lines[i].strip().startswith("//go:"):
                prose.append(lines[i].strip()[2:].strip())
                i += 1
            yield start + 1, [p for p in prose if p], lines[start].strip()
            continue
        i += 1


def check(path):
    out = []
    lines = path.read_text(encoding="utf-8").splitlines()
    for line_no, prose, first in blocks(lines):
        if "comment-lint: allow" in first:
            continue
        if path.suffix == ".go" and prose and prose[0].startswith("Package "):
            continue
        if len(prose) > MAX_LINES:
            out.append(f"{path}:{line_no}: comment is {len(prose)} lines (max {MAX_LINES}): refactor, or move it to docs/")
        for p in prose:
            m = NARRATION.search(p)
            if m:
                out.append(f"{path}:{line_no}: narration {m.group(0)!r}: history belongs in docs/decisions.md")
                break
    return out


def main(argv):
    roots = [Path(a) for a in argv] or [Path(".")]
    files = [f for r in roots for f in ([r] if r.is_file() else r.rglob("*"))
             if f.suffix in SUFFIXES and not any(p in {"build", ".gradle", "tmp", "testdata"} for p in f.parts)]
    found = [msg for f in sorted(files) for msg in check(f)]
    print("\n".join(found) if found else f"comment-lint: {len(files)} files clean (heuristic; see AGENTS.md)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
