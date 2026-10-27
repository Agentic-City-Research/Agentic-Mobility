"""Export the five completed seed-268 runs for the arXiv documentation.

Uses Python's standard library; reads the source archive without modifying it.
Reading archived .zst networks requires Python 3.14+ or the zstandard package.
Example: python scripts/export_arxiv_documentation_data.py --source PATH_TO_ARXIV_V1_DATA
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
import csv
import gzip
import hashlib
from itertools import groupby
import json
import math
from pathlib import Path
from statistics import fmean


RUNS = {
    "Native": "2026-10-20_day100_llm0_noMgr_rr0.1_tm0.1_seed268",
    "MNL": "mnl_joint_no_manager_20261021_114815_b47e64/simulation",
    "Traveler": "2026-10-17_day100_llm11793_noMgr_rr0.1_tm0.1_mem-llm_info-controlled50pct_seed268",
    "Manager": "deepseek_native_manager_day100_seed268",
    "Joint": "2026-10-21_day100_llm11793_mgr_rr0.1_tm0.1_mem-llm_info-controlled50pct_seed268",
}
N, WORK, PEOPLE, DAYS = 11793, 11332, 16822, 100


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


class Archive:
    def __init__(self, root):
        self.root = root.resolve()
        self.files = {}

    def track(self, path):
        path = path.resolve()
        require(path.is_relative_to(self.root), f"Source outside archive: {path}")
        stat = path.stat()
        self.files.setdefault(path, (stat.st_size, stat.st_mtime_ns))
        return path

    def document(self, path):
        return json.loads(self.track(path).read_text(encoding="utf-8-sig"))

    def rows(self, path):
        with gzip.open(self.track(path), "rt", encoding="utf-8") as stream:
            for line in stream:
                require(line.endswith("\n"), f"Partial source record: {path}")
                yield json.loads(line)

    def inventory(self):
        result = {}
        for path, original in sorted(self.files.items()):
            stat = path.stat()
            require((stat.st_size, stat.st_mtime_ns) == original, f"Source changed: {path}")
            sha = digest(path)
            stat = path.stat()
            require((stat.st_size, stat.st_mtime_ns) == original, f"Source changed while hashing: {path}")
            result[path.relative_to(self.root).as_posix()] = {"bytes": stat.st_size, "sha256": sha}
        return result


def day_groups(rows):
    expected = 0
    for day, records in groupby(rows, key=lambda row: row["day"]):
        require(day == expected, f"Nonconsecutive log day: {day}; expected {expected}")
        yield day, list(records)
        expected += 1
    require(expected == DAYS, f"Expected 100 days; found {expected}")


def actions_digest(rows, departure_key, actual=False):
    values = []
    for row in rows:
        item = [row["tripId"], row["routeId"], float(row[departure_key])]
        if actual:
            item.append(float(row["actualDepartureSeconds"]))
        values.append(item)
    return hashlib.sha256(json.dumps(sorted(values), separators=(",", ":")).encode()).hexdigest()


def compact_hash_record(value):
    return {key: value[key] for key in ["sha256", "bytes", "type"] if key in value}


def safe_settings(value):
    # Export generation parameters, excluding service URLs, credential fields and host paths.
    allowed = {"model", "modelId", "max_tokens", "maxOutputTokens", "temperature", "top_p", "topP", "top_k", "topK", "seed", "thinking", "response_format", "responseFormat", "chat_template_kwargs", "reasonMaxChars", "maxOutputAttempts"}
    result = {key: item for key, item in value.items() if key in allowed}
    if isinstance(value.get("generation"), dict):
        result["generation"] = safe_settings(value["generation"])
    return result


def export_run(archive, condition, relative):
    morning = archive.root / relative / "morning"
    manifest = archive.document(morning / "manifest.json")
    status = archive.document(morning / "status.json")
    usage = archive.document(morning / "api-usage.json")
    require(status.get("complete") is True and status["daysCompleted"] == DAYS, f"Incomplete {condition}")
    require(manifest["seed"] == 268 and manifest["populationSeed"] == 6021, f"Wrong seeds: {condition}")
    require(manifest["simulatedPopulation"] == PEOPLE, f"Wrong population: {condition}")
    cohort = {row["tripId"]: row for row in archive.rows(morning / "cohort.jsonl.gz")}
    require(len(cohort) == N, f"Wrong target cohort: {condition}")
    cohort_identity = sorted((key, r["originLink"], r["destinationLink"], r["plannedDepartureSeconds"], r["plannedArrivalSeconds"]) for key, r in cohort.items())
    routes = {r["od"]: {v["routeId"]: v["links"] for v in r["routes"]} for r in archive.rows(morning / "candidates.jsonl.gz")}
    canonical_routes = sorted((od, sorted(options.items())) for od, options in routes.items())
    metrics = list(archive.rows(morning / "metrics.jsonl.gz"))
    require([r["day"] for r in metrics] == list(range(DAYS)), f"Wrong metric window: {condition}")
    daily, previous, previous_flow, joint_trip_digests = [], {}, Counter(), {}
    for day, trips in day_groups(archive.rows(morning / "trips.jsonl.gz")):
        current = {r["tripId"]: r for r in trips}
        require(len(trips) == len(current) == N and current.keys() == cohort.keys(), f"Trip identity mismatch: {condition}, {day}")
        metric = metrics[day]
        car = metric["fullDayCarTravel"]
        require(metric["completedCarTrips"] == metric["completedOdTrips"] == N and metric["stuckTrips"] == 0, f"Incomplete targets: {condition}, {day}")
        require(car["allPlannedCarLegsCompleted"] and car["completedCarLegs"] == car["expectedCarLegs"] == 33644, f"Incomplete population: {condition}, {day}")
        require(metric["workTripsWithDeadline"] == metric["completedWorkTripsWithDeadline"] == WORK, f"Wrong work cohort: {condition}, {day}")
        require(all(r["completedCar"] and r["completedOd"] and not r["stuck"] for r in trips), f"Incomplete trip records: {condition}, {day}")
        require(math.isclose(fmean(r["carSeconds"] for r in trips), metric["meanCarSeconds"], abs_tol=1e-8), f"Target mean mismatch: {condition}, {day}")
        require(all(r["routeFingerprint"] and r["selectedDepartureSeconds"] is not None for r in trips), f"Missing action: {condition}, {day}")
        flow = Counter((cohort[key]["originLink"], cohort[key]["destinationLink"], row["routeFingerprint"]) for key, row in current.items())
        route_changes = sum(row["routeFingerprint"] != previous[key]["routeFingerprint"] for key, row in current.items()) if day else None
        departure_changes = sum(row["selectedDepartureSeconds"] != previous[key]["selectedDepartureSeconds"] for key, row in current.items()) if day else None
        multi_changes = sum(row["routeFingerprint"] != previous[key]["routeFingerprint"] for key, row in current.items() if cohort[key]["candidateCount"] > 1) if day and condition in {"MNL", "Traveler", "Joint"} else None
        turnover = sum(abs(flow[key] - previous_flow[key]) for key in flow.keys() | previous_flow.keys()) / (2 * N) if day else None
        row = {
            "condition": condition, "seed": 268, "day": day,
            "simulated_people": PEOPLE, "target_trips": N, "completed_target_trips": metric["completedCarTrips"],
            "work_targets": WORK, "late_work_trips": metric["lateWorkTrips"], "stuck_targets": metric["stuckTrips"],
            "completed_car_legs": car["completedCarLegs"],
            "total_car_person_hours": car["carPersonHours"],
            "mean_daily_car_min_per_person": 60 * car["carPersonHours"] / PEOPLE,
            "mean_target_car_min": metric["meanCarSeconds"] / 60,
            "daily_target_p95_car_min": metric["p95CarSeconds"] / 60,
            "mean_target_door_min": metric["meanDoorSeconds"] / 60,
            "work_late_percent": 100 * metric["lateWorkTrips"] / WORK,
            "all_car_pretraffic_wait_person_hours": car["completedPreTrafficWaitPersonHours"],
            "completed_link_delay_vehicle_hours": metric["fullDayLinkTraffic"]["totalLinkDelaySeconds"] / 3600,
            "route_switches": route_changes,
            "overall_route_switch_percent": 100 * route_changes / N if day else None,
            "multiple_route_switch_percent": 100 * multi_changes / 9013 if multi_changes is not None else None,
            "departure_changes": departure_changes,
            "departure_adjustment_percent": 100 * departure_changes / N if day else None,
            "route_flow_turnover_percent": 100 * turnover if turnover is not None else None,
            "mean_selected_departure_minutes_earlier_than_original": -fmean(r["selectedDepartureSeconds"] - r["plannedDepartureSeconds"] for r in trips) / 60,
        }
        daily.append(row)
        if condition == "Joint":
            joint_trip_digests[day] = actions_digest(trips, "selectedDepartureSeconds", actual=True)
        previous, previous_flow = current, flow
    summary = {"condition": condition, "seed": 268, "first_log_day": 0, "last_log_day": 99, "days": DAYS}
    for key in daily[0]:
        if key not in {"condition", "seed", "day"}:
            present = [r[key] for r in daily if r[key] is not None]
            summary["mean_" + key] = fmean(present) if present else None
    summary["api_calls"] = usage["attemptedTotalCalls"]
    summary["tokens"] = usage["total_tokens"]
    provenance = {
        "source_run_relative": relative,
        "source_morning_relative": (Path(relative) / "morning").as_posix(),
        "status": status,
        "manifest": {key: manifest.get(key) for key in ["schemaVersion", "matsimVersion", "mode", "choicePolicy", "managerEnabled", "controlledTrips", "seed", "populationSeed", "selectionSeeds", "simulatedPopulation", "simulatedPersonIdsSha256", "flowCapacityFactor", "storageCapacityFactor", "firstIteration", "lastIteration", "memoryMaxChars", "travelerSemanticReview", "promptTemplate", "memoryTemplate", "candidateSettings", "nativeStrategies"]},
        "archived_runtime_artifact": compact_hash_record(manifest.get("codeArtifact", {})),
        "archived_input_artifacts": {key: compact_hash_record(value) for key, value in manifest["inputHashes"].items()},
        "archived_candidate_input_artifact": compact_hash_record(manifest.get("candidateFileHash", {})),
        "canonical_candidate_routes_sha256": hashlib.sha256(json.dumps(canonical_routes, separators=(",", ":")).encode()).hexdigest(),
        "cohort_identity_sha256": hashlib.sha256(json.dumps(cohort_identity, separators=(",", ":")).encode()).hexdigest(),
        "model_settings": {key: safe_settings(manifest[key]) for key in ["llmSettings", "memoryModelSettings", "managerSettings", "managerMemorySettings"] if isinstance(manifest.get(key), dict)},
        "api_usage": usage,
        "source_git_commit": None,
        "source_git_commit_note": "No source revision is inferred from this checkout; the archived executable hash identifies the historical build.",
    }
    print(f"Validated {condition}: 100 metric days, {N * DAYS:,} target trip records", flush=True)
    return daily, summary, provenance, cohort, routes, joint_trip_digests


def export_worker(arguments):
    root, condition, relative = arguments
    archive = Archive(root)
    result = export_run(archive, condition, relative)
    return result, archive.files


def audit_joint(archive, morning, cohort, routes, trip_digests):
    result_digests, decision_digests = {}, {}
    repaired_choices, choice_calls, total = 0, 0, 0
    for day, rows in day_groups(archive.rows(morning / "llm-results.jsonl.gz")):
        require(len(rows) == N and {r["tripId"] for r in rows} == cohort.keys(), f"Wrong final choices on {day}")
        for row in rows:
            member = cohort[row["tripId"]]
            od = member["originLink"] + ">" + member["destinationLink"]
            require(row["routeId"] in routes[od] and row["departureSeconds"] in member["departureOptionsSeconds"], f"Illegal finalized choice on {day}")
            require(bool(row["reason"].strip()) and not row["semanticReviewEnabled"], f"Invalid reason/review state on {day}")
            repaired_choices += row["repairCount"] > 0
            choice_calls += row["calls"]
        result_digests[day] = actions_digest(rows, "departureSeconds")
        total += len(rows)
    print("Joint finalized choices checked", flush=True)
    for day, rows in day_groups(archive.rows(morning / "decisions.jsonl.gz")):
        require(len(rows) == N and {r["tripId"] for r in rows} == cohort.keys(), f"Wrong applied choices on {day}")
        for row in rows:
            member = cohort[row["tripId"]]
            od = member["originLink"] + ">" + member["destinationLink"]
            links = routes[od][row["routeId"]]
            require(row["appliedRouteFingerprint"] == "[" + ", ".join(links) + "]", f"Applied route does not match the selected candidate on {day}")
        decision_digests[day] = actions_digest(rows, "selectedDepartureSeconds")
        require(decision_digests[day] == result_digests[day], f"Final/applied action mismatch on {day}")
    checks_count = 0
    for day, rows in day_groups(archive.rows(morning / "executed-route-checks.jsonl.gz")):
        require(len(rows) == N and {r["tripId"] for r in rows} == cohort.keys(), f"Wrong execution checks on {day}")
        for row in rows:
            member = cohort[row["tripId"]]
            od = member["originLink"] + ">" + member["destinationLink"]
            require(row["expectedLinks"] == routes[od][row["routeId"]], f"Expected route does not match the selected candidate on {day}")
            require(row["completedCar"] and row["completedRouteMatches"] and row["planTimeMatchesChoice"] and row["expectedLinks"] == row["executedLinks"], f"Execution mismatch on {day}")
        require(actions_digest(rows, "selectedDepartureSeconds") == decision_digests[day], f"Applied/plan mismatch on {day}")
        require(actions_digest(rows, "selectedDepartureSeconds", actual=True) == trip_digests[day], f"Execution/trip timing mismatch on {day}")
        checks_count += len(rows)
    print("Joint action-to-plan-to-execution links checked", flush=True)
    memories, repairs, memory_count, max_chars = {}, 0, 0, 0
    for day, rows in day_groups(archive.rows(morning / "memory-updates.jsonl.gz")):
        require(len(rows) == N and {r["tripId"] for r in rows} == cohort.keys(), f"Wrong memory coverage on {day}")
        for row in rows:
            previous_day, previous_memory = memories.get(row["tripId"], (None, ""))
            require(row["previousMemoryDay"] == previous_day and row["previousMemory"] == previous_memory, f"Broken personal memory chain on {day}")
            require(row["committed"] and row["version"] == day + 1 and row["memory"].strip() and len(row["memory"]) <= 500, f"Invalid committed memory on {day}")
            memories[row["tripId"]] = (day, row["memory"])
            repairs += row["repairCount"] > 0
            memory_count += 1
            max_chars = max(max_chars, len(row["memory"]))
    finals = list(archive.rows(morning / "memory-final.jsonl.gz"))
    require(len(finals) == N and {r["tripId"] for r in finals} == cohort.keys(), "Wrong final memory coverage")
    require(all(memories[r["tripId"]] == (r["day"], r["memory"]) and r["day"] == 99 for r in finals), "Final memory mismatch")
    print("Joint 100-day personal-memory chains checked", flush=True)
    contexts, uninformed, contexts_by_configuration, uninformed_by_configuration = 0, 0, {}, {}
    for condition in ["Traveler", "Joint"]:
        sampled, sampled_uninformed, sampled_days = 0, 0, Counter()
        source = archive.root / RUNS[condition] / "morning/context-samples.jsonl.gz"
        for row in archive.rows(source):
            info = row["information"]
            require(info["networkTrafficVisible"] is False, "Network information exposed in sampled context")
            if info["group"] == "uninformed":
                require(info["currentAnnouncementVisible"] is False, "Current announcement exposed")
                choices = row["candidates"] + [r for option in row["departureOptions"] for r in option["routes"]]
                if condition == "Joint":
                    require(all(r.get("estimatedToll") is None and r.get("estimatedMonetaryCost") is None and r.get("tollPolicyId") is None for r in choices), "Current cost exposed to uninformed traveler")
                else:
                    require(all(r.get("estimatedToll") == 0 and r.get("pricingKnowledge", {}).get("source") == "charging_disabled" and not r.get("pricingKnowledge", {}).get("announcement") for r in choices), "Incorrect disabled-charging context")
                sampled_uninformed += 1
            sampled += 1
            sampled_days[row["day"]] += 1
        require(sampled == 2000 and sampled_days == Counter({day: 20 for day in range(DAYS)}), f"Wrong context sample coverage: {condition}")
        contexts_by_configuration[condition] = sampled
        uninformed_by_configuration[condition] = sampled_uninformed
        contexts += sampled
        uninformed += sampled_uninformed
    manager_updates = list(archive.rows(morning / "manager-memory-updates.jsonl.gz"))
    require([r["day"] for r in manager_updates] == list(range(DAYS)), "Incomplete manager memory days")
    old, old_day = "", None
    for row in manager_updates:
        require(row["memoryStatus"] == "updated" and row["previousMemory"] == old and row["previousMemoryDay"] == old_day and not row["policyChangedByReflection"], "Manager memory discontinuity")
        require(row["wordCount"] <= 1000 and row["completeReplacement"], "Invalid manager memory")
        old, old_day = row["memory"], row["day"]
    manager_attempts = list(archive.rows(morning / "manager-memory-attempts.jsonl.gz"))
    policy_records = list(archive.rows(morning / "manager-decisions.jsonl.gz"))
    require([r["day"] for r in policy_records] == [20, 50, 80], "Wrong policy opportunities")
    invalid_manager_attempts = sum(not r["valid"] for r in manager_attempts)
    require(total == checks_count == memory_count == N * DAYS, "Wrong full audit coverage")
    result = {
        "configuration": "Joint", "first_log_day": 0, "last_log_day": 99,
        "finalized_target_decisions": total,
        "legal_route_departure_choices": True,
        "accepted_actions_linked_to_applied_plans_and_completed_routes": True,
        "applied_route_fingerprints_match_selected_candidate_links": True,
        "expected_and_executed_link_sequences_match_selected_candidate_routes": True,
        "completed_execution_checks": checks_count,
        "finalized_personal_memory_records": memory_count,
        "continuous_personal_memory_chains": len(memories), "personal_memory_chain_days": DAYS,
        "maximum_personal_memory_characters_observed": max_chars,
        "repaired_travel_decision_records": repaired_choices,
        "repaired_personal_memory_records": repairs,
        "traveler_decision_calls_in_final_records": choice_calls,
        "policy_decisions": len(policy_records),
        "completed_daily_manager_memory_updates": len(manager_updates),
        "manager_memory_attempts": len(manager_attempts),
        "invalid_manager_memory_attempts": invalid_manager_attempts,
        "sampled_information_input_contexts": contexts,
        "sampled_information_contexts_by_configuration": contexts_by_configuration,
        "sampled_uninformed_input_contexts": uninformed,
        "sampled_uninformed_contexts_by_configuration": uninformed_by_configuration,
        "no_current_announcements_to_uninformed_in_inspected_contexts": True,
        "uninformed_joint_current_toll_and_total_cost_unknown_in_inspected_contexts": True,
        "uninformed_traveler_charging_disabled_in_inspected_contexts": True,
        "network_traffic_visibility_false_in_inspected_contexts": True,
        "information_check_method": "Inspect 2,000 sampled structured contexts per Traveler/Joint configuration (20 per day). Check network-visibility flags in both. For uninformed Joint contexts, check null current-cost/policy-ID fields in both route and departure-option projections. For uninformed Traveler contexts, check explicit charging_disabled knowledge with zero tolls and no announcements; base monetary costs may remain visible. This is not a semantic audit of every full prompt.",
        "action_link_method": "Compare canonical per-day trip/route/departure digests across finalized results, applied decisions and execution checks; compare selected and realized times against trip records. Match applied fingerprints and expected link sequences to the selected candidate, then check complete expected/executed link equality and plan-time matches.",
        "memory_chain_method": "Compare each committed previousMemory and previousMemoryDay with the preceding update for the same target; check versions, bounds and final-memory equality.",
        "semantic_review_enabled": False,
        "semantic_accuracy_status": "Not systematically established.",
    }
    require((repaired_choices, repairs, invalid_manager_attempts, contexts) == (2433, 2943, 1, 4000), f"Audit counts differ from the manuscript: {(repaired_choices, repairs, invalid_manager_attempts, contexts)}")
    return result


def policy_schedule(archive, condition, relative):
    morning = archive.root / relative / "morning"
    decisions = list(archive.rows(morning / "manager-decisions.jsonl.gz"))
    require([r["day"] for r in decisions] == [20, 50, 80], f"Wrong {condition} pricing schedule")
    output = []
    for row in decisions:
        rules = row["policy"]["rules"]
        require(len(rules) <= 40 and len({r["link_id"] for r in rules}) == len(rules), "Invalid policy size")
        require(all(math.isfinite(r["amount"]) and r["amount"] >= 0 and r["start"] == 0 and r["end"] == 108000 for r in rules), "Invalid policy rule")
        output.append({"condition": condition, "day": row["day"], "simulation_day": row["day"] + 1, "action": row["decision"]["action"], "pricing_changed": row["pricingChanged"], "rules": rules, "policy_fingerprint": row["policyFingerprint"]})
    return output


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def save_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main():
    repository = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=repository / "docs/data/seed268")
    parser.add_argument("--workers", type=int, default=1, help="Parallel workers for independent run aggregation (default: 1)")
    args = parser.parse_args()
    archive = Archive(args.source)
    require(not args.output.resolve().is_relative_to(archive.root), "Output must be outside the source archive")
    paper = json.loads((repository / "docs/data/manuscript-demonstration.json").read_text(encoding="utf-8"))
    reported = {row["id"]: row for row in paper["configurations"]}
    daily, summaries, provenance, comparisons, display_notes = [], [], {}, [], []
    joint_inputs = None
    require(args.workers > 0, "Worker count must be positive")
    arguments = [(archive.root, condition, relative) for condition, relative in RUNS.items()]
    if args.workers == 1:
        exports = [export_worker(argument) for argument in arguments]
    else:
        with ProcessPoolExecutor(max_workers=args.workers) as pool:
            exports = list(pool.map(export_worker, arguments))
    for (condition, relative), (run_result, source_files) in zip(RUNS.items(), exports):
        rows, summary, metadata, cohort, routes, trip_digests = run_result
        archive.files.update(source_files)
        daily.extend(rows)
        summaries.append(summary)
        provenance[condition] = metadata
        if condition == "Joint":
            joint_inputs = (archive.root / relative / "morning", cohort, routes, trip_digests)
        expected = reported[condition]
        actual = [summary["mean_total_car_person_hours"], summary["mean_mean_target_car_min"], summary["mean_work_late_percent"]]
        reference = [expected["outcomes"]["total_car_person_hours_per_day"], expected["outcomes"]["target_car_minutes_per_trip"], expected["outcomes"]["work_late_percent"]]
        rounded = [round(actual[0]), round(actual[1], 1), round(actual[2], 1)]
        exact_display_match = rounded == reference
        if not exact_display_match:
            require(condition == "Joint" and rounded[0] == reference[0] and rounded[2] == reference[2] and reference[1] == 16.3 and abs(actual[1] - 16.25) < 0.0001,
                    f"Unexpected outcome mismatch for {condition}: {actual} vs {reference}")
            display_notes.append({"condition": condition, "field": "mean_target_car_min", "source_window_mean": actual[1], "manuscript_display": reference[1], "direct_round_to_one_decimal": rounded[1], "note": "The source mean is just below the 16.25-minute rounding boundary. The manuscript displays 16.3; its original rounding pipeline has not been identified. Preserve source precision in exports and retain the manuscript display in narrative tables."})
        for source_key, paper_key in [("mean_overall_route_switch_percent", "overall_route"), ("mean_multiple_route_switch_percent", "multiple_route"), ("mean_departure_adjustment_percent", "departure")]:
            value = summary[source_key]
            require((round(value, 2) if value is not None else None) == expected["choice_change_percent"][paper_key], f"Behavior mismatch: {condition}, {source_key}, {value}")
        require(summary["api_calls"] == expected["api_calls"] and summary["tokens"] == expected["tokens"], f"Workload mismatch: {condition}")
        comparisons.append({"condition": condition, "outcome_values_match_direct_manuscript_rounding": exact_display_match, "behavior_values_match_manuscript_display_precision": True, "api_calls_and_tokens_match": True})
    require(len({m["cohort_identity_sha256"] for m in provenance.values()}) == 1, "Cohort differs across runs")
    require(len({m["canonical_candidate_routes_sha256"] for m in provenance.values()}) == 1, "Candidate routes differ across runs")
    audit = audit_joint(archive, *joint_inputs)
    schedule = policy_schedule(archive, "Manager", RUNS["Manager"]) + policy_schedule(archive, "Joint", RUNS["Joint"])
    audit["policy_rules_checked_for_nonnegative_finite_amounts_distinct_links_and_full_day_coverage"] = True
    # Verify that policy link IDs are legal network links, not merely syntactically valid IDs.
    import xml.etree.ElementTree as ET
    network_paths = sorted((archive.root / RUNS["Joint"]).glob('*output_network.xml.*'))
    if network_paths:
        network_path = archive.track(network_paths[0])
        if network_path.suffix == ".zst":
            try:
                from compression import zstd
            except ImportError:
                import zstandard as zstd
            opener = zstd.open
        else:
            require(network_path.suffix == ".gz", "Unsupported archived network compression")
            opener = gzip.open
        with opener(network_path, "rb") as stream:
            network = ET.parse(stream)
        car_links = {r.attrib["id"] for r in network.findall('.//link') if 'car' in r.attrib.get('modes', 'car').split(',')}
        require(all(rule["link_id"] in car_links for row in schedule for rule in row["rules"]), "Policy references an illegal car link")
        audit["policy_link_ids_checked_against_archived_network"] = True
    else:
        audit["policy_link_ids_checked_against_archived_network"] = False
    by_id = {r["condition"]: r for r in summaries}
    relative = {
        "traveler_mean_daily_car_time_per_person_vs_native": 100 * (by_id["Traveler"]["mean_total_car_person_hours"] / by_id["Native"]["mean_total_car_person_hours"] - 1),
        "joint_target_car_time_vs_traveler": 100 * (by_id["Joint"]["mean_mean_target_car_min"] / by_id["Traveler"]["mean_mean_target_car_min"] - 1),
        "joint_mean_daily_car_time_per_person_vs_traveler": 100 * (by_id["Joint"]["mean_total_car_person_hours"] / by_id["Traveler"]["mean_total_car_person_hours"] - 1),
    }
    require(all(round(value, 2) == paper["reported_relative_changes_percent"][key] for key, value in relative.items()), "Relative differences do not match the manuscript")
    print("Hashing source records and checking source stability", flush=True)
    source_files = archive.inventory()
    args.output.mkdir(parents=True, exist_ok=True)
    save_csv(args.output / "daily-metrics.csv", daily)
    save_csv(args.output / "run-summary.csv", summaries)
    save_json(args.output / "execution-audit.json", audit)
    save_json(args.output / "policy-schedule.json", schedule)
    save_json(args.output / "validation.json", {"daily_rows": len(daily), "unique_configuration_day_keys": len({(r["condition"], r["day"]) for r in daily}), "window": [0, 99], "configuration_checks": comparisons, "manuscript_display_notes": display_notes, "relative_changes_percent_from_unrounded_window_means": relative, "cohort_identity_matches": True, "canonical_candidate_routes_match": True, "source_files_unchanged_during_export": True, "semantic_accuracy_validated": False})
    output_files = {p.name: {"bytes": p.stat().st_size, "sha256": digest(p)} for p in sorted(args.output.iterdir()) if p.name in {"daily-metrics.csv", "run-summary.csv", "execution-audit.json", "policy-schedule.json", "validation.json"}}
    save_json(args.output / "run-metadata.json", {"artifact_kind": "archive_derived_100day_documentation_export", "source_archive_name": archive.root.name, "source_paths_are_relative_to_archive": True, "export_script_repository_relative": "scripts/export_arxiv_documentation_data.py", "export_script_sha256": digest(Path(__file__)), "runs": provenance, "source_files": source_files, "exported_files": output_files, "source_git_commit_note": "Historical runtime identities are recorded by archived JAR hashes; no release-to-source mapping is inferred.", "source_scope_note": "The external archive contains raw records. Only derived aggregate CSVs, audit summaries, policy rules and sanitized provenance are copied into docs; raw personal contexts and model responses remain in the source archive."})
    print(f"Exported {len(daily)} daily rows, five run summaries and a full-window Joint audit to {args.output}", flush=True)


if __name__ == "__main__":
    main()
