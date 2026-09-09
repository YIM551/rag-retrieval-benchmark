import copy
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("historical", ROOT / "scripts/reproduce_historical.py")
historical = importlib.util.module_from_spec(spec)
spec.loader.exec_module(historical)


class HistoricalReproductionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records = historical.read_records(ROOT / "data/recovered/timing-events.jsonl")

    def test_all_90_original_records_reproduce_report(self):
        result = historical.summarize(self.records)
        self.assertEqual(result["records"], 90)
        expected = {"SEQUENTIAL": (10722.833333333334, 16725.1, 5832.3),
                    "PARALLEL_OLD": (12425.833333333334, 22273.6, 5851.933333333333),
                    "PARALLEL_SYNC": (10402.766666666666, 13230.6, 4999.8)}
        for mode, (mean, p95, retrieval) in expected.items():
            group = result["modes"][mode]
            self.assertEqual(group["n"], 30)
            self.assertAlmostEqual(group["timings"]["totalMs"]["mean_ms"], mean)
            self.assertAlmostEqual(group["timings"]["totalMs"]["p95_ms"], p95)
            self.assertAlmostEqual(group["timings"]["retrievalMs"]["mean_ms"], retrieval)
        self.assertIsNone(result["error_rate"])
        self.assertIsNone(result["concurrent_users"])

    def test_linear_percentile_instead_of_nearest_rank(self):
        self.assertAlmostEqual(historical.percentile_linear([0, 10]), 9.5)
        self.assertEqual(historical.percentile_linear([4]), 4)

    def test_mode_override_and_mixed_config_are_rejected(self):
        record = copy.deepcopy(self.records[0])
        record["mode"] = "PARALLEL_OLD"
        with self.assertRaises(ValueError): historical.validate(record)
        record = copy.deepcopy(self.records[0])
        record["threadPoolSize"] += 1
        with self.assertRaises(ValueError): historical.summarize([self.records[0], record])

    def test_invalid_timings_and_pii_fields_are_rejected(self):
        for value in [True, -1, math.inf, math.nan, 10**400, "3"]:
            record = copy.deepcopy(self.records[0]); record["totalMs"] = value
            with self.assertRaises(ValueError): historical.validate(record)
        record = copy.deepcopy(self.records[0]); record["query"] = "sensitive text"
        with self.assertRaises(ValueError): historical.validate(record)

    def test_parallel_task_times_are_not_added_as_wall_clock(self):
        record = copy.deepcopy(self.records[0]); record["denseMs"] = record["totalMs"] * 2
        result = historical.summarize([record])
        self.assertEqual(result["modes"][record["mode"]]["timings"]["retrievalMs"]["mean_ms"], record["retrievalMs"])

    def test_parser_filters_non_timing_lines_and_rejects_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "input.log"
            p.write_text("irrelevant private log\n2025-12-10 INFO [RAG_TIMING] " + json.dumps(self.records[0]), encoding="utf-8")
            self.assertEqual(historical.read_records(p), [self.records[0]])
            p.write_text('[RAG_TIMING] {"mode":"SEQUENTIAL","mode":"PARALLEL_SYNC"}', encoding="utf-8")
            with self.assertRaises(ValueError): historical.read_records(p)

    def test_cli_roundtrip_and_invalid_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "summary.json"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/reproduce_historical.py"),
                                     str(ROOT / "data/recovered/timing-events.jsonl"), "--summary", str(out)], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(out.read_text())["records"], 90)
            invalid = Path(directory) / "invalid.jsonl"; invalid.write_text('{"private":"do-not-echo"}')
            result = subprocess.run([sys.executable, str(ROOT / "scripts/reproduce_historical.py"), str(invalid)], capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn(b"do-not-echo", result.stderr)


if __name__ == "__main__":
    unittest.main()
