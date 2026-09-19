import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("engineering_check", Path(__file__).with_name("check-engineering-consistency.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ConsistencyTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.write("README.md", "# 测试仓库\n")
        self.write("docs/adr/001-first.md", "# 第一个决策\n")
        self.write(checker.MIGRATIONS + "/V1__first.sql", "SELECT 1;\n")
        self.commit()
        self.git("branch", "baseline")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True).stdout

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def result(self):
        return checker.check(self.root, "baseline")

    def codes(self):
        return [item["code"] for item in self.result()["issues"]]

    def test_clean_baseline_and_untracked_valid_documents(self):
        self.assertEqual(self.result()["issues"], [])
        self.write("docs/adr/002-next.md", "[已有决策](001-first.md#标题)\n")
        self.write(checker.MIGRATIONS + "/V2__next.sql", "SELECT 2;\n")
        result = self.result()
        self.assertEqual(result["issues"], [])
        self.assertEqual(result["counts"], {"adr": 2, "migrations": 2, "changedMarkdown": 1})

    def test_untracked_duplicate_and_leading_zero_are_detected(self):
        self.write("docs/adr/1-another.md", "# 重复\n")
        self.write(checker.MIGRATIONS + "/V01__another.sql", "SELECT 2;\n")
        self.assertEqual(set(self.codes()), {"ADR_DUPLICATE", "MIGRATION_DUPLICATE"})
        self.assertTrue(all(item["severity"] == "error" for item in self.result()["issues"]))

    def test_historical_adr_is_warning_but_new_third_file_is_error(self):
        self.write("docs/adr/001-history.md", "# 历史重复\n")
        self.commit()
        self.git("branch", "-f", "baseline", "HEAD")
        self.assertEqual(self.result()["issues"][0]["severity"], "warning")
        self.write("docs/adr/001-new.md", "# 新重复\n")
        self.assertEqual(self.result()["issues"][0]["severity"], "error")

    def test_historical_migration_collision_is_not_exempted(self):
        self.write(checker.MIGRATIONS + "/V1__history.sql", "SELECT 2;\n")
        self.commit()
        self.git("branch", "-f", "baseline", "HEAD")
        issue = self.result()["issues"][0]
        self.assertEqual((issue["severity"], issue["code"]), ("error", "MIGRATION_DUPLICATE"))

    def test_existing_migration_cannot_be_edited_removed_or_renamed(self):
        path = checker.MIGRATIONS + "/V1__first.sql"
        self.write(path, "SELECT 'private-sentinel';\n")
        self.assertIn("MIGRATION_MODIFIED", self.codes())
        self.assertNotIn("private-sentinel", json.dumps(self.result()))
        (self.root / path).rename(self.root / checker.MIGRATIONS / "V3__renamed.sql")
        self.assertIn("MIGRATION_REMOVED", self.codes())

    def test_parallel_branch_requires_sync_then_detects_semantic_collision(self):
        self.git("checkout", "-qb", "feature")
        self.write("docs/adr/002-feature.md", "# 功能\n")
        self.commit()
        self.git("checkout", "-q", "main")
        self.write("docs/adr/002-other.md", "# 其他任务\n")
        self.commit()
        self.git("branch", "-f", "baseline", "HEAD")
        self.git("checkout", "-q", "feature")
        self.assertEqual(self.codes(), ["BASE_NOT_INCLUDED"])
        self.git("merge", "-qm", "merge fixture", "main")
        self.assertIn("ADR_DUPLICATE", self.codes())

    def test_markdown_relative_reference_image_space_and_code_scope(self):
        self.write("docs/a b.md", "# 目标\n")
        self.write("docs/pic (1).svg", "<svg/>\n")
        self.write("docs/check.md", '''[空格](<a b.md>)
[转义](a%20b.md#标题)
![图片](pic%20(1).svg)
[引用]: a%20b.md "说明"
[根路径](/README.md)
[网络](https://example.invalid/missing)
[锚点](#unknown)
`[示例](missing-inline.md)`
```md
[示例](missing-fence.md)
```
~~~
[示例](missing-other-fence.md)
~~~
''')
        self.assertEqual(self.result()["issues"], [])
        self.write("docs/check.md", "[坏引用](absent.md)\n![坏图片](no.png)\n[ref]: lost.md\n")
        self.assertEqual(self.codes().count("LINK_MISSING"), 3)

    def test_unchanged_broken_link_is_not_claimed_as_new_failure(self):
        self.write("docs/legacy.md", "[旧链接](missing.md)\n")
        self.commit()
        self.git("branch", "-f", "baseline", "HEAD")
        self.assertEqual(self.result()["counts"]["changedMarkdown"], 0)
        self.assertEqual(self.result()["issues"], [])
        self.write("docs/legacy.md", "[旧链接](missing.md)\n新增一行\n")
        self.assertIn("LINK_MISSING", self.codes())

    def test_invalid_naming_and_outside_link(self):
        self.write("docs/adr/no-number.md", "[外部路径](../../../outside.md)\n")
        self.write(checker.MIGRATIONS + "/bad.sql", "SELECT 2;\n")
        self.assertEqual(set(self.codes()), {"ADR_NAME", "MIGRATION_NAME", "LINK_OUTSIDE_REPOSITORY"})

    def test_external_symlink_is_not_read(self):
        self.write("docs/unsafe.md", "temporary")
        (self.root / "docs/unsafe.md").unlink()
        (self.root / "docs/unsafe.md").symlink_to(Path(__file__).resolve())
        with self.assertRaises(checker.CheckUnavailable):
            self.result()

    def test_cli_json_and_exit_codes(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(checker.main(["--root", str(self.root), "--base", "baseline", "--json"]), 0)
        self.assertEqual(json.loads(output.getvalue())["status"], "passed")
        self.write("docs/adr/001-collision.md", "# 碰撞\n")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(checker.main(["--root", str(self.root), "--base", "baseline"]), 1)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(checker.main(["--root", str(self.root), "--base", "missing"]), 2)


if __name__ == "__main__":
    unittest.main()
