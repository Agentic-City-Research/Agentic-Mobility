"""Restore the original all-car population by removing verified _new copies.

Preserves retained XML person blocks byte-for-byte and refuses to overwrite
an existing output. This is not random population sampling.
"""

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def signature(element, person_root=False):
    attributes = dict(element.attrib)
    if person_root:
        attributes.pop("id", None)
    return (
        element.tag,
        tuple(sorted(attributes.items())),
        (element.text or "").strip(),
        tuple(signature(child) for child in element),
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--expected-persons", type=int, default=84110)
    args = parser.parse_args()
    if args.destination.exists():
        raise ValueError(f"Output already exists: {args.destination}")

    with gzip.open(args.source, "rb") as stream:
        original_xml = stream.read()
    root = ET.fromstring(original_xml)
    persons = root.findall("person")
    if len(persons) != 2 * args.expected_persons:
        raise ValueError(f"Unexpected source count: {len(persons)}")
    by_id = {person.attrib["id"]: person for person in persons}
    if len(by_id) != len(persons):
        raise ValueError("Duplicate person IDs in source")
    originals = {pid: person for pid, person in by_id.items() if not pid.endswith("_new")}
    copies = {pid: person for pid, person in by_id.items() if pid.endswith("_new")}
    if len(originals) != args.expected_persons or len(copies) != args.expected_persons:
        raise ValueError("Population does not contain equal original and _new groups")
    for pid, person in originals.items():
        copy = copies.get(pid + "_new")
        if copy is None or signature(person, True) != signature(copy, True):
            raise ValueError(f"Missing or non-identical copy for {pid}")

    modes = Counter(leg.get("mode") for person in originals.values() for leg in person.iter("leg"))
    if set(modes) != {"car"}:
        raise ValueError(f"Original population contains non-car legs: {modes}")

    blocks = list(re.finditer(rb'<person\b[^>]*>.*?</person\s*>', original_xml, re.DOTALL))
    if len(blocks) != len(persons):
        raise ValueError("Person XML block count does not match parsed count")
    pieces = []
    cursor = 0
    for match, person in zip(blocks, persons):
        pieces.append(original_xml[cursor:match.start()])
        if person.attrib["id"] in originals:
            pieces.append(match.group())
        cursor = match.end()
    pieces.append(original_xml[cursor:])
    restored_xml = b"".join(pieces)
    restored = ET.fromstring(restored_xml).findall("person")
    if [p.attrib["id"] for p in restored] != list(originals):
        raise ValueError("Restored IDs/order do not match original persons")
    if any(signature(p, True) != signature(originals[p.attrib["id"]], True) for p in restored):
        raise ValueError("Restored person data differs from source")

    with args.destination.open("xb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as stream:
            stream.write(restored_xml)
    with gzip.open(args.destination, "rb") as stream:
        if stream.read() != restored_xml:
            raise ValueError("Compressed output verification failed")
    print(json.dumps({
        "source": str(args.source.resolve()),
        "destination": str(args.destination.resolve()),
        "source_persons": len(persons),
        "verified_duplicate_pairs": len(copies),
        "output_persons": len(restored),
        "output_leg_modes": dict(modes),
        "original_person_data_preserved": True,
        "output_gzip_sha256": hashlib.sha256(args.destination.read_bytes()).hexdigest(),
    }, indent=2))


if __name__ == "__main__":
    main()
