import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("static-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "dist"
        (self.source / "assets").mkdir(parents=True)
        (self.source / "index.html").write_text("<html>test</html>")
        (self.source / "assets/app.js").write_text("export const version=1")
        self.archive = self.root / "release.tgz"
        self.target = self.root / "releases"
        self.sha = "a" * 40

    def pack(self):
        release.pack(self.source, self.archive, self.sha)

    def test_install_idempotency_and_immutable_conflict(self):
        self.pack()
        self.assertEqual("installed", release.install(self.archive, self.target, self.sha))
        self.assertEqual("already-installed", release.install(self.archive, self.target, self.sha))
        (self.source / "assets/app.js").write_text("changed")
        self.pack()
        with self.assertRaisesRegex(ValueError, "immutable"):
            release.install(self.archive, self.target, self.sha)
        self.assertIn("version=1", (self.target / self.sha / "assets/app.js").read_text())
        self.assertEqual([self.sha], [p.name for p in self.target.iterdir()])

    def test_corruption_never_activates_partial_release(self):
        self.pack()
        with tarfile.open(self.archive) as src:
            entries = [(m.name, src.extractfile(m).read()) for m in src]
        with tarfile.open(self.archive, "w:gz") as dst:
            for name, data in entries:
                release.add_file(dst, name, b"broken" if name == "assets/app.js" else data)
        with self.assertRaisesRegex(ValueError, "checksum"):
            release.install(self.archive, self.target, self.sha)
        self.assertEqual([], list(self.target.iterdir()))

    def test_traversal_links_duplicate_and_hidden_files_rejected(self):
        for name in ("../escape", "/absolute", "assets/../../escape", ".env", "assets/.secret", "assets/app.js.map"):
            with self.subTest(name=name):
                with tarfile.open(self.archive, "w:gz") as dst:
                    release.add_file(dst, name, b"bad")
                with self.assertRaises(ValueError):
                    release.install(self.archive, self.target, self.sha)
        with tarfile.open(self.archive, "w:gz") as dst:
            link = tarfile.TarInfo("link"); link.type = tarfile.SYMTYPE; link.linkname = "../escape"
            dst.addfile(link)
        with self.assertRaises(ValueError):
            release.install(self.archive, self.target, self.sha)
        with tarfile.open(self.archive, "w:gz") as dst:
            release.add_file(dst, "index.html", b"1")
            release.add_file(dst, "index.html", b"2")
        with self.assertRaises(ValueError):
            release.install(self.archive, self.target, self.sha)

    def test_release_identity_and_existing_damage_fail_closed(self):
        self.pack()
        with self.assertRaisesRegex(ValueError, "identity"):
            release.install(self.archive, self.target, "b" * 40)
        release.install(self.archive, self.target, self.sha)
        (self.target / self.sha / "assets/app.js").write_text("damaged")
        with self.assertRaisesRegex(ValueError, "damaged"):
            release.install(self.archive, self.target, self.sha)


if __name__ == "__main__":
    unittest.main()
