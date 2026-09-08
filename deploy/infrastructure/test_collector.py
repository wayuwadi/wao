import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


server = module('server')
probe = module('probe')


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        server.DATA = Path(self.directory.name)
        server.NODES = [dict(id='test', name='测试', role='测试', address='test', command=['false'])]
        server.LATEST = server.initialize()

    def value(self):
        return dict(collectedAt=int(time.time()), cpuPercent=12.5, memory=dict(available=600,total=1000,swapUsed=0),
                    disks=[dict(mount='/',available=3000)], services=[])

    def test_failure_retains_last_success_and_never_fabricates_history(self):
        value = self.value()
        with patch.object(server, 'sample', return_value=('test',value,None)):
            server.collect_once()
        with patch.object(server, 'sample', return_value=('test',None,'连接失败')):
            server.collect_once()
        node = server.overview(24)['nodes'][0]
        self.assertFalse(node['online'])
        self.assertEqual(node['error'],'连接失败')
        self.assertEqual(node['memory']['available'],600)
        self.assertEqual(len(node['history']),1)
        self.assertEqual(node['collectedAt'],value['collectedAt'])
        self.assertEqual(server.initialize()['test']['error'],'连接失败')

    def test_old_snapshot_is_explicitly_stale(self):
        value = self.value(); value['collectedAt'] -= 181
        server.LATEST['test'] = value
        self.assertTrue(server.overview(1)['nodes'][0]['stale'])
        self.assertFalse(server.overview(1)['nodes'][0]['online'])

    def test_systemd_delta_ignores_reset_and_first_sample(self):
        def service(cpu): return dict(kind='systemd',name='a',cpuTotalNs=cpu)
        current=dict(collectedAt=70,services=[service(30000000000)])
        server.service_deltas(current,dict(collectedAt=10,services=[service(0)]))
        self.assertEqual(current['services'][0]['cpuPercent'],50)
        current=dict(collectedAt=80,services=[service(0)])
        server.service_deltas(current,dict(collectedAt=70,services=[service(100)]))
        self.assertNotIn('cpuPercent',current['services'][0])

    def test_history_excludes_old_points_and_bounds_payload(self):
        now=int(time.time())
        with server.connection() as db:
            for i in range(500):
                db.execute('INSERT INTO metric VALUES(?,?,?,?,?,?,?)',('test',now-i*60,10,5,10,50,0))
        self.assertLessEqual(len(server.overview(24)['nodes'][0]['history']),241)
        self.assertLessEqual(len(server.overview(1)['nodes'][0]['history']),61)

    def test_docker_missing_is_unknown_and_units_preserve_binary_sizes(self):
        with patch.object(probe, 'run', return_value=None):
            services,warning=probe.containers()
        self.assertEqual(services,[]); self.assertIsNotNone(warning)
        self.assertEqual(probe.bytes_value('1.5GiB'),1610612736)
        self.assertIsNone(probe.bytes_value('N/A'))
        self.assertEqual(probe.group('docker.service'),'其他服务')
        self.assertEqual(probe.group('docker-api-1','langgenius/dify-api:1'),'Dify')


if __name__ == '__main__': unittest.main()
