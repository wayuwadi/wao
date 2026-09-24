import base64
import importlib.util
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


MODULE_PATH = Path(__file__).with_name("webdav_client.py")
SPEC = importlib.util.spec_from_file_location("wao_webdav_client", MODULE_PATH)
webdav = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(webdav)


class WebDavHandler(BaseHTTPRequestHandler):
    files: dict[str, bytes] = {}
    directories: set[str] = {"/webdav/"}
    authorization = "Basic " + base64.b64encode(b"backup-user:app-password").decode("ascii")

    def log_message(self, _format, *_args):
        pass

    def authorized(self):
        if self.headers.get("Authorization") == self.authorization:
            return True
        self.send_response(401)
        self.end_headers()
        return False

    def normalized(self, directory=False):
        path = unquote(urlsplit(self.path).path)
        if directory and not path.endswith("/"):
            path += "/"
        return path

    def do_MKCOL(self):
        if not self.authorized():
            return
        path = self.normalized(directory=True)
        existed = path in self.directories
        self.directories.add(path)
        self.send_response(405 if existed else 201)
        self.end_headers()

    def do_PUT(self):
        if not self.authorized():
            return
        size = int(self.headers["Content-Length"])
        self.files[self.normalized()] = self.rfile.read(size)
        self.send_response(201)
        self.end_headers()

    def do_HEAD(self):
        if not self.authorized():
            return
        payload = self.files.get(self.normalized())
        if payload is None:
            self.send_response(404)
        else:
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
        self.end_headers()

    def do_GET(self):
        if not self.authorized():
            return
        payload = self.files.get(self.normalized())
        if payload is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_PROPFIND(self):
        if not self.authorized():
            return
        parent = self.normalized(directory=True)
        responses = [parent]
        responses.extend(
            path for path in sorted(self.directories) if path != parent and path.startswith(parent)
            and "/" not in path[len(parent):].rstrip("/")
        )
        body = ['<?xml version="1.0"?><multistatus xmlns="DAV:">']
        for path in responses:
            body.append(
                f"<response><href>{path}</href><propstat><prop><resourcetype>"
                "<collection/></resourcetype></prop></propstat></response>"
            )
        body.append("</multistatus>")
        payload = "".join(body).encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_DELETE(self):
        if not self.authorized():
            return
        prefix = self.normalized(directory=True)
        self.directories = {path for path in self.directories if not path.startswith(prefix)}
        self.files = {path: value for path, value in self.files.items() if not path.startswith(prefix)}
        type(self).directories = self.directories
        type(self).files = self.files
        self.send_response(204)
        self.end_headers()


class WebDavClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), WebDavHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()

    def setUp(self):
        WebDavHandler.files = {}
        WebDavHandler.directories = {"/webdav/"}
        self.client = webdav.WebDavClient(
            f"http://127.0.0.1:{self.server.server_port}/webdav",
            "backup-user",
            "app-password",
            allow_http=True,
        )

    def test_upload_list_download_and_recursive_delete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.bin"
            source.write_bytes(b"encrypted-data" * 200000)
            self.client.ensure_dir("WAO-backups/linux2/20260831T193000Z")
            self.client.upload(source, "WAO-backups/linux2/20260831T193000Z/part-0000")
            self.assertEqual(
                self.client.stat_size("WAO-backups/linux2/20260831T193000Z/part-0000"),
                source.stat().st_size,
            )
            self.assertEqual(
                self.client.list_dirs("WAO-backups/linux2"), ["20260831T193000Z"]
            )
            destination = root / "download.bin"
            self.client.download(
                "WAO-backups/linux2/20260831T193000Z/part-0000", destination
            )
            self.assertEqual(destination.read_bytes(), source.read_bytes())
            self.client.delete("WAO-backups/linux2/20260831T193000Z")
            self.assertEqual(self.client.list_dirs("WAO-backups/linux2"), [])

    def test_plain_http_is_rejected_without_explicit_test_override(self):
        with self.assertRaises(webdav.WebDavError):
            webdav.WebDavClient(
                f"http://127.0.0.1:{self.server.server_port}/webdav",
                "backup-user",
                "app-password",
            )

    def test_uploads_selected_byte_ranges_without_staging_files(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.bin"
            source.write_bytes(b"abcdefghij")
            self.client.ensure_dir("chunks")
            self.client.upload(source, "chunks/part-0000", offset=0, length=4)
            self.client.upload(source, "chunks/part-0001", offset=4, length=4)
            self.client.upload(source, "chunks/part-0002", offset=8, length=2)
            self.assertEqual(WebDavHandler.files["/webdav/chunks/part-0000"], b"abcd")
            self.assertEqual(WebDavHandler.files["/webdav/chunks/part-0001"], b"efgh")
            self.assertEqual(WebDavHandler.files["/webdav/chunks/part-0002"], b"ij")
            with self.assertRaises(webdav.WebDavError):
                self.client.upload(source, "chunks/bad", offset=9, length=2)

    def test_remote_path_traversal_is_rejected(self):
        with self.assertRaises(webdav.WebDavError):
            self.client.ensure_dir("WAO-backups/../outside")


if __name__ == "__main__":
    unittest.main()
