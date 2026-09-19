import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SKILL = Path(__file__).resolve().parents[2] / '.codex/skills/codex-knowledge-archive/scripts/archive.py'
spec = importlib.util.spec_from_file_location('archive', SKILL)
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.state = patch.object(a, 'STATE', self.root / 'state'); self.state.start()
        self.home = self.root / 'codex'; (self.home / 'sessions').mkdir(parents=True)
        self.file = self.home / 'sessions/rollout.jsonl'
        self.config = {'codexHome': str(self.home), 'deviceId': 'device-1', 'deviceName': 'test',
                       'memberId': 'u1', 'excludeProjects': [], 'since': None}
        self.records = [{'type': 'session_meta', 'payload': {'id': 's1', 'cwd': '/project'}},
                        self.msg('user', 'Hello'), self.msg('assistant', 'Done')]
        self.write(self.records)

    def tearDown(self):
        self.state.stop(); self.tmp.cleanup()

    def msg(self, role, text, **kwargs):
        return {'timestamp': '2026-09-19T10:00:00Z', 'type': 'response_item',
                'payload': {'type': 'message', 'role': role, 'content': [{'type': 'input_text', 'text': text}], **kwargs}}

    def write(self, records):
        self.file.write_text(''.join(json.dumps(r) + '\n' for r in records))

    def test_allowlist_and_redaction(self):
        for role in ('system', 'developer'):
            self.assertIsNone(a.visible(self.msg(role, 'private instruction')))
        self.assertIsNone(a.visible(self.msg('assistant', 'thinking', channel='analysis')))
        self.assertIsNone(a.visible({'type': 'response_item', 'payload': {'type': 'reasoning', 'summary': 'private'}}))
        self.assertIsNone(a.visible(self.msg('user', '# AGENTS.md instructions for /project\n...')))
        secret = 'fake' + 'SensitiveValue12345'
        text = a.redact('password="%s"\nAuthorization: Bearer %s\n' % (secret, secret))
        self.assertNotIn(secret, text)
        nested = json.dumps({'output': json.dumps({'api_key': secret}), 'name': 'tool'})
        self.assertNotIn(secret, a.redact(nested))
        self.assertIn('tool', a.redact(nested))
        self.assertNotIn('private-body', a.redact('-----BEGIN RSA PRIVATE KEY-----\nprivate-body\n-----END RSA PRIVATE KEY-----'))

    def test_partial_tail_waits_and_completed_line_is_collected(self):
        original_size = self.file.stat().st_size
        with self.file.open('a') as f: f.write('{"type":')
        end, events = a.read_batch(self.file, 0)
        self.assertEqual(original_size, end); self.assertEqual(2, len(events))
        with self.file.open('a') as f: f.write('"event_msg"}\n')
        self.assertGreater(a.read_batch(self.file, end)[0], end)

    def test_failure_preserves_exact_outbox_and_response_loss_is_retryable(self):
        with a.database() as db:
            a.stage(db, self.config, self.file)
            queued = db.execute('SELECT body FROM outbox').fetchone()[0]
            with patch.object(a, 'request', side_effect=RuntimeError('offline')):
                with self.assertRaises(RuntimeError): a.flush(db, self.config, 10)
            self.assertEqual(queued, db.execute('SELECT body FROM outbox').fetchone()[0])
            receipt = {'docId': 'ca1', 'kbId': 'kb-codex-records', 'duplicate': True}
            with patch.object(a, 'request', return_value=receipt) as req:
                a.flush(db, self.config, 10)
                self.assertEqual(json.loads(queued), req.call_args.args[2])
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM outbox').fetchone()[0])
            self.assertEqual(0, a.stage(db, self.config, self.file))

    def test_long_message_split_without_loss(self):
        text = '甲乙丙丁' * 60000
        self.write([self.records[0], self.msg('user', text)])
        with a.database() as db:
            a.stage(db, self.config, self.file)
            parts = [json.loads(r[0]) for r in db.execute('SELECT body FROM outbox ORDER BY id')]
        self.assertGreater(len(parts), 1)
        self.assertTrue(all(len(p['content']) <= a.LIMIT for p in parts))
        self.assertIn(text, ''.join(p['content'] for p in parts))
        self.assertEqual(list(range(len(parts))), [p['part'] for p in parts])

    def test_archived_move_and_exclusion_and_new_history(self):
        with a.database() as db:
            self.config['excludeProjects'] = ['/proj*']
            self.assertEqual(0, a.stage(db, self.config, self.file))
            self.config['excludeProjects'] = []
            self.config['since'] = '2026-09-19T11:00:00Z'
            a.stage(db, self.config, self.file)
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM outbox').fetchone()[0])
            archive = self.home / 'archived_sessions'; archive.mkdir()
            self.file.rename(archive / self.file.name)
            self.assertEqual(1, len(list(a.sources(self.config))))
            self.assertEqual(0, a.stage(db, self.config, archive / self.file.name))

    def test_bad_complete_line_does_not_advance(self):
        with self.file.open('a') as f: f.write('bad json\n')
        with a.database() as db:
            with self.assertRaises(ValueError): a.stage(db, self.config, self.file)
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM files').fetchone()[0])

    def test_https_and_no_embedded_credentials(self):
        for url in ('http://remote.invalid', 'https://user:pass@example.com', 'https://example.com?token=x'):
            with self.assertRaises(ValueError): a.endpoint(url)
        self.assertEqual('http://127.0.0.1:8080', a.endpoint('http://127.0.0.1:8080/'))


if __name__ == '__main__': unittest.main()
