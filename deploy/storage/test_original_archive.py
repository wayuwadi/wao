from hashlib import sha256
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

import archive_original_png as archive


class FakeWebDav:
    def __init__(self):
        self.objects = {}

    def stat_size(self, key, missing_ok=False):
        return len(self.objects[key]) if key in self.objects else None

    def ensure_dir(self, path):
        pass

    def upload(self, path, key):
        self.objects[key] = path.read_bytes()

    def download(self, key, path):
        path.write_bytes(self.objects[key])


class OriginalArchiveTest(unittest.TestCase):
    def setUp(self):
        self.data = b"original PNG source"
        self.hash = sha256(self.data).hexdigest()
        self.db = MagicMock()
        self.cursor = self.db.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.return_value = {
            "id": "img1", "content_type": "image/png", "byte_size": len(self.data),
            "content_hash": None, "content": self.data, "object_key": None,
            "object_content_type": None, "original_archive_key": None,
        }
        self.cursor.rowcount = 1
        self.webdav = FakeWebDav()

    def test_archive_verifies_remote_before_marking_row(self):
        def fake_crypt(data, _passphrase, decrypt=False):
            return data[10:] if decrypt else b"encrypted:" + data

        with patch.object(archive, "crypt", side_effect=fake_crypt):
            result = archive.archive_one(self.db, self.webdav, None, "private", Path("unused"),
                                         "WAO-backups", "img1")
        self.assertEqual(result, "archived")
        self.assertEqual(len(self.webdav.objects), 1)
        self.assertEqual(self.cursor.execute.call_count, 2)
        self.assertIn("original_archive_key", self.cursor.execute.call_args_list[1].args[0])

    def test_wrong_remote_ciphertext_cannot_mark_row(self):
        key = archive.archive_key("WAO-backups", self.hash)
        self.webdav.objects[key] = b"bad archive"

        def fake_crypt(data, _passphrase, decrypt=False):
            return b"wrong" if decrypt else b"encrypted:" + data

        with patch.object(archive, "crypt", side_effect=fake_crypt):
            with self.assertRaisesRegex(RuntimeError, "differs"):
                archive.archive_one(self.db, self.webdav, None, "private", Path("unused"),
                                    "WAO-backups", "img1")
        self.assertEqual(self.cursor.execute.call_count, 1)


if __name__ == "__main__":
    unittest.main()
