import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('routing', Path(__file__).with_name('publish-static-routing.py'))
routing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(routing)


class RoutingTest(unittest.TestCase):
    def test_default_closed_and_preserve_operator_switches(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'config/routing.json'
            routing.publish(path, 'a' * 40)
            data = json.loads(path.read_text())
            self.assertFalse(data['enabled'])
            self.assertTrue(data['forceCloud'])
            data.update(enabled=True, staticEnabled=True, forceCloud=False)
            data['nodes'][0]['enabled'] = True
            path.write_text(json.dumps(data))
            routing.publish(path, 'b' * 40)
            data = json.loads(path.read_text())
            self.assertTrue(data['enabled'])
            self.assertFalse(data['forceCloud'])
            self.assertTrue(data['nodes'][0]['enabled'])
            self.assertEqual(['b' * 40, 'a' * 40], data['allowedReleases'])
            routing.publish(path, 'b' * 40)
            self.assertEqual(data, json.loads(path.read_text()))

    def test_malformed_config_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'routing.json'
            path.write_text('{"schema":1,"enabled":"true"}')
            with self.assertRaises(ValueError): routing.publish(path, 'a' * 40)
            self.assertEqual('{"schema":1,"enabled":"true"}', path.read_text())

    def test_retired_node_is_removed_while_linux2_route_is_preserved(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'routing.json'
            path.write_text(json.dumps({
                'schema': 1, 'enabled': True, 'staticEnabled': True, 'forceCloud': False,
                'allowedReleases': [], 'nodes': [
                    {'origin': 'https://linux1.wayuwa.cn:9443', 'enabled': False},
                    {'origin': 'https://linux2.wayuwa.cn:9443', 'enabled': True},
                ],
            }))
            routing.publish(path, 'a' * 40)
            data = json.loads(path.read_text())
            self.assertEqual([{'origin': 'https://linux2.wayuwa.cn:9443', 'enabled': True}], data['nodes'])


if __name__ == '__main__': unittest.main()
