import importlib.util
import io
import pathlib
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("tunnel", pathlib.Path(__file__).with_name("wao-linux2-tunnel.py"))
tunnel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tunnel)

class TunnelTest(unittest.TestCase):
    def test_system_public_address_needs_no_external_query(self):
        with patch.object(tunnel.socket, "getaddrinfo", return_value=[(None, None, None, None, ("8.8.8.8", 22))]), patch.object(tunnel.urllib.request, "urlopen") as request:
            self.assertEqual("8.8.8.8", tunnel.resolve_ipv4("example.com"))
            request.assert_not_called()

    def test_fake_address_uses_real_a_record_and_keeps_host_identity(self):
        with patch.object(tunnel.socket, "getaddrinfo", return_value=[(None, None, None, None, ("198.18.0.14", 22))]), patch.object(tunnel.urllib.request, "urlopen", return_value=io.StringIO('{"Status":0,"Answer":[{"type":1,"data":"1.1.1.1"}]}')):
            self.assertEqual("1.1.1.1", tunnel.resolve_ipv4("example.com"))
        command = tunnel.ssh_command("example.com", "1.1.1.1", "/key", "/known-hosts", 7897)
        self.assertIn("HostKeyAlias=example.com", command)
        self.assertIn("IPQoS=none", command)
        self.assertIn("StrictHostKeyChecking=yes", command)
        self.assertIn("127.0.0.1:2201:127.0.0.1:22", command)
        self.assertEqual("wao-tunnel@1.1.1.1", command[-1])

    def test_invalid_and_nonpublic_results_fail_closed(self):
        for value in ["127.0.0.1", "198.18.0.14", "10.1.1.1", "::1", "invalid"]:
            self.assertIsNone(tunnel.public_ipv4(value))
        with patch.object(tunnel.socket, "getaddrinfo", side_effect=OSError()), patch.object(tunnel.urllib.request, "urlopen", side_effect=OSError()):
            self.assertRaises(RuntimeError, tunnel.resolve_ipv4, "example.com")

if __name__ == "__main__": unittest.main()
