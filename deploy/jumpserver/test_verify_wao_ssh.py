"""Exercise SSH routing and fail-closed behavior without live credentials."""
import ast
from contextlib import ExitStack, redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock


class VerifySshTest(unittest.TestCase):
    def setUp(self):
        self.paramiko = MagicMock()
        self.clients = []

        def client_factory():
            client = MagicMock()
            client.__enter__.return_value = client
            stdout = MagicMock()
            stdout.channel.recv_exit_status.return_value = 0
            client.exec_command.return_value = (None, stdout, None)
            self.clients.append(client)
            return client

        self.paramiko.SSHClient.side_effect = client_factory
        namespace = {"paramiko": self.paramiko, "ExitStack": ExitStack, "StringIO": StringIO}
        tree = ast.parse(Path(__file__).with_name("verify_wao_ssh.py").read_text(encoding="utf-8"))
        functions = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef)], type_ignores=[])
        exec(compile(functions, "verify_wao_ssh.py", "exec"), namespace)
        self.verify = namespace["verify_asset"]
        self.gateway = self.asset("Linux2", "linux2.example", 22)
        self.slave = self.asset("Linux1", "127.0.0.1", 2201, self.gateway)

    @staticmethod
    def asset(name, address, port, gateway=None):
        accounts = MagicMock()
        accounts.get.return_value = SimpleNamespace(username="test-user", secret_type="ssh_key", secret="test-key")
        return SimpleNamespace(name=name, address=address, gateway=gateway, accounts=accounts,
                               get_protocol_port=lambda protocol: port)

    def test_direct_route_uses_saved_account_and_strict_known_hosts(self):
        self.verify(self.gateway)
        client = self.clients[0]
        self.assertEqual(client.connect.call_args.args, ("linux2.example",))
        options = client.connect.call_args.kwargs
        self.assertEqual(options["port"], 22)
        self.assertFalse(options["allow_agent"])
        self.assertFalse(options["look_for_keys"])
        client.load_host_keys.assert_called_once()
        client.set_missing_host_key_policy.assert_called_once_with(self.paramiko.RejectPolicy.return_value)
        client.__exit__.assert_called_once()

    def test_loopback_slave_connects_through_saved_gateway(self):
        self.verify(self.slave)
        gateway, slave = self.clients
        opener = gateway.get_transport.return_value.open_channel
        self.assertEqual(opener.call_args.args[0:2], ("direct-tcpip", ("127.0.0.1", 2201)))
        self.assertEqual(slave.connect.call_args.kwargs["port"], 2201)
        self.assertIs(slave.connect.call_args.kwargs["sock"], opener.return_value.__enter__.return_value)
        slave.__exit__.assert_called_once()
        gateway.__exit__.assert_called_once()

    def test_direct_nonstandard_port_is_preserved(self):
        self.verify(self.asset("Direct", "slave.example", 2223))
        self.assertEqual(self.clients[0].connect.call_args.kwargs["port"], 2223)
        self.assertIsNone(self.clients[0].connect.call_args.kwargs["sock"])

    def test_password_account_is_rejected(self):
        self.slave.accounts.get.return_value.secret_type = "password"
        with self.assertRaisesRegex(RuntimeError, "Dedicated SSH key"):
            self.verify(self.slave)
        self.assertEqual(len(self.clients), 1)
        self.clients[0].__exit__.assert_called_once()

    def test_gateway_failure_cannot_report_success(self):
        client = MagicMock()
        client.__enter__.return_value = client
        client.connect.side_effect = OSError("gateway refused")
        self.paramiko.SSHClient.side_effect = None
        self.paramiko.SSHClient.return_value = client
        with redirect_stdout(StringIO()) as output, self.assertRaises(OSError):
            self.verify(self.slave)
        self.assertEqual(output.getvalue(), "")
        client.__exit__.assert_called_once()

    def test_target_key_or_connection_failure_closes_gateway(self):
        original = self.paramiko.SSHClient.side_effect

        def factory():
            client = original()
            if len(self.clients) == 2:
                client.connect.side_effect = OSError("target host key mismatch")
            return client

        self.paramiko.SSHClient.side_effect = factory
        with redirect_stdout(StringIO()) as output, self.assertRaises(OSError):
            self.verify(self.slave)
        self.assertEqual(output.getvalue(), "")
        for client in self.clients:
            client.__exit__.assert_called_once()

    def test_nonzero_remote_exit_is_failure(self):
        original = self.paramiko.SSHClient.side_effect

        def factory():
            client = original()
            client.exec_command.return_value[1].channel.recv_exit_status.return_value = 1
            return client

        self.paramiko.SSHClient.side_effect = factory
        with self.assertRaisesRegex(RuntimeError, "SSH command failed"):
            self.verify(self.gateway)


if __name__ == "__main__":
    unittest.main()
