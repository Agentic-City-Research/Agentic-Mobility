import gzip
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "analyze_morning_choices.py"


class AnalyzeMorningChoicesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.morning = Path(self.temporary.name)

    def document(self, name, data):
        (self.morning / name).write_text(json.dumps(data), encoding="utf-8")

    def stream(self, name, records):
        with gzip.open(self.morning / (name + ".jsonl.gz"), "wt", encoding="utf-8") as stream:
            for record in records:
                stream.write(json.dumps(record) + "\n")

    def completed_manifest(self, llm_persons):
        self.document("status.json", {"complete": True, "daysCompleted": 2})
        self.document("manifest.json", {"firstIteration": 0, "lastIteration": 1,
                                       "llmPersons": llm_persons,
                                       "controlledTrips": llm_persons,
                                       "travelerSemanticReview": {"enabled": False}})

    def run_script(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--morning", str(self.morning)],
                              capture_output=True, text=True)

    @staticmethod
    def transition(before, after):
        return {"day": 1, "switchedFromLastChoice": True,
                "previousExperience": {"arrivalSeconds": 10, "lateSeconds": 0, "stuck": False},
                "previousForecasts": [{"routeId": "c0", "estimatedMonetaryCost": before, "expectedCarSeconds": 20}],
                "currentForecasts": [{"routeId": "c0", "estimatedMonetaryCost": after, "expectedCarSeconds": 20}]}

    def test_unknown_cost_is_not_zero_and_availability_changes_are_distinct(self):
        self.completed_manifest(0)
        self.stream("choice-transitions", [self.transition(None, None), self.transition(None, 0),
                                           self.transition(0, None), self.transition(0, 1)])
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(result.stdout)
        groups = output["conditional_switching"]
        self.assertEqual(groups["all_with_previous_choice"]["decisions"], 4)
        self.assertEqual(groups["after_cost_information_change"]["decisions"], 3)
        self.assertEqual(groups["after_cost_information_availability_change"]["decisions"], 2)
        self.assertEqual(groups["after_known_cost_value_change"]["decisions"], 1)
        self.assertEqual(output["total_llm_client_calls"], 0)

    def test_missing_status_or_required_llm_streams_fail(self):
        self.stream("choice-transitions", [self.transition(None, None)])
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing status.json", result.stderr)
        self.completed_manifest(1)
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing required record streams", result.stderr)
        self.assertIn("attempts", result.stderr)
        self.document("status.json", {"complete": False, "daysCompleted": 1})
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not complete", result.stderr)

    def test_missing_usage_makes_total_unknown(self):
        self.completed_manifest(1)
        self.stream("choice-transitions", [self.transition(None, None)] * 2)
        self.stream("llm-results", [{"repairCount": 0, "elapsedMillis": 1,
                                     "candidateOrder": ["c0"], "routeId": "c0"}] * 2)
        self.stream("attempts", [{"failureKind": None, "usage": None}] * 2)
        self.stream("memory-updates", [{"committed": True, "repairCount": 0,
                                        "memory": "facts", "elapsedMillis": 1}] * 2)
        self.stream("memory-attempts", [{"failureKind": None, "usage": None}] * 2)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual(output["total_llm_client_calls"], 4)
        self.assertIsNone(output["total_reported_tokens"])

    def test_empty_streams_do_not_pass_as_zero_calls_for_a_complete_llm_run(self):
        self.completed_manifest(1)
        self.stream("choice-transitions", [self.transition(None, None)] * 2)
        for name in ("llm-results", "attempts", "memory-updates", "memory-attempts"):
            self.stream(name, [])
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Incomplete traveler records", result.stderr)

    def test_enabled_review_requires_its_record_stream(self):
        self.completed_manifest(1)
        self.document("manifest.json", {"firstIteration": 0, "lastIteration": 1,
                                       "llmPersons": 1, "controlledTrips": 1,
                                       "travelerSemanticReview": {"enabled": True}})
        for name in ("choice-transitions", "llm-results", "attempts", "memory-updates", "memory-attempts"):
            self.stream(name, [])
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("semantic-reviews", result.stderr)

    def test_enabled_review_rejects_an_empty_review_stream(self):
        self.completed_manifest(1)
        self.document("manifest.json", {"firstIteration": 0, "lastIteration": 1,
                                       "llmPersons": 1, "controlledTrips": 1,
                                       "travelerSemanticReview": {"enabled": True}})
        self.stream("choice-transitions", [self.transition(None, None)] * 2)
        self.stream("llm-results", [{"repairCount": 0, "elapsedMillis": 1,
                                     "candidateOrder": ["c0"], "routeId": "c0"}] * 2)
        self.stream("attempts", [{"failureKind": None, "usage": None}] * 2)
        self.stream("memory-updates", [{"committed": True, "repairCount": 0,
                                        "memory": "facts", "elapsedMillis": 1}] * 2)
        self.stream("memory-attempts", [{"failureKind": None, "usage": None}] * 2)
        self.stream("semantic-reviews", [])
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Incomplete semantic-review records", result.stderr)


if __name__ == "__main__":
    unittest.main()
