"""Read the shared Markdown voice scripts without provider requests or generated copies."""
from __future__ import annotations

import hashlib
from pathlib import Path
import re


MAX_MARKDOWN_BYTES = 2 * 1024 * 1024
SCRIPT_ID = r"[a-z][a-z0-9_-]{0,47}"
ACTIVE = re.compile(rf"^ACTIVE_VOICE_SCRIPT = ({SCRIPT_ID})$", re.MULTILINE)
HEADING = re.compile(rf"^## ({SCRIPT_ID})(?: \| (.+))?$")


def parse_voice_scripts(markdown: str) -> dict:
    if len(markdown.encode("utf-8")) > MAX_MARKDOWN_BYTES:
        raise ValueError("The voice script file must be at most 2 MiB.")
    markdown = markdown.replace("\r\n", "\n").replace("\r", "\n")
    lines = markdown.split("\n")
    headings = []
    for index, line in enumerate(lines):
        if line == "##" or line.startswith("## ") or line.startswith("##\t"):
            match = HEADING.fullmatch(line)
            if not match:
                raise ValueError("Use script headings in the form: ## script_id | Display name.")
            headings.append((index, match[1], (match[2] or match[1]).strip()))
    if not 1 <= len(headings) <= 100:
        raise ValueError("Keep between 1 and 100 voice scripts in the file.")
    preamble = "\n".join(lines[:headings[0][0]])
    active = ACTIVE.findall(preamble)
    declarations = [line for line in lines[:headings[0][0]] if line.startswith("ACTIVE_VOICE_SCRIPT")]
    if len(active) != 1 or len(declarations) != 1:
        raise ValueError("Set ACTIVE_VOICE_SCRIPT = script_id once before the script sections.")
    scripts = []
    ids = set()
    for position, (line_index, script_id, name) in enumerate(headings):
        if script_id in ids:
            raise ValueError(f"Duplicate voice script ID: {script_id}.")
        if not name or len(name.encode("utf-16-le")) // 2 > 80:
            raise ValueError(f"Give {script_id} a name of 1 to 80 characters.")
        end = headings[position + 1][0] if position + 1 < len(headings) else len(lines)
        instructions = "\n".join(lines[line_index + 1:end]).strip()
        if not instructions or len(instructions.encode("utf-16-le")) // 2 > 20_000:
            raise ValueError(f"Give {script_id} instructions of 1 to 20,000 characters.")
        ids.add(script_id)
        scripts.append({"id": script_id, "name": name, "instructions": instructions,
                        "revision": hashlib.sha256(instructions.encode("utf-8")).hexdigest()})
    if active[0] not in ids:
        raise ValueError(f"ACTIVE_VOICE_SCRIPT names an unknown script: {active[0]}.")
    return {"activeScriptId": active[0], "scripts": scripts}


def load_voice_scripts(path: Path) -> tuple[str, dict]:
    if path.stat().st_size > MAX_MARKDOWN_BYTES:
        raise ValueError("The voice script file must be at most 2 MiB.")
    markdown = path.read_text(encoding="utf-8")
    return markdown, parse_voice_scripts(markdown)
