import io
from hashlib import sha256
import unittest
from unittest.mock import MagicMock

from PIL import Image
from botocore.exceptions import ClientError

from optimize_png_webp import optimize_one


class OptimizePngTest(unittest.TestCase):
    def setUp(self):
        image = Image.new("RGB", (400, 400), (30, 80, 120))
        for y in range(0, 400, 15):
            for x in range(0, 400, 15):
                image.putpixel((x, y), (240, 70, 30))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        self.original = buffer.getvalue()
        self.hash = sha256(self.original).hexdigest()
        self.key = "images/img1/" + self.hash
        self.db = MagicMock()
        self.cursor = self.db.cursor.return_value.__enter__.return_value
        self.cursor.fetchone.return_value = {
            "id": "img1", "content_type": "image/png", "byte_size": len(self.original),
            "content_hash": self.hash, "content": self.original, "object_key": self.key,
            "object_content_type": "image/png", "original_archive_key":
            "WAO-backups/originals/png/" + self.hash[:2] + "/" + self.hash + ".png.gpg",
            "preview_key": None, "lossless_webp_checked": False,
        }
        self.cursor.rowcount = 1
        self.objects = {}
        self.objects[self.key] = self.original
        self.s3 = MagicMock()

        def put(Bucket, Key, Body, ContentType, Metadata):
            self.objects[Key] = Body

        def get(Bucket, Key):
            return {"Body": io.BytesIO(self.objects[Key])}

        self.s3.put_object.side_effect = put
        self.s3.get_object.side_effect = get
        self.s3.head_object.side_effect = lambda Bucket, Key: (_ for _ in ()).throw(
            ClientError({"Error": {"Code": "404"}, "ResponseMetadata": {"HTTPStatusCode": 404}},
                        "HeadObject"))

    def test_verified_archive_and_pixel_equal_webp_switch(self):
        verified = []
        self.assertEqual(optimize_one(self.db, self.s3, "private", "img1",
                                      lambda key, body: verified.append((key, body))), "optimized")
        self.assertEqual(verified[0][1], self.original)
        self.assertIn("object_content_type='image/webp'", self.cursor.execute.call_args.args[0])
        self.assertEqual(self.cursor.execute.call_count, 2)

    def test_missing_archive_verifier_prevents_switch(self):
        with self.assertRaisesRegex(RuntimeError, "archive must be verified"):
            optimize_one(self.db, self.s3, "private", "img1")
        self.assertEqual(self.cursor.execute.call_count, 1)


if __name__ == "__main__":
    unittest.main()
