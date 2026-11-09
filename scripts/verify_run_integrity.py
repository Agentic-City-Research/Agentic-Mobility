#!/usr/bin/env python3
"""Recheck a sealed morning output directory after copying or archiving it."""
import argparse
import gzip
import hashlib
import json
import sys
from pathlib import Path


def verify(directory):
    status = json.loads((directory / "status.json").read_text(encoding="utf-8"))
    if not status.get("integrityVerified") or not (status.get("complete") or status.get("validated")):
        raise ValueError("run status does not report a completed, verified run")
    manifest = json.loads((directory / "integrity.json").read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or manifest.get("verified") is not True:
        raise ValueError("invalid integrity manifest")
    seen, counts = set(), {}
    for row in manifest["files"]:
        name = row["name"]
        if Path(name).name != name or name in seen:
            raise ValueError("duplicate or unsafe file name in manifest")
        seen.add(name)
        path = directory / name
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(65536), b""):
                digest.update(chunk)
        if path.stat().st_size != row["bytes"] or digest.hexdigest() != row["sha256"]:
            raise ValueError(f"stored bytes or SHA-256 differ: {name}")
        count, size, last = 0, 0, b""
        with (gzip.open(path, "rb") if row["compressed"] else path.open("rb")) as source:
            for chunk in iter(lambda: source.read(65536), b""):
                count += chunk.count(b"\n")
                size += len(chunk)
                last = chunk[-1:]
        if (size and last != b"\n") or count != row["records"] or count != row["writtenRecords"] or size != row["uncompressedBytes"]:
            raise ValueError(f"uncompressed length or record count differs: {name}")
        if row["logicalName"] in counts:
            raise ValueError("duplicate logical stream in manifest")
        counts[row["logicalName"]] = count
    for name, expected in manifest["expectedRows"].items():
        if counts.get(name, 0) != expected:
            raise ValueError(f"expected-record contract failed: {name}")
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("morning_directory", type=Path, help="path to the run's morning directory")
    args = parser.parse_args()
    try:
        count = verify(args.morning_directory)
    except (OSError, EOFError, ValueError, KeyError, TypeError, gzip.BadGzipFile) as error:
        print(f"INTEGRITY FAILED: {error}", file=sys.stderr)
        return 1
    print(f"Integrity verified: {count} JSONL streams. Counts do not prove semantic correctness or uniqueness.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
