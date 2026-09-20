import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ha", Path(__file__).with_name("validate-private-ha.py"))
ha = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ha)
adopt_spec = importlib.util.spec_from_file_location("adopt", Path(__file__).with_name("adopt-private-topology.py"))
adopt = importlib.util.module_from_spec(adopt_spec)
adopt_spec.loader.exec_module(adopt)


class ConsensusTest(unittest.TestCase):
    def views(self, primary="192.168.31.180"):
        return [(host, {"ip": primary, "port": "6379", "quorum": "2",
                        "num-other-sentinels": "2", "flags": "master"}) for host in ha.SENTINELS]

    def test_accepts_either_elected_private_primary(self):
        for primary in ha.DATA_NODES:
            self.assertEqual(ha.check_views(self.views(primary), 2), primary)

    def test_rejects_disagreement_public_addresses_and_lost_membership(self):
        cases = [("ip", "192.168.2.180"), ("ip", "linux2.wayuwa.cn"),
                 ("num-other-sentinels", "1"), ("flags", "master,s_down"), ("quorum", "4")]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                views = self.views()
                views[0][1][field] = value
                with self.assertRaises(RuntimeError):
                    ha.check_views(views, 2)

    def test_adoption_does_not_mutate_if_either_monitor_is_not_gated(self):
        calls = []

        def query(container, host, port, *command):
            calls.append(command)
            if command == ("ROLE",):
                return ["slave", "192.168.31.180", 6379, "connected", 1]
            if command == ("SENTINEL", "MASTER", "wao-master"):
                return {"quorum": "4" if host == "192.168.31.180" else "2"}
            self.fail("Adoption modified state before checking both gates")

        with patch.object(adopt.ha, "query", side_effect=query), patch("sys.argv", ["adopt", "slave", "adopt"]):
            with self.assertRaises(RuntimeError):
                adopt.main()
        self.assertEqual(len(calls), 3)


if __name__ == "__main__":
    unittest.main()
