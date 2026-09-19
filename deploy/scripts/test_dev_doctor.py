"""体检工具的故障分类、只读边界与脱敏输出回归。"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("dev_doctor", Path(__file__).with_name("dev-doctor.py"))
doctor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(doctor)


def args(*extra):
    return doctor.parser().parse_args(list(extra))


class DoctorTests(unittest.TestCase):
    def test_node_engine_boundaries(self):
        expression = "^22.18.0 || >=24.12.0"
        for version in ((22, 18, 0), (22, 99, 0), (24, 12, 0), (25, 0, 0)):
            self.assertTrue(doctor.supports_node(version, expression))
        for version in ((20, 99, 0), (22, 17, 9), (23, 9, 0), (24, 11, 9)):
            self.assertFalse(doctor.supports_node(version, expression))
        with self.assertRaises(ValueError):
            doctor.supports_node((24, 12, 0), ">=24.x")

    def test_environment_is_literal_and_process_values_win(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / ".env"
            sentinel = Path(directory) / "should-not-exist"
            payload = f"$(touch {sentinel})"
            file.write_text(f"export DB_PASS='{payload}'\nDB_NAME=wao\nDB_USER='a b#c' # comment\nIGNORED='secret'\n")
            result = doctor.load_environment(file, {"DB_NAME": "from-process"})
            self.assertEqual(result["DB_PASS"], payload)
            self.assertEqual(result["DB_USER"], "a b#c")
            self.assertEqual(result["DB_NAME"], "from-process")
            self.assertNotIn("IGNORED", result)
            self.assertFalse(sentinel.exists())

    def test_invalid_environment_does_not_echo_its_line(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / ".env"
            file.write_text("DB_PASS='private-secret\n")
            with self.assertRaises(ValueError) as caught:
                doctor.load_environment(file, {})
            self.assertNotIn("private-secret", str(caught.exception))

    def test_bad_url_argument_never_echoes_credentials(self):
        for value in ("https://wao.wayuwa.cn", "http://localhost@evil.test", "http://u:private-secret@localhost",
                      "http://localhost:8080/api", "http://localhost:invalid", "http://[bad"):
            output = io.StringIO()
            with contextlib.redirect_stderr(output), self.assertRaises(SystemExit) as caught:
                args("--api-url", value)
            self.assertEqual(caught.exception.code, 2)
            self.assertNotIn("private-secret", output.getvalue())
        self.assertEqual(doctor.local_origin("http://[::1]:8080/"), "http://[::1]:8080")

    def test_offline_never_opens_network(self):
        instance = doctor.Doctor(args("--offline", "--web-url", "http://localhost:5174"), {
            "MYSQL_HOST": "private-host", "DB_NAME": "private-db", "DB_USER": "private-user", "DB_PASS": "private-secret"})
        with patch.object(doctor.socket, "create_connection") as connect, patch.object(doctor, "http_get") as get:
            instance.database(instance.environment)
            instance.services()
        connect.assert_not_called()
        get.assert_not_called()
        report = json.dumps(instance.checks)
        for secret in instance.environment.values():
            self.assertNotIn(secret, report)

    def test_missing_config_skips_database(self):
        instance = doctor.Doctor(args(), {})
        with patch.object(doctor.socket, "create_connection") as connect:
            instance.database({"MYSQL_HOST": "<host>", "DB_PASS": "<password>"})
        connect.assert_not_called()
        self.assertEqual([row["status"] for row in instance.checks], ["fail", "skip"])

    def test_port_validation_and_connection_failure_are_sanitized(self):
        config = {"MYSQL_HOST": "private-host", "DB_NAME": "private-db", "DB_USER": "private-user", "DB_PASS": "private-secret"}
        for port in ("0", "65536", "secret-port"):
            instance = doctor.Doctor(args(), {})
            instance.database({**config, "MYSQL_PORT": port})
            self.assertEqual(instance.checks[-1]["status"], "fail")
            self.assertNotIn(port, instance.checks[-1]["message"])
        instance = doctor.Doctor(args(), {})
        with patch.object(doctor.socket, "create_connection", side_effect=OSError("private-host private-secret")):
            instance.database(config)
        self.assertNotIn("private-secret", json.dumps(instance.checks))
        self.assertEqual(instance.checks[-1]["status"], "fail")

    def test_tcp_connect_is_not_reported_as_sql_success(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            instance = doctor.Doctor(args(), {})
            instance.database({"MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": str(listener.getsockname()[1]),
                               "DB_NAME": "test", "DB_USER": "test", "DB_PASS": "test"})
        self.assertEqual(instance.checks[-1]["status"], "pass")
        self.assertIn("未验证账号", instance.checks[-1]["message"])

    def test_missing_failed_timed_out_and_unsupported_runtime(self):
        instance = doctor.Doctor(args(), {})
        instance.tool_version("node", None, "--version", r"v\d+\.\d+\.\d+", lambda v: True, "configure")
        with patch.object(doctor.subprocess, "run", side_effect=subprocess.TimeoutExpired("private-command", 1)):
            instance.tool_version("node", "/fake", "--version", r"v\d+\.\d+\.\d+", lambda v: True, "configure")
        for code, output in ((1, "private-secret"), (0, "unknown private-secret"), (0, "v20.1.0 private-secret")):
            with patch.object(doctor.subprocess, "run", return_value=subprocess.CompletedProcess([], code, output, "")):
                instance.tool_version("node", "/fake", "--version", r"v\d+\.\d+\.\d+", lambda v: v >= (24, 12, 0), "configure")
        self.assertTrue(all(row["status"] == "fail" for row in instance.checks))
        self.assertNotIn("private-secret", json.dumps(instance.checks))

    def test_wrong_architecture_has_actionable_safe_message(self):
        instance = doctor.Doctor(args(), {})
        with patch.object(doctor.subprocess, "run", side_effect=OSError(86, "private-secret")):
            instance.tool_version("java", "/fake", "-version", "unused", lambda v: True, "configure")
        self.assertIn("CPU 架构", instance.checks[0]["message"])
        self.assertNotIn("private-secret", json.dumps(instance.checks))

    def test_service_errors_and_wrong_identity_do_not_pass(self):
        for response in ((200, "text/html", b"secret"), (503, "application/json", b"secret"),
                         (200, "application/json", b'{"status":"DOWN"}'),
                         (200, "application/json", b'["secret"]')):
            instance = doctor.Doctor(args(), {})
            with patch.object(doctor, "http_get", return_value=response):
                instance.services()
            self.assertEqual(instance.checks[0]["status"], "fail")
            self.assertNotIn("secret", json.dumps(instance.checks))
        instance = doctor.Doctor(args(), {})
        with patch.object(doctor, "http_get", side_effect=[(200, "application/json", b'{"status":"UP"}'),
             (200, "application/json", b'{"code":0,"data":{"service":"other-service"}}')]):
            instance.services()
        self.assertEqual(instance.checks[0]["status"], "fail")

    def test_http_timeouts_are_classified_without_raw_error(self):
        instance = doctor.Doctor(args(), {})
        with patch.object(doctor, "http_get", side_effect=TimeoutError("private-secret")):
            instance.services()
        self.assertEqual(instance.checks[0]["status"], "fail")
        self.assertNotIn("private-secret", json.dumps(instance.checks))

    def test_exit_code_reflects_missing_requirements(self):
        instance = doctor.Doctor(args("--offline"), {})
        with patch.object(instance, "runtimes"):
            report = instance.run()
        self.assertEqual(report["exitCode"], 1)
        self.assertGreater(report["summary"]["fail"], 0)

    def test_http_reads_health_identity_and_html_but_never_redirects(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_GET(self):
                requests.append(self.path)
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/forbidden")
                    self.end_headers()
                    return
                body, mime = {
                    "/actuator/health": (b'{"status":"UP"}', "application/json"),
                    "/api/hello": (b'{"code":0,"data":{"service":"wao-server"}}', "application/json"),
                    "/": (b"<html><title>WAO Life OS</title></html>", "text/html"),
                }.get(self.path, (b"unexpected", "text/plain"))
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.end_headers()
                self.wfile.write(body)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            instance = doctor.Doctor(args("--api-url", base, "--web-url", base), {})
            instance.services()
            self.assertTrue(all(row["status"] == "pass" for row in instance.checks))
            self.assertEqual(doctor.http_get(base + "/redirect", 2)[0], 302)
            self.assertNotIn("/forbidden", requests)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
