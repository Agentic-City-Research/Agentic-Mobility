"""Read-only descriptive analysis of choice changes; no behavioral or causal assumptions."""
import argparse
import gzip
import json
from collections import Counter, defaultdict
from pathlib import Path


def rows(path):
    if not path.exists():
        return
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def resolve(folder, name):
    compressed = folder / (name + ".jsonl.gz")
    return compressed if compressed.exists() else folder / (name + ".jsonl")


def validate_input(folder):
    """Require completion and enough provenance to distinguish missing logs from zero calls."""
    for name in ("status.json", "manifest.json"):
        if not (folder / name).is_file():
            raise ValueError(f"Missing {name}; cannot establish completion or enabled roles")
    status = json.loads((folder / "status.json").read_text(encoding="utf-8-sig"))
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8-sig"))
    if status.get("complete") is not True:
        raise ValueError("Run is not complete; status.json must contain complete=true")
    if not isinstance(manifest.get("llmPersons"), int) or manifest["llmPersons"] < 0:
        raise ValueError("manifest.json does not identify the number of LLM travelers")
    if manifest["llmPersons"] and (not isinstance(manifest.get("controlledTrips"), int) or manifest["controlledTrips"] < 1):
        raise ValueError("manifest.json does not identify the controlled LLM target trips")
    first, last = manifest.get("firstIteration"), manifest.get("lastIteration")
    if not isinstance(first, int) or not isinstance(last, int) or last < first or status.get("daysCompleted") != last - first + 1:
        raise ValueError("Completed days do not match the manifest's iteration window")
    # Runs preceding the periodic-reflection protocol reflected daily.
    interval = manifest.get("travelerReflectionInterval", 1)
    if type(interval) is not int or interval not in (1, 5):
        raise ValueError("Invalid travelerReflectionInterval in manifest")
    reflection_days = (last - first + 1) // interval
    required = ["choice-transitions"]
    if manifest["llmPersons"]:
        required += ["llm-results", "attempts"]
        if reflection_days:
            required += ["memory-updates", "memory-attempts"]
        review = manifest.get("travelerSemanticReview")
        if not isinstance(review, dict) or not isinstance(review.get("enabled"), bool):
            raise ValueError("Manifest does not establish whether traveler semantic review was enabled")
        if review["enabled"]:
            required.append("semantic-reviews")
    missing = [name for name in required if not resolve(folder, name).is_file()]
    if missing:
        raise ValueError("Missing required record streams: " + ", ".join(missing))
    return manifest


def analyze(folder):
    manifest = validate_input(folder)
    groups = defaultdict(Counter)
    positions = Counter()
    by_day = defaultdict(Counter)
    transitions = 0
    for row in rows(resolve(folder, "choice-transitions")):
        transitions += 1
        switched = row["switchedFromLastChoice"]
        if switched is None:
            continue
        previous = row["previousExperience"]
        labels = ["all_with_previous_choice"]
        if previous["arrivalSeconds"] is None:
            labels.append("after_unfinished_trip")
        elif previous["lateSeconds"] is not None:
            labels.append("after_late_trip" if previous["lateSeconds"] > 0 else "after_on_time_trip")
        if previous["stuck"]:
            labels.append("after_stuck_trip")
        current = {x["routeId"]: x for x in row["currentForecasts"]}
        past = {x["routeId"]: x for x in (row["previousForecasts"] or [])}
        shared = current.keys() & past.keys()
        availability_change = any((current[k]["estimatedMonetaryCost"] is None) !=
                                  (past[k]["estimatedMonetaryCost"] is None) for k in shared)
        value_change = any(current[k]["estimatedMonetaryCost"] is not None and
                           past[k]["estimatedMonetaryCost"] is not None and
                           abs(current[k]["estimatedMonetaryCost"] - past[k]["estimatedMonetaryCost"]) > 1e-9
                           for k in shared)
        if availability_change or value_change:
            labels.append("after_cost_information_change")
        if availability_change:
            labels.append("after_cost_information_availability_change")
        if value_change:
            labels.append("after_known_cost_value_change")
        if any(abs(current[k]["expectedCarSeconds"] - past[k]["expectedCarSeconds"]) > 1.0 for k in shared):
            labels.append("after_driving_time_prediction_change_over_1s")
        for label in labels:
            groups[label]["decisions"] += 1
            groups[label]["switches"] += bool(switched)
        by_day[row["day"]]["decisions_with_previous"] += 1
        by_day[row["day"]]["switches"] += bool(switched)
    successful = repairs = 0
    latency_ms = 0.0
    for row in rows(resolve(folder, "llm-results")):
        successful += 1
        repairs += row["repairCount"]
        latency_ms += row["elapsedMillis"]
        order = row["candidateOrder"]
        positions[str(order.index(row["routeId"]) + 1)] += 1
    calls = failed_calls = invalid_outputs = missing_usage = 0
    prompt_tokens = completion_tokens = 0
    for row in rows(resolve(folder, "attempts")):
        calls += 1
        failed_calls += row.get("failureKind") == "call"
        invalid_outputs += row.get("failureKind") == "output"
        usage = row.get("usage")
        if usage is None:
            missing_usage += 1
        else:
            prompt_tokens += usage["promptTokens"]
            completion_tokens += usage["completionTokens"]
    memory_updates = memory_repairs = memory_chars = 0
    memory_latency = 0.0
    for row in rows(resolve(folder, "memory-updates")):
        if not row.get("committed", False):
            continue
        memory_updates += 1
        memory_repairs += row["repairCount"]
        memory_chars += len(row["memory"])
        memory_latency += row["elapsedMillis"]
    memory_calls = memory_failed = memory_invalid = memory_missing = 0
    memory_prompt_tokens = memory_completion_tokens = 0
    for row in rows(resolve(folder, "memory-attempts")):
        memory_calls += 1
        memory_failed += row.get("failureKind") == "call"
        memory_invalid += row.get("failureKind") == "output"
        usage = row.get("usage")
        if usage is None:
            memory_missing += 1
        else:
            memory_prompt_tokens += usage["promptTokens"]
            memory_completion_tokens += usage["completionTokens"]
    review_statuses = Counter()
    review_calls = review_missing = review_prompt = review_completion = 0
    for row in rows(resolve(folder, "semantic-reviews")):
        review_statuses[row.get("reviewStatus", "unknown")] += 1
        if not row.get("reviewCallMade", False):
            continue
        review_calls += 1
        usage = row.get("usage")
        if usage is None:
            review_missing += 1
        else:
            review_prompt += usage["promptTokens"]
            review_completion += usage["completionTokens"]
    if manifest["llmPersons"]:
        days = manifest["lastIteration"] - manifest["firstIteration"] + 1
        expected = manifest["controlledTrips"] * days
        expected_memories = manifest["controlledTrips"] * (days // manifest.get("travelerReflectionInterval", 1))
        if transitions != expected or successful != expected or memory_updates != expected_memories:
            raise ValueError(f"Incomplete traveler records: expected {expected} transitions and decisions and {expected_memories} committed memories; "
                             f"found {transitions}, {successful} and {memory_updates}")
        if calls < successful or memory_calls < memory_updates:
            raise ValueError("Attempt streams contain fewer calls than finalized traveler records")
        if manifest["travelerSemanticReview"]["enabled"] and sum(review_statuses.values()) != expected + expected_memories:
            raise ValueError("Incomplete semantic-review records for finalized choices and memories")
    return {
        "interpretation": "Descriptive association only; groups overlap, no causal or human-habit inference. Cost availability changes (unknown vs known) are distinct from changes between two known values; unknown is never treated as zero. Display-position totals must also be interpreted against available candidate counts and order conditions.",
        "input_scope": {"complete": True, "first_iteration": manifest["firstIteration"],
                        "last_iteration": manifest["lastIteration"], "llm_persons": manifest["llmPersons"]},
        "transitions": transitions,
        "conditional_switching": {
            k: dict(v, switch_fraction=v["switches"] / v["decisions"])
            for k, v in sorted(groups.items())
        },
        "daily_switching": {str(k): dict(v) for k, v in sorted(by_day.items())},
        "llm": {
            "successful_decisions": successful,
            "output_repairs": repairs,
            "client_calls": calls,
            "failed_calls": failed_calls,
            "invalid_outputs": invalid_outputs,
            "calls_without_reported_usage": missing_usage,
            "reported_prompt_tokens": prompt_tokens,
            "reported_completion_tokens": completion_tokens,
            "mean_total_decision_millis": latency_ms / successful if successful else None,
            "chosen_display_positions": dict(sorted(positions.items())),
        },
        "memory": {
            "committed_updates": memory_updates,
            "output_repairs_in_committed_updates": memory_repairs,
            "client_calls": memory_calls,
            "failed_calls": memory_failed,
            "invalid_outputs": memory_invalid,
            "calls_without_reported_usage": memory_missing,
            "reported_prompt_tokens": memory_prompt_tokens,
            "reported_completion_tokens": memory_completion_tokens,
            "mean_summary_characters": memory_chars / memory_updates if memory_updates else None,
            "mean_total_update_millis": memory_latency / memory_updates if memory_updates else None,
        },
        "semantic_review": {
            "client_calls": review_calls,
            "statuses": dict(sorted(review_statuses.items())),
            "calls_without_reported_usage": review_missing,
            "reported_prompt_tokens": review_prompt,
            "reported_completion_tokens": review_completion,
        },
        "total_llm_client_calls": calls + memory_calls + review_calls,
        "total_reported_tokens": (prompt_tokens + completion_tokens + memory_prompt_tokens + memory_completion_tokens + review_prompt + review_completion
                                  if missing_usage + memory_missing + review_missing == 0 else None),
        "call_scope": "Traveler choices, memories and optional semantic review; manager calls are recorded separately in api-usage.json.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--morning", required=True, type=Path, help="Completed experiment's morning directory")
    args = parser.parse_args()
    try:
        result = analyze(args.morning)
    except (ValueError, OSError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=False, indent=2))
