import io
from hashlib import sha256
import unittest

from botocore.exceptions import ClientError
from PIL import Image

from migrate_assets import ensure_object, lossless_webp, pixel_equivalent_png_webp, preview


class FakeS3:
    def __init__(self, corrupt=False):
        self.objects = {}
        self.corrupt = corrupt

    def head_object(self, Bucket, Key):
        if Key not in self.objects:
            raise ClientError({"Error": {"Code": "404"}, "ResponseMetadata": {"HTTPStatusCode": 404}},
                              "HeadObject")
        data, metadata = self.objects[Key]
        return {"ContentLength": len(data), "Metadata": metadata}

    def put_object(self, Bucket, Key, Body, ContentType, Metadata):
        self.objects[Key] = (Body, Metadata)

    def get_object(self, Bucket, Key):
        data = self.objects[Key][0]
        return {"Body": io.BytesIO(data + b"corrupt" if self.corrupt else data)}


class MigrationSafetyTest(unittest.TestCase):
    def test_upload_is_read_back_before_any_row_can_be_marked(self):
        s3 = FakeS3()
        data = b"original image bytes"
        hash_value = sha256(data).hexdigest()
        key = "images/img1/" + hash_value
        ensure_object(s3, "private", key, data, "image/png", hash_value)
        self.assertEqual(s3.objects[key][0], data)
        self.assertEqual(s3.objects[key][1]["sha256"], hash_value)

        with self.assertRaisesRegex(RuntimeError, "readback differs"):
            ensure_object(FakeS3(corrupt=True), "private", key, data, "image/png", hash_value)

    def test_large_png_gets_small_decodable_preview(self):
        image = Image.new("RGB", (1600, 900), "white")
        output = io.BytesIO()
        image.save(output, format="PNG")
        # A flat-color PNG is already tiny, so the preview gate correctly avoids extra bytes.
        self.assertIsNone(preview(output.getvalue(), "image/png"))

        pixels = Image.effect_noise((1600, 900), 100).convert("RGB")
        output = io.BytesIO()
        pixels.save(output, format="PNG")
        smaller = preview(output.getvalue(), "image/png")
        self.assertIsNotNone(smaller)
        self.assertLess(len(smaller), len(output.getvalue()))
        self.assertEqual(Image.open(io.BytesIO(smaller)).size, (768, 432))

    def test_lossless_webp_preserves_every_pixel_including_transparent_rgb(self):
        image = Image.new("RGBA", (128, 128))
        for y in range(128):
            for x in range(128):
                image.putpixel((x, y), ((x * 17) % 256, (y * 19) % 256,
                                        (x + y) % 256, 0 if x < 64 else 255))
        output = io.BytesIO()
        image.save(output, format="PNG")
        optimized = lossless_webp(output.getvalue())
        self.assertIsNotNone(optimized)
        self.assertLess(len(optimized), len(output.getvalue()))
        self.assertTrue(pixel_equivalent_png_webp(output.getvalue(), optimized))


if __name__ == "__main__":
    unittest.main()
