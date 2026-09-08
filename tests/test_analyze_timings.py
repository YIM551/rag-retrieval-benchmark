import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from analyze_timings import analyze, load_records, summary


def row(i=1, **overrides):
    return {"schema_version": 1, "run_id": "synthetic-run", "request_id": str(i),
            "query_id": "synthetic-q", "actual_mode": "SEQUENTIAL",
            "corpus_id": "synthetic-corpus", "config_id": "synthetic-config",
            "cache_state": "disabled", "timing_scope": "server_total",
            "phase": "measurement", "outcome": "success", "duration_ms": 100,
            **overrides}


class TimingTests(unittest.TestCase):
    def test_nearest_rank_known_sample(self):
        self.assertEqual(summary(list(range(1, 21))), {"n": 20, "mean_ms": 10.5, "p95_ms": 19})

    def test_warmup_failure_and_timeout_are_not_fast_successes(self):
        result = analyze([row(1, phase="warmup", duration_ms=9999), row(2),
                          row(3, outcome="error", duration_ms=1),
                          row(4, outcome="timeout", duration_ms=1000)])
        run = result["runs"][0]
        self.assertEqual((run["attempts"], run["warmup_excluded"], run["failure_rate"]), (3, 1, 2/3))
        self.assertEqual(run["success_latency"], {"n": 1, "mean_ms": 100, "p95_ms": 100})
        self.assertIsNone(result["throughput_rps"])

    def test_all_failed_has_no_success_percentile(self):
        result = analyze([row(outcome="error")])["runs"][0]
        self.assertIsNone(result["success_latency"]["p95_ms"])

    def test_warmup_only_has_no_failure_rate(self):
        self.assertIsNone(analyze([row(phase="warmup")])["runs"][0]["failure_rate"])

    def test_duplicate_request_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            analyze([row(), row()])

    def test_mixed_cohorts_rejected(self):
        for field, value in [("actual_mode", "PARALLEL_SYNC"), ("config_id", "other"),
                             ("corpus_id", "other"), ("cache_state", "warm"),
                             ("timing_scope", "client_e2e")]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "mixed"):
                analyze([row(), row(2, **{field: value})])

    def test_requested_mode_is_not_server_evidence(self):
        with self.assertRaisesRegex(ValueError, "expected"):
            analyze([row()], expected_mode="PARALLEL_SYNC")

    def test_invalid_durations_rejected(self):
        for value in [float("nan"), float("inf"), -1, True, "100", 10**400]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                analyze([row(duration_ms=value)])

    def test_stage_boundaries_and_partial_coverage(self):
        run = analyze([row(actual_mode="PARALLEL_SYNC", stage_ms={"retrieval": 80, "dense": 70, "sparse": 60}),
                       row(2, actual_mode="PARALLEL_SYNC")])["runs"][0]
        # Overlapping dense/sparse spans must not be summed into total time.
        self.assertEqual(run["success_stage_latency"]["dense"]["n"], 1)
        for overrides in [{"stage_ms": {"llm": 101}}, {"stage_ms": {"secret": 1}},
                          {"timing_scope": "client_e2e", "stage_ms": {"llm": 10}}]:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                analyze([row(**overrides)])

    def test_runs_remain_separate(self):
        self.assertEqual(len(analyze([row(), row(run_id="other")])["runs"]), 2)

    def test_schema_and_unknown_fields_fail(self):
        for overrides in [{"schema_version": True}, {"query_id": ""}, {"prompt": "private"}]:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                analyze([row(**overrides)])

    def test_jsonl_and_malformed_line_number(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "log.jsonl"
            path.write_text(json.dumps(row()) + "\n\n", encoding="utf-8")
            self.assertEqual(len(load_records(path)), 1)
            path.write_text(json.dumps(row()) + "\nNOT JSON", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "line 2"):
                load_records(path)
            path.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "no timing"):
                load_records(path)

    def test_cli_rejects_mislabeled_mode_with_exit_two(self):
        root = Path(__file__).resolve().parents[1]
        result = subprocess.run([sys.executable, str(root / "scripts/analyze_timings.py"),
                                 str(root / "tests/fixtures/synthetic-timings.jsonl"),
                                 "--expected-mode", "PARALLEL_SYNC"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("actual_mode differs", result.stderr)


if __name__ == "__main__":
    unittest.main()
