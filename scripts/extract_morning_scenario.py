"""Extract fixed morning car-trip cohorts and terminal single-trip MATSim plans.

Defaults: core departures [07:00,09:00), buffered departures [06:00,09:00).
Source plans are not modified. Output directories must not already exist.
"""

import argparse
from collections import Counter
from copy import deepcopy
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET


def seconds(value):
    if value is None or value == "undefined":
        return None
    if ":" in value:
        hours, minutes, secs = map(float, value.split(":"))
        return hours * 3600 + minutes * 60 + secs
    return float(value)


def add_attribute(attributes, name, value, kind="java.lang.String"):
    if any(a.get("name") == name for a in attributes):
        raise ValueError(f"Source already defines extraction attribute {name}")
    ET.SubElement(attributes, "attribute", {"name": name, "class": kind}).text = str(value)


def selected_plan(person):
    plans = person.findall("plan")
    chosen = [p for p in plans if p.get("selected") == "yes"]
    if len(chosen) == 1:
        return chosen[0]
    if not chosen and len(plans) == 1:
        return plans[0]
    raise ValueError(f"No unambiguous selected plan for {person.get('id')}")


def extract_trip(person, index, origin, leg, destination, departure, core):
    output = ET.Element("person", {"id": person.attrib["id"]})
    source_attributes = person.find("attributes")
    attributes = ET.SubElement(output, "attributes") if source_attributes is None else deepcopy(source_attributes)
    if source_attributes is not None:
        output.append(attributes)
    add_attribute(attributes, "am_source_person_id", person.attrib["id"])
    add_attribute(attributes, "am_source_leg_index", index, "java.lang.Integer")
    add_attribute(attributes, "am_baseline_departure_seconds", departure, "java.lang.Double")
    add_attribute(attributes, "am_origin_activity_type", origin.attrib["type"])
    add_attribute(attributes, "am_destination_activity_type", destination.attrib["type"])
    add_attribute(attributes, "am_core_peak", str(core).lower(), "java.lang.Boolean")
    for key in ("start_time", "end_time"):
        if key in destination.attrib:
            add_attribute(attributes, "am_source_destination_" + key, destination.attrib[key])
    plan = ET.SubElement(output, "plan", {"selected": "yes"})
    first = deepcopy(origin)
    for key in ("start_time", "max_dur", "duration"):
        first.attrib.pop(key, None)
    first.set("end_time", origin.get("end_time", leg.get("dep_time", str(departure))))
    last = deepcopy(destination)
    # Terminal destination: no subsequent departure or fixed activity start.
    for key in ("start_time", "end_time", "max_dur", "duration"):
        last.attrib.pop(key, None)
    plan.extend([first, ET.Element("leg", {"mode": "car"}), last])
    return output


def collect(source, core_start, core_end, buffer_start):
    cohorts = {"core": ET.Element("population"), "buffered": ET.Element("population")}
    stats = {name: {"activity_pairs": Counter(), "departures_by_hour": Counter(), "core_trips": 0}
             for name in cohorts}
    source_people = source_trips = 0
    ids = set()
    for person in source.findall("person"):
        source_people += 1
        if person.attrib["id"] in ids:
            raise ValueError("Duplicate source person ID")
        ids.add(person.attrib["id"])
        elements = list(selected_plan(person))
        indices = [i for i, element in enumerate(elements) if element.tag == "leg"]
        source_trips += len(indices)
        for leg_index, element_index in enumerate(indices):
            leg = elements[element_index]
            if element_index == 0 or element_index == len(elements) - 1:
                raise ValueError("Leg without adjacent activities")
            origin, destination = elements[element_index - 1], elements[element_index + 1]
            if origin.tag != "activity" or destination.tag != "activity":
                raise ValueError("Expected activity-leg-activity source plan")
            departure = seconds(origin.get("end_time", leg.get("dep_time")))
            if departure is None:
                raise ValueError(f"Missing planned departure: {person.attrib['id']} leg {leg_index}")
            if leg.get("mode") != "car" or not buffer_start <= departure < core_end:
                continue
            core = core_start <= departure < core_end
            for name in ("core", "buffered") if core else ("buffered",):
                cohorts[name].append(extract_trip(person, leg_index, origin, leg, destination, departure, core))
                stats[name]["activity_pairs"][origin.attrib["type"] + "->" + destination.attrib["type"]] += 1
                stats[name]["departures_by_hour"][f"{int(departure // 3600):02d}:00"] += 1
                stats[name]["core_trips"] += int(core)
    # Retain original person IDs if they occur once; multiple trips become independent agents.
    for name, root in cohorts.items():
        counts = Counter(p.attrib["id"] for p in root)
        for person in root:
            if counts[person.attrib["id"]] > 1:
                attributes = {a.get("name"): a.text for a in person.find("attributes")}
                person.set("id", person.attrib["id"] + "__am_trip_" + attributes["am_source_leg_index"])
        stats[name]["source_persons"] = len(counts)
        stats[name]["multiple_trip_source_persons"] = sum(count > 1 for count in counts.values())
        stats[name]["persons"] = len(root)
        stats[name]["car_trips"] = len(root)
        stats[name]["pre_peak_trips"] = len(root) - stats[name]["core_trips"]
    return cohorts, stats, source_people, source_trips


def write_population(path, root):
    ET.indent(root, space="\t")
    data = (b'<?xml version="1.0" encoding="utf-8"?>\n'
            b'<!DOCTYPE population SYSTEM "http://www.matsim.org/files/dtd/population_v6.dtd">\n'
            + ET.tostring(root, encoding="utf-8") + b"\n")
    with path.open("xb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as stream:
            stream.write(data)
    with gzip.open(path, "rb") as stream:
        parsed = ET.parse(stream).getroot()
    person_ids = [person.attrib["id"] for person in parsed]
    assert len(person_ids) == len(set(person_ids)) == len(root)
    for person in parsed:
        elements = list(person.find("plan"))
        assert [e.tag for e in elements] == ["activity", "leg", "activity"]
        assert elements[1].get("mode") == "car"
        assert "end_time" in elements[0].attrib
        assert all(key not in elements[2].attrib for key in ("start_time", "end_time", "max_dur"))


def set_param(module, name, value):
    found = module.find(f"param[@name='{name}']")
    if found is None:
        found = ET.SubElement(module, "param", {"name": name})
    found.set("value", str(value))


def build_config(source_config, destination, population_name, output_directory):
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.parse(source_config, parser=parser).getroot()
    path_names = {"inputPlansFile", "inputNetworkFile", "inputFacilitiesFile", "inputCountsFile",
                  "inputPersonAttributesFile", "inputFacilityAttributesFile", "inputHouseholdAttributesFile"}
    for parameter in root.iter("param"):
        value = parameter.get("value")
        if parameter.get("name") in path_names and parameter.get("name") != "inputPlansFile" and value and value != "null":
            resolved = (source_config.parent / value).resolve()
            if not resolved.exists():
                raise FileNotFoundError(resolved)
            local_names = {"inputNetworkFile": "network.xml.gz", "inputFacilitiesFile": "facilities.xml.gz"}
            local_name = local_names.get(parameter.get("name"))
            if local_name:
                local_path = destination.parent / local_name
                if not local_path.exists():
                    shutil.copyfile(resolved, local_path)
                elif local_path.read_bytes() != resolved.read_bytes():
                    raise ValueError(f"Conflicting shared input: {local_path}")
                parameter.set("value", local_name)
            else:
                parameter.set("value", Path(os.path.relpath(resolved, destination.parent)).as_posix())
    plans = root.find("module[@name='plans']")
    set_param(plans, "inputPlansFile", population_name)
    controller = root.find("module[@name='controller']")
    set_param(controller, "lastIteration", 0)
    set_param(controller, "outputDirectory", output_directory)
    qsim = root.find("module[@name='qsim']")
    set_param(qsim, "startTime", "00:00:00")
    # Retain the source drain horizon; extraction does not truncate at 09:00.
    set_param(qsim, "flowCapacityFactor", "1.0")
    set_param(qsim, "storageCapacityFactor", "1.0")
    strategy = root.find("module[@name='replanning']")
    if strategy is None:
        strategy = root.find("module[@name='strategy']")
    if strategy is None:
        raise ValueError("Source config has no replanning module")
    for element in list(strategy):
        if element.tag == "parameterset" and element.get("type") == "strategysettings":
            strategy.remove(element)
    settings = ET.SubElement(strategy, "parameterset", {"type": "strategysettings"})
    set_param(settings, "strategyName", "KeepLastSelected")
    set_param(settings, "weight", "1.0")
    # Full-day activity utility is unsuitable as a single-trip evaluation metric.
    for parameter_set in root.iter("parameterset"):
        if parameter_set.get("type") == "activityParams":
            set_param(parameter_set, "scoringThisActivityAtAll", "false")
        if parameter_set.get("type") == "modeParams":
            mode = parameter_set.find("param[@name='mode']")
            if mode is not None and mode.get("value") == "car":
                set_param(parameter_set, "marginalUtilityOfTraveling_util_hr", "-6.0")
    ET.indent(root, space="\t")
    data = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!DOCTYPE config SYSTEM "http://www.matsim.org/files/dtd/config_v2.dtd">\n'
            + ET.tostring(root, encoding="unicode") + "\n")
    with destination.open("x", encoding="utf-8") as stream:
        stream.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--population", required=True, type=Path)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--peak-start", default="07:00:00")
    parser.add_argument("--peak-end", default="09:00:00")
    parser.add_argument("--buffer-start", default="06:00:00")
    args = parser.parse_args()
    core_start, core_end, buffer_start = map(seconds, (args.peak_start, args.peak_end, args.buffer_start))
    if not 0 <= buffer_start <= core_start < core_end <= 86400:
        raise ValueError("Require 0 <= buffer-start <= peak-start < peak-end <= 24h")
    if args.output.exists():
        raise FileExistsError(f"Output directory already exists: {args.output}")
    with gzip.open(args.population, "rb") as stream:
        source = ET.parse(stream).getroot()
    cohorts, stats, people, trips = collect(source, core_start, core_end, buffer_start)
    if not stats["core"]["persons"]:
        raise ValueError("No peak car trips found")
    args.output.mkdir(parents=True)
    for name in cohorts:
        filename = f"population-{name}.xml.gz"
        write_population(args.output / filename, cohorts[name])
        config_name = "config.xml" if name == "core" else "config-buffered.xml"
        build_config(args.config.resolve(), args.output / config_name, filename,
                     f"output/siouxfalls-am-{name}")
    report = {
        "source_population": str(args.population.resolve()),
        "source_population_sha256": hashlib.sha256(args.population.read_bytes()).hexdigest(),
        "source_persons": people, "source_legs": trips,
        "selection": "Selected-plan car legs, by planned origin activity end time; left-closed/right-open",
        "peak_start": args.peak_start, "peak_end": args.peak_end, "buffer_start": args.buffer_start,
        "cohorts": stats,
        "terminal_destination": True, "all_retained_legs_car": True,
        "scoring": "Activity scoring disabled; car travel utility -6 utils/hour; KeepLastSelected for baseline",
        "caveats": [
            "This is a fixed departure-cohort experiment, not all traffic physically present during the peak.",
            "The buffered cohort additionally includes pre-peak departures; it excludes departures at/after peak end.",
            "Stored source destination start times are planned input data, not inferred desired arrival deadlines.",
            "The existing agentic application still requires home-work-home plans and cannot yet control this single-trip scenario.",
            "The source QSim end horizon is retained; completion and unfinished trips must be checked after simulation.",
        ],
    }
    with (args.output / "extraction-report.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
