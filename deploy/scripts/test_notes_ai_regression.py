"""报告必须来自本轮，缺少或损坏的结果不能复用旧的成功证据。"""
import importlib.util
from pathlib import Path
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('notes_ai_regression', Path(__file__).with_name('notes-ai-regression.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class ReportEvidenceTest(unittest.TestCase):
    def test_missing_stale_and_malformed_reports_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                tool.inspect_junit(root, 0)
            for name in tool.SUITES:
                (root / ('TEST-' + name + '.xml')).write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(len(tool.SUITES), len(tool.inspect_junit(root, 0)))
            with self.assertRaises(ValueError):
                tool.inspect_junit(root, time.time() + 60)
            (root / ('TEST-' + tool.SUITES[0] + '.xml')).write_text('<broken')
            with self.assertRaises(ET.ParseError):
                tool.inspect_junit(root, 0)


if __name__ == '__main__':
    unittest.main()
