import io
from hashlib import sha256
import unittest
from unittest.mock import MagicMock, patch

from botocore.exceptions import ClientError
from PIL import Image

from backfill_previews import backfill_one


class PreviewBackfillTest(unittest.TestCase):
    def setUp(self):
        image = Image.effect_noise((640, 480), 80).convert("RGB")
        stream = io.BytesIO()
        image.save(stream, format="WEBP", lossless=True, method=4)
        self.source = stream.getvalue()
        self.assertGreater(len(self.source), 220_000)
        self.hash = sha256(self.source).hexdigest()
        self.key = "images/img1/" + self.hash
        self.db = MagicMock()
        self.cursor = self.db.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.return_value = {
            "id": "img1", "content_type": "image/webp", "byte_size": len(self.source),
            "content_hash": self.hash, "content": None, "object_key": self.key,
            "object_content_type": "image/webp", "object_byte_size": len(self.source),
            "object_hash": self.hash, "preview_key": None, "preview_checked": False,
        }
        self.cursor.rowcount = 1
        self.objects = {self.key: self.source}
        self.s3 = MagicMock()
        self.s3.get_object.side_effect = lambda Bucket, Key: {"Body": io.BytesIO(self.objects[Key])}
        self.s3.head_object.side_effect = lambda Bucket, Key: (_ for _ in ()).throw(
            ClientError({"Error": {"Code": "404"}, "ResponseMetadata": {"HTTPStatusCode": 404}},
                        "HeadObject"))
        self.s3.put_object.side_effect = lambda Bucket, Key, Body, ContentType, Metadata: (
            self.objects.__setitem__(Key, Body))

    def test_large_webp_gets_verified_small_jpeg_preview(self):
        self.assertEqual(backfill_one(self.db, self.s3, "private", "img1"), "created")
        self.assertEqual(self.cursor.execute.call_count, 2)
        key = self.cursor.execute.call_args.args[1][0]
        self.assertLess(len(self.objects[key]), len(self.source))
        with Image.open(io.BytesIO(self.objects[key])) as image:
            self.assertEqual(image.format, "JPEG")

    def test_corrupt_object_cannot_mark_preview(self):
        self.objects[self.key] = b"wrong"
        with self.assertRaisesRegex(RuntimeError, "differs"):
            backfill_one(self.db, self.s3, "private", "img1")
        self.assertEqual(self.cursor.execute.call_count, 1)

    def test_no_smaller_preview_marks_checked_once(self):
        with patch("backfill_previews.preview", return_value=None):
            self.assertEqual(backfill_one(self.db, self.s3, "private", "img1"), "not-smaller")
        self.assertIn("preview_checked=TRUE", self.cursor.execute.call_args.args[0])
        self.cursor.fetchone.return_value["preview_checked"] = True
        self.assertEqual(backfill_one(self.db, self.s3, "private", "img1"), "skipped")
        self.assertEqual(self.cursor.execute.call_count, 3)


if __name__ == "__main__":
    unittest.main()
