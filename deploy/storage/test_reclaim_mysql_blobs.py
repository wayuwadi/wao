import io
from hashlib import sha256
import unittest
from unittest.mock import MagicMock

from reclaim_mysql_blobs import clear_one


class ReclaimSafetyTest(unittest.TestCase):
    def setUp(self):
        self.data = b"original image bytes"
        self.hash = sha256(self.data).hexdigest()
        self.key = "images/img1/" + self.hash
        self.db = MagicMock()
        self.cursor = self.db.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.return_value = {
            "object_key": self.key, "byte_size": len(self.data),
            "content_hash": self.hash, "content": self.data,
        }
        self.cursor.rowcount = 1
        self.s3 = MagicMock()
        self.s3.get_object.return_value = {"Body": io.BytesIO(self.data)}
        self.manifest = {self.key: {"bytes": len(self.data), "sha256": self.hash}}

    def test_updates_only_after_object_and_backup_match(self):
        self.assertEqual(clear_one(self.db, self.s3, "private", self.manifest, "image_blob", "img1"),
                         len(self.data))
        self.assertEqual(self.cursor.execute.call_count, 2)
        self.assertIn("SET content=NULL", self.cursor.execute.call_args_list[1].args[0])

    def test_missing_backup_never_clears_mysql(self):
        with self.assertRaisesRegex(RuntimeError, "absent from"):
            clear_one(self.db, self.s3, "private", {}, "image_blob", "img1")
        self.assertEqual(self.cursor.execute.call_count, 1)

    def test_corrupt_object_never_clears_mysql(self):
        self.s3.get_object.return_value = {"Body": io.BytesIO(b"wrong")}
        with self.assertRaisesRegex(RuntimeError, "differs"):
            clear_one(self.db, self.s3, "private", self.manifest, "image_blob", "img1")
        self.assertEqual(self.cursor.execute.call_count, 1)


if __name__ == "__main__":
    unittest.main()
