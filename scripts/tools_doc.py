#!/usr/bin/env python3
"""Keep docs/tools.md and bridge/tools.json in step with the plugin's tool list.

Usage: tools_doc.py render|check [plugin/backend/build/tool-list.json]
render rewrites the generated parameter tables in docs/tools.md; check fails if docs/tools.md lacks a section
for a tool, its tables are stale, or bridge/tools.json differs in names or schemas (what the bridge compares).
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs/tools.md"
BRIDGE = ROOT / "bridge/tools.json"
BEGIN, END = "<!-- generated: tool parameters -->", "<!-- /generated -->"


def tables(tools):
    out = [BEGIN, ""]
    for t in sorted(tools, key=lambda t: t["name"]):
        props = t["inputSchema"].get("properties", {})
        required = set(t["inputSchema"].get("required", []))
        out += [f"### `{t['name']}`", ""]
        if not props:
            out += ["No parameters.", ""]
            continue
        out += ["| Parameter | Type | Required | Description |", "|---|---|---|---|"]
        out += rows(props, required, "")
        out.append("")
    return "\n".join(out + [END])


def rows(props, required, prefix):
    out = []
    for name, p in props.items():
        kind = p["type"] + (f" of {p['items']['type']}" if p["type"] == "array" else "")
        desc = (p.get("description") or "").replace("|", "\\|")
        out.append(f"| `{prefix}{name}` | {kind} | {'yes' if name in required else 'no'} | {desc} |")
        item = p.get("items", {})
        if item.get("type") == "object":
            out += rows(item.get("properties", {}), set(item.get("required", [])), f"{prefix}{name}[].")
    return out


def rendered(doc, tools):
    head, _, rest = doc.partition(BEGIN)
    _, _, tail = rest.partition(END)
    return head + tables(tools) + tail


def shape(tools):
    return {t["name"]: t["inputSchema"] for t in tools}


def main(argv):
    if not argv or argv[0] not in ("render", "check"):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    tools = json.loads(Path(argv[1] if len(argv) > 1 else ROOT / "plugin/backend/build/tool-list.json").read_text())
    doc = DOC.read_text()
    if argv[0] == "render":
        DOC.write_text(rendered(doc, tools))
        return 0
    problems = [f"docs/tools.md has no '## `{t['name']}`' section" for t in tools
                if not re.search(rf"^## `{re.escape(t['name'])}`", doc, re.M)]
    if rendered(doc, tools) != doc:
        problems.append("docs/tools.md parameter tables are stale: run scripts/tools_doc.py render")
    if shape(json.loads(BRIDGE.read_text())) != shape(tools):
        problems.append("bridge/tools.json differs from the plugin's tool list in names or schemas")
    print("\n".join(problems) if problems else "tools_doc: docs/tools.md and bridge/tools.json match the plugin")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
