import io
from hashlib import sha256
import unittest
from unittest.mock import MagicMock

from PIL import Image

from migrate_assets import lossless_webp
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
            "content_type": "image/jpeg", "object_content_type": "image/jpeg",
            "object_byte_size": len(self.data), "object_hash": self.hash,
            "original_archive_key": None,
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

    def test_png_requires_verified_original_archive(self):
        self.cursor.fetchone.return_value["content_type"] = "image/png"
        self.cursor.fetchone.return_value["object_content_type"] = "image/png"
        self.cursor.fetchone.return_value["original_archive_key"] = (
            "WAO-backups/originals/png/ab/" + self.hash + ".png.gpg")
        with self.assertRaisesRegex(RuntimeError, "independently verified"):
            clear_one(self.db, self.s3, "private", self.manifest, "image_blob", "img1")
        self.assertEqual(self.cursor.execute.call_count, 1)

    def test_pixel_exact_webp_and_verified_archive_allow_mysql_reclaim(self):
        image = Image.new("RGBA", (300, 300), (20, 60, 100, 180))
        source = io.BytesIO()
        image.save(source, format="PNG")
        original = source.getvalue()
        webp = lossless_webp(original)
        self.assertIsNotNone(webp)
        source_hash = sha256(original).hexdigest()
        webp_hash = sha256(webp).hexdigest()
        key = "images/img1/" + webp_hash
        row = self.cursor.fetchone.return_value
        row.update({"object_key": key, "byte_size": len(original),
                    "content_hash": source_hash, "content": original,
                    "content_type": "image/png", "object_content_type": "image/webp",
                    "object_byte_size": len(webp), "object_hash": webp_hash,
                    "original_archive_key": "WAO-backups/originals/png/" + source_hash[:2]
                    + "/" + source_hash + ".png.gpg"})
        self.s3.get_object.return_value = {"Body": io.BytesIO(webp)}
        backup = {key: {"bytes": len(webp), "sha256": webp_hash}}
        verified = []
        self.assertEqual(clear_one(self.db, self.s3, "private", backup, "image_blob", "img1",
                                   lambda archive_key, body: verified.append((archive_key, body))),
                         len(original))
        self.assertEqual(verified[0][1], original)
        self.assertEqual(self.cursor.execute.call_count, 2)


if __name__ == "__main__":
    unittest.main()
