import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("compare_performance", Path(__file__).with_name("compare-performance.py"))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


def report(times=(10, 20, 30)):
    return {"schemaVersion": 1, "measurement": "local-service-and-database-roundtrip", "warmups": 2,
            "repetitions": 3, "javaVersion": "17", "workingTree": "clean", "dataShape": {"doc": 2},
            "scenarios": [{"id": "document-read", "outputsStable": True, "medianMs": 999,
                           "samples": [{"elapsedMs": value, "queries": 2, "returnedRows": 1, "resultBytes": 30}
                                       for value in times]}]}


class ComparisonTests(unittest.TestCase):
    def load(self, payload):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            return comparison.load_report(path)

    def test_median_is_recomputed_and_change_direction_is_explicit(self):
        before = self.load(report())
        after = self.load(report((5, 10, 15)))
        result = comparison.compare(before, after)
        self.assertEqual(result["rows"][0]["beforeMs"], 20)
        self.assertEqual(result["rows"][0]["afterMs"], 10)
        self.assertEqual(result["rows"][0]["changePercent"], -50)
        self.assertTrue(result["warnings"])

    def test_zero_baseline_does_not_divide_by_zero(self):
        result = comparison.compare(self.load(report((0, 0, 0))), self.load(report()))
        self.assertIsNone(result["rows"][0]["changePercent"])

    def test_dirty_different_data_and_unstable_outputs_are_flagged(self):
        before = self.load(report())
        payload = report()
        payload.update({"dataShape": {"doc": 3}, "workingTree": "dirty", "warmups": 1})
        payload["scenarios"][0]["outputsStable"] = False
        result = comparison.compare(before, self.load(payload))
        warnings = " ".join(result["warnings"])
        for term in ("数据概况", "工作区", "预热", "输出发生变化"):
            self.assertIn(term, warnings)

    def test_missing_scenarios_are_reported_instead_of_implicitly_passing(self):
        before = self.load(report())
        payload = report()
        payload["scenarios"][0]["id"] = "dashboard-summary"
        result = comparison.compare(before, self.load(payload))
        self.assertEqual(result["rows"], [])
        self.assertEqual(result["onlyBefore"], ["document-read"])
        self.assertEqual(result["onlyAfter"], ["dashboard-summary"])

    def test_rejects_missing_nonfinite_negative_duplicate_and_small_samples(self):
        for value in (float("nan"), float("inf"), -1, True, "1"):
            payload = report()
            payload["scenarios"][0]["samples"][0]["elapsedMs"] = value
            with self.assertRaises(ValueError):
                self.load(payload)
        for payload in (report((1, 2)), {**report(), "schemaVersion": 2}, {**report(), "scenarios": []}):
            with self.assertRaises(ValueError):
                self.load(payload)
        payload = report()
        payload["scenarios"].append(copy.deepcopy(payload["scenarios"][0]))
        with self.assertRaises(ValueError):
            self.load(payload)


if __name__ == "__main__":
    unittest.main()
