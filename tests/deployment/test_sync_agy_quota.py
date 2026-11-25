import importlib.util
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

SCRIPT_PATH = Path(__file__).resolve().parents[2] / "scripts" / "sync-agy-quota.py"
SPEC = importlib.util.spec_from_file_location("sync_agy_quota", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


SAMPLE_USAGE_JSON = json.dumps({
    "status": "SUCCESS",
    "command": {
        "name": "usage",
        "data": {
            "groups": [
                {
                    "name": "Gemini Models",
                    "buckets": [
                        {
                            "id": "gemini-weekly",
                            "name": "Weekly Limit Remaining",
                            "window": "weekly",
                            "remaining_fraction": 0.787266,
                            "reset_time": "2026-12-01T03:53:02Z",
                        },
                        {
                            "id": "gemini-5h",
                            "name": "Five Hour Limit Remaining",
                            "window": "5h",
                            "remaining_fraction": 0.91372,
                            "reset_time": "2026-11-25T07:28:05Z",
                        },
                    ],
                },
                {
                    "name": "Claude and GPT models",
                    "buckets": [
                        {
                            "id": "3p-weekly",
                            "name": "Weekly Limit Remaining",
                            "window": "weekly",
                            "remaining_fraction": 1,
                            "reset_time": "1970-01-01T00:00:00Z",
                        },
                    ],
                },
            ]
        },
    },
})


class SyncAgyQuotaTests(unittest.TestCase):
    def test_parses_usage_and_normalizes_epoch_zero(self) -> None:
        parsed = MODULE.parse_agy_usage(SAMPLE_USAGE_JSON, "macbook-pro", "Agy")
        self.assertEqual(parsed["source"], "macbook-pro")
        self.assertEqual(parsed["accountLabel"], "Agy")
        self.assertEqual(len(parsed["groups"]), 2)
        gemini_buckets = parsed["groups"][0]["buckets"]
        self.assertEqual(gemini_buckets[0]["remainingPercent"], 79)
        self.assertIsNotNone(gemini_buckets[0]["resetsAt"])
        self.assertEqual(gemini_buckets[1]["remainingPercent"], 91)
        claude_bucket = parsed["groups"][1]["buckets"][0]
        self.assertEqual(claude_bucket["remainingPercent"], 100)
        self.assertIsNone(claude_bucket["resetsAt"])

    def test_rejects_invalid_source(self) -> None:
        with self.assertRaises(ValueError):
            MODULE.parse_agy_usage(SAMPLE_USAGE_JSON, "bad source!", "Agy")

    def test_dry_run_exits_zero_without_token(self) -> None:
        with mock.patch.object(MODULE, "run_agy_usage", return_value=SAMPLE_USAGE_JSON):
            code = MODULE.main(["--dry-run", "--source", "mac"])
        self.assertEqual(code, 0)

    def test_missing_token_returns_non_zero(self) -> None:
        with mock.patch.object(MODULE, "run_agy_usage", return_value=SAMPLE_USAGE_JSON), \
             mock.patch.dict("os.environ", {"WAO_API_TOKEN": ""}, clear=False):
            code = MODULE.main(["--source", "mac"])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
