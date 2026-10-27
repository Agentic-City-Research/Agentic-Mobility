"""Check repository Markdown links and image paths without network access."""

from collections import Counter
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
SKIP = {".git", ".vscode", ".venv", "venv", "target", "tmp", "cache", "output", "node_modules", "__pycache__"}
LINK = re.compile(r"\]\((<[^>]+>|[^\s)]+)(?:\s+[^)]*)?\)")
HTML_LINK = re.compile(r"<(?:a|img)\s+[^>]*(?:href|src)=[\"']([^\"']+)", re.I)
ANCHOR = re.compile(r"<a\s+[^>]*(?:id|name)=[\"']([^\"']+)", re.I)
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$", re.M)
FENCED = re.compile(r"^\s*(`{3,}|~{3,}).*?^\s*\1\s*$", re.M | re.S)


def markdown_files():
    for path in ROOT.rglob("*.md"):
        parts = path.relative_to(ROOT).parts
        if any(part in SKIP or part.startswith("output-") for part in parts[:-1]):
            continue
        yield path


def anchors(path):
    text = FENCED.sub("", path.read_text(encoding="utf-8-sig"))
    result = set(ANCHOR.findall(text))
    counts = Counter()
    for heading in HEADING.findall(text):
        heading = re.sub(r"<[^>]+>", "", heading).strip().lower()
        slug = re.sub(r"[^\w\-\s]", "", heading).replace(" ", "-")
        index = counts[slug]
        counts[slug] += 1
        result.add(slug if index == 0 else f"{slug}-{index}")
    return result


def main():
    errors, checked = [], 0
    for path in markdown_files():
        text = FENCED.sub("", path.read_text(encoding="utf-8-sig"))
        for match in list(LINK.finditer(text)) + list(HTML_LINK.finditer(text)):
            target = match.group(1).strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or target.startswith("//"):
                continue
            file_part = unquote(parsed.path)
            fragment = unquote(parsed.fragment)
            destination = (path.parent / file_part).resolve() if file_part else path
            checked += 1
            if not destination.exists():
                errors.append(f"{path.relative_to(ROOT)}: missing {target}")
            elif fragment and destination.suffix.lower() == ".md" and fragment not in anchors(destination):
                errors.append(f"{path.relative_to(ROOT)}: missing anchor {target}")
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Checked {checked} local Markdown/HTML links; {len(errors)} errors.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
