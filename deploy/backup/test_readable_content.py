import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location("readable_content", Path(__file__).with_name("export-readable-content.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class FakeWebDav:
    def __init__(self):
        self.files = {}
        self.uploads = []
        self.removals = []

    def ensure_dir(self, _path):
        pass

    def stat_size(self, path, missing_ok=False):
        return len(self.files[path]) if path in self.files else None

    def upload(self, source, remote, force=False):
        self.files[remote] = source.read_bytes()
        self.uploads.append(remote)

    def download(self, remote, target):
        target.write_bytes(self.files[remote])

    def delete_file(self, remote):
        del self.files[remote]
        self.removals.append(remote)


class ReadableContentTest(unittest.TestCase):
    def test_source_check_rejects_stale_local_replica_and_read_only_writer(self):
        with patch.dict(exporter.os.environ, {"WAO_EXPORT_PRIMARY": "linux1", "WAO_EXPORT_LOCAL_UUID": "local"}):
            with patch.object(exporter, "mysql", return_value=b"local\t0\t0\n"):
                with self.assertRaisesRegex(RuntimeError, "configured writer role"):
                    exporter.verify_source()
            with patch.object(exporter, "mysql", return_value=b"remote\t1\t1\n"):
                with self.assertRaisesRegex(RuntimeError, "not a writable"):
                    exporter.verify_source()
            with patch.object(exporter, "mysql", return_value=b"remote\t0\t0\n"):
                exporter.verify_source()

    def test_export_is_incremental_and_cleans_only_managed_stale_files(self):
        client = FakeWebDav()
        state = {"知识库与小记/小记/旧标题--d1.txt": b"old", "任务/项目/任务--t1.txt": b"task"}
        def content():
            return dict(state), set(), set(), {"知识库与小记": 1, "任务": 1}
        with patch.object(exporter, "wanted_content", content), \
             patch.object(exporter, "media_metadata", return_value=([], 0)):
            first = exporter.sync(client)
            self.assertEqual(first["uploaded"], 3)  # two records and readme
            previous_upload_count = len(client.uploads)
            second = exporter.sync(client)
            self.assertEqual(second["uploaded"], 0)
            self.assertEqual(len(client.uploads), previous_upload_count + 1)  # manifest only
            state.pop("知识库与小记/小记/旧标题--d1.txt")
            state["知识库与小记/小记/新标题--d1.txt"] = b"new"
            third = exporter.sync(client)
            self.assertEqual(third["uploaded"], 1)
            self.assertEqual(third["removed"], 1)
            self.assertEqual(client.removals, [exporter.ROOT + "/知识库与小记/小记/旧标题--d1.txt"])

    def test_refuses_to_overwrite_cloud_side_edits(self):
        client = FakeWebDav()
        state = {"知识库与小记/小记/文档--d1.txt": b"original", "任务/项目/任务--t1.txt": b"task"}
        with patch.object(exporter, "wanted_content", lambda: (dict(state), set(), set(), {"知识库与小记": 1, "任务": 1})), \
             patch.object(exporter, "media_metadata", return_value=([], 0)):
            exporter.sync(client)
            client.files[exporter.ROOT + "/知识库与小记/小记/文档--d1.txt"] = b"cloud edit"
            state["知识库与小记/小记/文档--d1.txt"] = b"source edit"
            with self.assertRaisesRegex(RuntimeError, "edited outside"):
                exporter.sync(client)

    def test_initial_resume_only_overwrites_uncommitted_files(self):
        client = FakeWebDav()
        path = exporter.ROOT + "/任务/项目/任务--t1.txt"
        client.files[path] = b"interrupted old version"
        state = {"知识库与小记/小记/文档--d1.txt": b"doc", "任务/项目/任务--t1.txt": b"new version"}
        with patch.object(exporter, "wanted_content", lambda: (dict(state), set(), set(), {"知识库与小记": 1, "任务": 1})), \
             patch.object(exporter, "media_metadata", return_value=([], 0)):
            with self.assertRaisesRegex(RuntimeError, "outside the manifest"):
                exporter.sync(client)
            exporter.sync(client, resume_initial=True)
            self.assertEqual(client.files[path], b"new version")
            with self.assertRaisesRegex(RuntimeError, "cannot be used"):
                exporter.sync(client, resume_initial=True)

    def test_legacy_blocks_and_references(self):
        self.assertIn("- 一项", exporter.legacy_blocks([{"p": "正文"}, {"ul": ["一项"]}]))
        images, files = exporter.references("/api/images/image1/raw", {"url": "/api/files/file1/download"})
        self.assertEqual(images, {"image1"})
        self.assertEqual(files, {"file1"})
        self.assertLessEqual(len(exporter.safe_name("很长的中文标题" * 30).encode("utf-8")), 140)


if __name__ == "__main__":
    unittest.main()
