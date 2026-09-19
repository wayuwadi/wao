#!/usr/bin/env python3
"""Codex visible-history archive. Python 3.9+, standard library only."""
import argparse
import contextlib
import datetime as dt
import fnmatch
import getpass
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

VERSION = '1.0.0'
STATE = Path(os.environ.get('WAO_CODEX_ARCHIVE_HOME', str(Path.home() / '.wao-codex-archive')))
LIMIT = 70000
SECRET_KEY = re.compile(r'(?i)(password|passwd|secret|token|api[_-]?key|authorization|cookie|private[_-]?key|credential)')
PATTERNS = [
    (re.compile(r'-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)'), '[PRIVATE KEY REDACTED]'),
    (re.compile(r'(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9_./+=-]+'), '[AUTH REDACTED]'),
    (re.compile(r'\b(?:wao_mcp_|sk-(?:proj-)?|gh[pousr]_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]{12,}'), '[KEY REDACTED]'),
    (re.compile(r'\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+'), '[JWT REDACTED]'),
    (re.compile(r'(?i)(\b[\w.-]{0,128}(?:password|passwd|secret|token|api[_-]?key|cookie|credential|db_pass)[\w.-]{0,128}["\x27]?\s*[:=]\s*)(?:"[^"\n]*"|\x27[^\x27\n]*\x27|[^\s,;]+)'), r'\1[REDACTED]'),
    (re.compile(r'(?i)(https?://)[^/\s:@]+:[^/\s@]+@'), r'\1[REDACTED]@'),
    (re.compile(r'(?i)data:[^\s;,]+;base64,[A-Za-z0-9+/=]+'), '[BINARY OMITTED]'),
    (re.compile(r'(?im)^.*(?:authorization|set-cookie|cookie)\s*:.*$'), '[HEADER REDACTED]'),
]


def redact(value):
    if isinstance(value, dict):
        if value.get('type') in ('image', 'audio', 'input_image', 'input_audio') or ('mimeType' in value and 'data' in value):
            return '[BINARY OMITTED]'
        return {k: '[REDACTED]' if SECRET_KEY.search(k) else redact(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    if not isinstance(value, str):
        return value
    # Tool arguments / outputs often contain JSON strings; sanitize before flattening.
    if value.lstrip().startswith(('{', '[')):
        try:
            return json.dumps(redact(json.loads(value)), ensure_ascii=False)
        except (ValueError, RecursionError):
            pass
    for pattern, replacement in PATTERNS:
        value = pattern.sub(replacement, value)
    return value


def visible(record):
    """An allowlist deliberately excludes model reasoning and injected context."""
    if record.get('type') != 'response_item':
        return None
    p = record.get('payload', {})
    if not isinstance(p, dict):
        raise ValueError('不支持的会话消息结构')
    kind = p.get('type')
    if kind == 'message':
        role = p.get('role')
        if role not in ('user', 'assistant') or p.get('channel') == 'analysis' or p.get('phase') == 'analysis':
            return None
        chunks = []
        for item in p.get('content', []):
            if not isinstance(item, dict):
                raise ValueError('不支持的可见消息结构')
            if item.get('type') in ('input_text', 'output_text', 'text'):
                text = item.get('text', '')
                # Codex can serialize environment and project instructions as user context.
                if text.lstrip().startswith(('# AGENTS.md instructions', '<environment_context>', '<permissions instructions>', '<INSTRUCTIONS>', '<recommended_plugins>', '<app-context>')):
                    continue
                chunks.append(text)
            elif 'image' in item.get('type', '') or 'audio' in item.get('type', ''):
                chunks.append('[图片或音频附件：二进制未采集]')
        text = '\n'.join(chunks)
    elif kind in ('function_call', 'custom_tool_call'):
        role = 'tool_call'
        text = json.dumps({k: p[k] for k in ('name', 'call_id', 'arguments', 'input') if k in p}, ensure_ascii=False)
    elif kind in ('function_call_output', 'custom_tool_call_output'):
        role = 'tool_result'
        text = json.dumps({k: p[k] for k in ('call_id', 'output') if k in p}, ensure_ascii=False)
    else:
        return None
    if not text.strip():
        return None
    return {'timestamp': str(record.get('timestamp', '')), 'role': role, 'text': redact(text)}


def private_dir(path):
    path.mkdir(parents=True, exist_ok=True)
    if os.name != 'nt':
        path.chmod(0o700)


def write_json(path, data):
    private_dir(path.parent)
    tmp = path.with_suffix('.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        if os.name != 'nt':
            os.chmod(tmp, 0o600)
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


@contextlib.contextmanager
def locked():
    private_dir(STATE)
    with open(STATE / 'sync.lock', 'a+b') as f:
        try:
            if os.name == 'nt':
                import msvcrt
                if f.tell() == 0:
                    f.write(b'0'); f.flush()
                f.seek(0); msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise RuntimeError('已有归档同步正在运行') from None
        try:
            yield
        finally:
            if os.name == 'nt':
                f.seek(0); msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(f.fileno(), fcntl.LOCK_UN)


def database():
    private_dir(STATE)
    db = sqlite3.connect(STATE / 'state.sqlite')
    if os.name != 'nt':
        os.chmod(STATE / 'state.sqlite', 0o600)
    db.executescript('''
        CREATE TABLE IF NOT EXISTS files (key TEXT PRIMARY KEY, offset INTEGER NOT NULL, error TEXT);
        CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, data TEXT NOT NULL);
    ''')
    return db


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def endpoint(url):
    p = urllib.parse.urlparse(url)
    local = p.hostname in ('127.0.0.1', 'localhost', '::1')
    if p.scheme != 'https' and not (p.scheme == 'http' and local):
        raise ValueError('服务器必须使用 HTTPS；仅本机验收允许 HTTP')
    if not p.netloc or p.username or p.password or p.query or p.fragment or p.path not in ('', '/'):
        raise ValueError('请填写不含路径、账号或查询参数的 WAO 站点地址')
    return url.rstrip('/')


def request(config, path, body=None):
    token_file = Path(config['tokenFile'])
    if os.name != 'nt' and token_file.stat().st_mode & 0o077:
        raise RuntimeError('令牌文件权限过宽，请设为仅当前用户可读（chmod 600）')
    auth_value = token_file.read_text(encoding='utf-8').strip()
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(endpoint(config['url']) + path, data=data,
        headers={'Authorization': 'Bearer ' + auth_value, 'Content-Type': 'application/json'})
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=25) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        # Do not echo server bodies, URL query strings or any request headers.
        raise RuntimeError('WAO 返回 HTTP %s；检查服务版本、设备授权或令牌，断点已保留' % exc.code) from None
    if result.get('code') != 0 or not isinstance(result.get('data'), dict):
        raise RuntimeError('WAO 未确认归档成功，断点已保留')
    return result['data']


def config_read():
    return json.loads((STATE / 'config.json').read_text(encoding='utf-8'))


def sources(config):
    home = Path(config['codexHome']).expanduser().resolve()
    for name in ('sessions', 'archived_sessions'):
        folder = home / name
        if folder.exists():
            for p in sorted(folder.rglob('*.jsonl')):
                # Do not follow links out of the two approved history roots.
                if p.is_symlink() or folder.resolve() not in p.resolve().parents:
                    continue
                yield p


def metadata(path):
    with path.open('rb') as f:
        first = f.readline()
    try:
        item = json.loads(first)
        if item.get('type') != 'session_meta':
            raise ValueError()
        p = item['payload']
        sid = p.get('id') or p.get('session_id')
        if not isinstance(sid, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', sid):
            raise ValueError()
        return sid, str(p.get('cwd') or '(无工作目录)')
    except (ValueError, KeyError, TypeError):
        raise ValueError('不支持的会话元数据，未上传该文件') from None


def read_batch(path, offset, since=None):
    events = []
    chars = 0
    end = offset
    with path.open('rb') as f:
        f.seek(offset)
        while True:
            pos = f.tell()
            line = f.readline()
            if not line or not line.endswith(b'\n'):
                break  # A live writer may not have completed this record yet.
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError()
            except (ValueError, UnicodeError):
                raise ValueError('会话在字节 %s 解析失败；保留断点等待修复' % pos) from None
            event = visible(row)
            if event and (not since or event['timestamp'] >= since):
                events.append(event)
                chars += len(event['text'])
            end = f.tell()
            if chars >= LIMIT:
                break
    return end, events


def stage(db, config, path):
    sid, project = metadata(path)
    if any(fnmatch.fnmatch(project, pattern) for pattern in config.get('excludeProjects', [])):
        return 0
    key = sid
    row = db.execute('SELECT offset FROM files WHERE key=?', (key,)).fetchone()
    offset = row[0] if row else 0
    if path.stat().st_size < offset:
        raise ValueError('会话文件缩短；为避免重复或错位同步，请核对原文件')
    end, events = read_batch(path, offset, config.get('since'))
    if end == offset:
        return 0
    content = '\n\n'.join('### %s · %s\n\n%s' % (e['timestamp'], e['role'], e['text']) for e in events)
    # Split after redaction, so a private key spanning a split is never exposed.
    parts = [content[i:i + LIMIT] for i in range(0, len(content), LIMIT)]
    title = next((e['text'].splitlines()[0] for e in events if e['role'] == 'user'), '工作过程')
    title = ('Codex · ' + title)[:140]
    with db:
        for part, text in enumerate(parts):
            body = {'deviceId': config['deviceId'], 'deviceName': redact(config['deviceName'])[:80],
                'sessionId': sid, 'project': redact(project)[:1000], 'source': redact(str(path))[-1000:],
                'startOffset': offset, 'endOffset': end, 'part': part,
                'title': (title + ' · %s/%s' % (offset, part + 1))[:180], 'content': text}
            db.execute('INSERT INTO outbox(body) VALUES (?)', (json.dumps(body, ensure_ascii=False),))
        # Staging cursor and outbox are one transaction. Acknowledgements are separate.
        db.execute('INSERT OR REPLACE INTO files(key,offset,error) VALUES (?,?,NULL)', (key, end))
    return end - offset


def flush(db, config, remaining):
    sent = 0
    for queue_id, body in db.execute('SELECT id,body FROM outbox ORDER BY id LIMIT ?', (remaining,)).fetchall():
        receipt = request(config, '/api/codex-archive/batches', json.loads(body))
        if not receipt.get('docId') or receipt.get('kbId') != 'kb-codex-records':
            raise RuntimeError('服务器回执不完整；保留待发送批次')
        with db:
            db.execute('INSERT OR REPLACE INTO receipts(id,data) VALUES (?,?)',
                (receipt['docId'], json.dumps(receipt, ensure_ascii=False)))
            db.execute('DELETE FROM outbox WHERE id=?', (queue_id,))
        sent += 1
    return sent


def sync(config, max_batches=200):
    if config.get('paused'):
        return {'paused': True}
    with locked(), contextlib.closing(database()) as db:
        identity = request(config, '/api/codex-archive/status')
        if identity.get('protocol') != '1' or identity.get('memberId') != config['memberId']:
            raise RuntimeError('服务器协议或账号已变化；请重新配置独立归档状态目录')
        sent = flush(db, config, max_batches)
        errors = []
        for path in sources(config):
            if sent >= max_batches:
                break
            try:
                while stage(db, config, path):
                    sent += flush(db, config, max_batches - sent)
                    if sent >= max_batches:
                        break
            except (ValueError, OSError) as exc:
                # Print file names only, not transcript text or tokens.
                errors.append({'file': path.name, 'error': str(exc) if isinstance(exc, ValueError) else type(exc).__name__})
                continue
        pending = db.execute('SELECT COUNT(*) FROM outbox').fetchone()[0]
        result = {'sent': sent, 'pending': pending, 'errors': errors, 'time': dt.datetime.now(dt.timezone.utc).isoformat()}
        write_json(STATE / 'status.json', result)
        return result


def configure(args):
    private_dir(STATE)
    old = config_read() if (STATE / 'config.json').exists() else None
    if old:
        raise ValueError('已配置；请编辑 config.json 保留 deviceId 与账号，切换账号必须使用新的状态目录')
    token_path = Path(args.token_file).expanduser().resolve() if args.token_file else STATE / 'token'
    if not args.token_file and not token_path.exists():
        auth_value = getpass.getpass('粘贴开启归档权限的 WAO 设备令牌（不回显）：').strip()
        if not auth_value.startswith('wao_mcp_'):
            raise ValueError('不是 WAO 设备令牌')
        fd = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write(auth_value)
    config = {'url': endpoint(args.url), 'tokenFile': str(token_path), 'deviceId': str(uuid.uuid4()),
        'deviceName': args.device or platform.node() or 'Codex device',
        'codexHome': str(Path(args.codex_home).expanduser().resolve()), 'excludeProjects': args.exclude,
        'since': None if args.history == 'all' else dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z'),
        'paused': False, 'version': VERSION}
    identity = request(config, '/api/codex-archive/status')
    config['memberId'] = identity['memberId']
    write_json(STATE / 'config.json', config)
    print(json.dumps({'configured': True, 'history': args.history, 'deviceId': config['deviceId']}, ensure_ascii=False))


def export(args):
    cfg = {'codexHome': args.codex_home}
    out = Path(args.output).expanduser().resolve()
    private_dir(out)
    count = 0
    for path in sources(cfg):
        sid, project = metadata(path)
        if any(fnmatch.fnmatch(project, p) for p in args.exclude):
            continue
        offset = 0
        with open(out / (sid + '.jsonl'), 'w', encoding='utf-8') as jf, open(out / (sid + '.md'), 'w', encoding='utf-8') as mf:
            if os.name != 'nt':
                os.chmod(jf.name, 0o600); os.chmod(mf.name, 0o600)
            mf.write('# Codex 工作记录\n\n会话：%s\n\n工作目录：%s\n\n' % (sid, redact(project)))
            while True:
                end, events = read_batch(path, offset)
                for event in events:
                    event.update({'sessionId': sid, 'project': redact(project)})
                    jf.write(json.dumps(event, ensure_ascii=False) + '\n')
                    mf.write('## %s · %s\n\n%s\n\n' % (event['timestamp'], event['role'], event['text']))
                if end == offset:
                    break
                offset = end
        count += 1
    print(json.dumps({'exportedSessions': count, 'output': str(out)}, ensure_ascii=False))


def main():
    global STATE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=STATE)
    sub = parser.add_subparsers(dest='command', required=True)
    cfg = sub.add_parser('configure')
    cfg.add_argument('--url', default='https://wao.wayuwa.cn')
    cfg.add_argument('--token-file')
    cfg.add_argument('--device')
    cfg.add_argument('--history', choices=('all', 'new'), required=True)
    cfg.add_argument('--codex-home', default=os.environ.get('CODEX_HOME', str(Path.home() / '.codex')))
    cfg.add_argument('--exclude', action='append', default=[])
    run = sub.add_parser('sync'); run.add_argument('--max-batches', type=int, default=200)
    sub.add_parser('status'); sub.add_parser('pause'); sub.add_parser('resume')
    ex = sub.add_parser('export'); ex.add_argument('--output', required=True)
    ex.add_argument('--codex-home', default=os.environ.get('CODEX_HOME', str(Path.home() / '.codex')))
    ex.add_argument('--exclude', action='append', default=[])
    args = parser.parse_args()
    STATE = args.state.expanduser().resolve()
    try:
        if args.command == 'configure':
            configure(args)
        elif args.command == 'export':
            export(args)
        elif args.command in ('pause', 'resume'):
            with locked():
                cfg = config_read(); cfg['paused'] = args.command == 'pause'; write_json(STATE / 'config.json', cfg)
            print(json.dumps({'paused': cfg['paused']}))
        elif args.command == 'status':
            cfg = config_read()
            with contextlib.closing(database()) as db:
                print(json.dumps({'paused': cfg['paused'], 'url': cfg['url'], 'deviceId': cfg['deviceId'],
                    'queued': db.execute('SELECT COUNT(*) FROM outbox').fetchone()[0],
                    'documents': db.execute('SELECT COUNT(*) FROM receipts').fetchone()[0],
                    'lastRun': json.loads((STATE / 'status.json').read_text()) if (STATE / 'status.json').exists() else None}, ensure_ascii=False))
        else:
            result = sync(config_read(), max(1, args.max_batches))
            print(json.dumps(result, ensure_ascii=False))
            return 1 if result.get('errors') else 0
        return 0
    except (ValueError, RuntimeError, OSError, sqlite3.Error, KeyError) as exc:
        # Avoid generic exception strings: network errors can contain URLs and local auth paths.
        message = str(exc) if isinstance(exc, (ValueError, RuntimeError)) else type(exc).__name__
        print('归档未完成：' + redact(message), file=sys.stderr)
        write_json(STATE / 'status.json', {'error': redact(message), 'time': dt.datetime.now(dt.timezone.utc).isoformat()})
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
