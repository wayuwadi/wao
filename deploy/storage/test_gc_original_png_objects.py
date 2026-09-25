import io
from hashlib import sha256
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

from botocore.exceptions import ClientError
from PIL import Image

from gc_original_png_objects import gc_one
from migrate_assets import lossless_webp


class OriginalObjectGcTest(unittest.TestCase):
    def setUp(self):
        image = Image.new("RGB", (400, 400), (30, 80, 120))
        for y in range(0, 400, 15):
            for x in range(0, 400, 15):
                image.putpixel((x, y), (240, 70, 30))
        source = io.BytesIO()
        image.save(source, format="PNG")
        self.original = source.getvalue()
        self.webp = lossless_webp(self.original)
        self.assertIsNotNone(self.webp)
        original_hash = sha256(self.original).hexdigest()
        webp_hash = sha256(self.webp).hexdigest()
        self.original_key = "images/img1/" + original_hash
        self.webp_key = "images/img1/" + webp_hash
        self.archive_key = "WAO-backups/originals/png/" + original_hash[:2] + "/" + original_hash + ".png.gpg"
        self.row = {"id": "img1", "byte_size": len(self.original),
                    "content_hash": original_hash, "content": self.original,
                    "object_key": self.webp_key, "object_content_type": "image/webp",
                    "object_byte_size": len(self.webp), "object_hash": webp_hash,
                    "original_archive_key": self.archive_key}
        self.db = MagicMock()
        self.cursor = self.db.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.side_effect = [self.row,
                                            {"object_key": self.webp_key, "object_hash": webp_hash,
                                             "original_archive_key": self.archive_key},
                                            {"references": 0}]
        self.s3 = MagicMock()
        self.s3.get_object.side_effect = lambda Bucket, Key: {
            "Body": io.BytesIO(self.webp if Key == self.webp_key else self.original)}
        self.s3.head_object.side_effect = ClientError(
            {"Error": {"Code": "404"}, "ResponseMetadata": {"HTTPStatusCode": 404}},
            "HeadObject")
        self.backup = {self.webp_key: {"bytes": len(self.webp), "sha256": webp_hash}}

    def call_gc(self, backup=None):
        with patch("gc_original_png_objects.download_original", return_value=self.original):
            return gc_one(self.db, self.s3, "private", self.backup if backup is None else backup,
                          object(), Path("unused"), "WAO-backups", "img1", 7)

    def test_deletes_only_after_backup_archive_webp_and_references_match(self):
        self.assertEqual(self.call_gc(), "deleted")
        self.s3.delete_object.assert_called_once_with(Bucket="private", Key=self.original_key)
        self.db.commit.assert_called_once()

    def test_missing_backup_keeps_original_online(self):
        with self.assertRaisesRegex(RuntimeError, "absent from restored"):
            self.call_gc({})
        self.s3.delete_object.assert_not_called()

    def test_live_reference_keeps_original_online(self):
        self.cursor.fetchone.side_effect = [self.row,
                                            {"object_key": self.webp_key,
                                             "object_hash": self.row["object_hash"],
                                             "original_archive_key": self.archive_key},
                                            {"references": 1}]
        with self.assertRaisesRegex(RuntimeError, "still referenced"):
            self.call_gc()
        self.s3.delete_object.assert_not_called()


if __name__ == "__main__":
    unittest.main()
