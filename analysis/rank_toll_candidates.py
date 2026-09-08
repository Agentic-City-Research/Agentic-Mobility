"""Rank observed car-link/time cells from no-toll events; NEVER select candidates.

Uses full LinkEnter -> LinkLeave traversals, grouped by entry time. Supply one
events file per baseline iteration, preferably a stable final-iteration window.
All inputs must come from the same no-toll scenario and experiment specification.
"""
import argparse
import csv
import gzip
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET


def xml_elements(path, tag):
    opener = gzip.open if str(path).endswith(".gz") else open
    if str(path).endswith(".zst"):
        try:
            import zstandard
        except ImportError as exc:
            raise RuntimeError("Reading .zst requires zstandard; install analysis/requirements.txt") from exc
        opener = zstandard.open
    with opener(path, "rb") as stream:
        context = ET.iterparse(stream, events=("start", "end"))
        _, root = next(context)
        for event, elem in context:
            if event == "end" and elem.tag == tag:
                yield dict(elem.attrib)
                elem.clear()
                root.clear()


def rank(network_path, event_paths, start, end, bin_seconds):
    links = {}
    for link in xml_elements(network_path, "link"):
        if "car" in link.get("modes", "car").split(","):
            length, speed = float(link["length"]), float(link["freespeed"])
            if length < 0 or speed <= 0 or not math.isfinite(length + speed):
                raise ValueError("Invalid network length/speed for " + link["id"])
            links[link["id"]] = (length, speed)
    cells = {}
    for path in event_paths:
        cars, entries = set(), {}
        for e in xml_elements(path, "event"):
            kind, vehicle = e.get("type"), e.get("vehicle")
            if kind == "personMoney" and e.get("purpose") == "congestionPricing" and float(e.get("amount", 0)) != 0:
                raise ValueError("Congestion-pricing payments detected; input is not a no-toll baseline")
            if kind == "vehicle enters traffic":
                if e.get("networkMode") == "car":
                    cars.add(vehicle)
            elif kind == "vehicle leaves traffic":
                cars.discard(vehicle)
                entries.pop(vehicle, None)
            elif kind == "entered link" and vehicle in cars:
                entries[vehicle] = (e["link"], float(e["time"]))
            elif kind == "left link":
                entry = entries.pop(vehicle, None)
                if not entry or entry[0] != e["link"] or entry[0] not in links:
                    continue
                link_id, entry_time = entry
                if not start <= entry_time < end:
                    continue
                length, speed = links[link_id]
                delay = max(0.0, float(e["time"]) - entry_time - length / speed)
                key = link_id, int((entry_time - start) // bin_seconds)
                n, total = cells.get(key, (0, 0.0))
                cells[key] = n + 1, total + delay
    return [dict(link_id=link, bin=bin_index, start_seconds=start+bin_index*bin_seconds,
                 end_seconds=start+(bin_index+1)*bin_seconds, traversals=n,
                 total_delay_seconds=total, mean_delay_seconds=total/n)
            for (link, bin_index), (n, total) in sorted(cells.items(), key=lambda item: (-item[1][1], item[0]))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--network", type=Path, required=True)
    parser.add_argument("--events", type=Path, nargs="+", required=True)
    parser.add_argument("--start", type=int, default=21600)
    parser.add_argument("--end", type=int, default=36000)
    parser.add_argument("--bin-seconds", type=int, default=1800)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.start < 0 or args.end <= args.start or args.bin_seconds <= 0 or (args.end-args.start) % args.bin_seconds:
        parser.error("Invalid [start,end) or bin duration")
    if len(set(p.resolve() for p in args.events)) != len(args.events):
        parser.error("Duplicate events paths would double-count observations")
    if args.output.exists() or args.output.with_suffix(".provenance.json").exists():
        parser.error("Output already exists; choose a new name")
    rows = rank(args.network, args.events, args.start, args.end, args.bin_seconds)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["link_id", "bin", "start_seconds", "end_seconds",
                                                  "traversals", "total_delay_seconds", "mean_delay_seconds"])
        writer.writeheader()
        writer.writerows(rows)
    metadata = dict(network=str(args.network.resolve()), events=[str(p.resolve()) for p in args.events],
                    start=args.start, end=args.end, bin_seconds=args.bin_seconds,
                    selection="none; researcher must confirm candidates", metric="full car-link traversals; entry-time bins",
                    limitation="Static free speeds only; no marginal external cost estimate; inspect baseline manifest to verify no toll")
    with args.output.with_suffix(".provenance.json").open("x", encoding="utf-8") as stream:
        json.dump(metadata, stream, ensure_ascii=False, indent=2)
    print(f"Wrote {len(rows)} observed link/time cells. No candidate set was selected.")


if __name__ == "__main__":
    main()
