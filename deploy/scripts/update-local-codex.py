#!/usr/bin/env python3
"""独立主机维护：官方稳定 CLI → 隔离验证 → 原子指针，不修改 Codex App 或登录文件。"""
import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

REGISTRY = 'https://registry.npmjs.org/'
MODEL = 'gpt-6-astra'
ENV = {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMSPEC', 'USERPROFILE', 'HOME', 'APPDATA',
       'LOCALAPPDATA', 'TEMP', 'TMP', 'PATH', 'PATHEXT', 'CODEX_HOME', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY'}


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, value):
    path = Path(path)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        temp = Path(handle.name)
    try:
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def read(path, default=None):
    return json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else default


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def stop(process):
    if process.poll() is None:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=10)
        else:
            process.kill()
        process.wait(timeout=10)


def process(args, **kwargs):
    return subprocess.Popen([str(arg) for arg in args], creationflags=(subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0),
                            env={k: v for k, v in os.environ.items() if k.upper() in ENV}, **kwargs)


def command(args, timeout=30, input_text=None):
    child = process(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        out, _ = child.communicate(None if input_text is None else input_text.encode(), timeout=timeout)
        if child.returncode != 0:
            raise RuntimeError('command_failed')  # 不把认证信息或供应商原文写到报告。
        if len(out) > 2_000_000:
            raise RuntimeError('output_too_large')
        return out.decode('utf-8')
    finally:
        stop(child)


@contextlib.contextmanager
def lock(root):
    with (root / 'update.lock').open('a+b') as handle:
        handle.seek(0); handle.write(b'0'); handle.flush(); handle.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == 'nt':
                handle.seek(0); msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def available_models(executable):
    child = process([executable, 'app-server'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    events = queue.Queue(maxsize=256)
    def reader():
        while True:
            line = child.stdout.readline(1_048_577)
            if not line or len(line) > 1_048_576:
                events.put(None); return
            events.put(line)
    threading.Thread(target=reader, daemon=True).start()
    deadline = time.monotonic() + 25
    def rpc(number, method, params):
        child.stdin.write((json.dumps(dict(id=number, method=method, params=params))+'\n').encode()); child.stdin.flush()
        while True:
            raw = events.get(timeout=max(0.01, deadline - time.monotonic()))
            if raw is None:
                raise RuntimeError('protocol_closed')
            event = json.loads(raw)
            if event.get('id') == number:
                if 'error' in event:
                    raise RuntimeError('protocol_error')
                return event['result']
    try:
        rpc(1, 'initialize', dict(clientInfo=dict(name='wao-updater', version='1'), capabilities={}))
        child.stdin.write(b'{"method":"initialized"}\n'); child.stdin.flush()
        account = rpc(2, 'account/read', dict(refreshToken=False))
        if account.get('account', {}).get('type') != 'chatgpt':
            raise RuntimeError('chatgpt_login_required')
        models, cursor = [], None
        for number in range(3, 23):
            params = dict(limit=100, includeHidden=False)
            if cursor:
                params['cursor'] = cursor
            result = rpc(number, 'model/list', params)
            models.extend(item['model'] for item in result.get('data', []) if not item.get('hidden'))
            cursor = result.get('nextCursor')
            if not cursor:
                if MODEL not in models:
                    raise RuntimeError('required_model_missing')
                return models
        raise RuntimeError('too_many_model_pages')
    finally:
        stop(child)


def probe(executable, root):
    """只使用合成材料；每个新候选版本一次文字、一次图片，验证实际产品参数和结果格式。"""
    version = command([executable, '--version']).strip()
    help_text = command([executable, 'exec', '--help'])
    if not all(flag in help_text for flag in ['--ignore-user-config', '--ignore-rules', '--ephemeral', '--json', '--output-last-message']):
        raise RuntimeError('required_flags_missing')
    models = available_models(executable)
    evidence = root / 'probes' / str(time.time_ns())
    evidence.mkdir(parents=True)
    args = [executable, 'exec', '--ignore-user-config', '--ignore-rules', '--ephemeral', '--skip-git-repo-check',
            '--sandbox', 'read-only', '--model', MODEL, '--disable', 'shell_tool', '--disable', 'multi_agent',
            '--disable', 'view_image', '-c', 'forced_login_method="chatgpt"', '-c', 'web_search="disabled"',
            '--cd', evidence, '--json', '--color', 'never', '--output-last-message', evidence / 'result.txt']
    command(args + ['--disable', 'image_generation', '-'], timeout=180,
            input_text='这是合成兼容验收。仅原样回复 WAO_CODEX_OK，不调用工具。')
    if (evidence / 'result.txt').read_text(encoding='utf-8').strip() != 'WAO_CODEX_OK':
        raise RuntimeError('text_probe_failed')
    raw = command(args + ['--enable', 'image_generation', '-'], timeout=300,
                  input_text='这是合成兼容验收。必须调用图片生成工具，只生成一张蓝色圆形的纯色简图，不要文字，不要其他工具。')
    thread = next((item.get('thread_id') for line in raw.splitlines() if line.startswith('{')
                   for item in [json.loads(line)] if item.get('type') == 'thread.started'), None)
    if not thread or not re.fullmatch(r'[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', thread):
        raise RuntimeError('image_thread_missing')
    generated = Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex'))) / 'generated_images'
    directory = generated / thread
    if directory.is_symlink() or not directory.is_dir() or directory.resolve().parent != generated.resolve():
        raise RuntimeError('image_directory_invalid')
    images = list(directory.glob('*.png'))
    if len(images) != 1 or images[0].is_symlink() or not 8 < images[0].stat().st_size <= 10 * 1024 * 1024:
        raise RuntimeError('image_result_invalid')
    if images[0].read_bytes()[:8] != b'\x89PNG\r\n\x1a\n':
        raise RuntimeError('image_png_invalid')
    # 留在原生成目录，按本次 thread 精确登记；不自动删除任何未知目录。
    report = dict(version=version, models=models, text='passed', image='passed', imagePath=str(images[0]), checkedAt=now())
    save(evidence / 'report.json', report)
    return report


def activate(root, executable, version, report):
    executable = executable.resolve()
    if not executable.is_relative_to((root / 'versions').resolve()):
        raise RuntimeError('unmanaged_executable')
    previous = read(root / 'current.json', {}).get('active')
    active = dict(path=str(executable.relative_to(root)), version=version, sha256=digest(executable))
    save(root / 'current.json', dict(schema=1, active=active, previous=previous, activatedAt=now()))
    save(root / 'status.json', dict(status='updated', version=version, checkedAt=now(), probe=report))


def rollback(root):
    state = read(root / 'current.json')
    previous = state.get('previous')
    if not previous:
        raise RuntimeError('no_previous_version')
    binary = (root / previous['path']).resolve()
    if not binary.is_relative_to((root / 'versions').resolve()) or digest(binary) != previous['sha256']:
        raise RuntimeError('previous_version_invalid')
    # 回退后暂停自动升级，避免下次计划立即重新切到有问题版本。
    save(root / 'paused.json', dict(reason='manual_rollback', at=now()))
    save(root / 'current.json', dict(schema=1, active=previous, previous=state['active'], activatedAt=now()))
    save(root / 'status.json', dict(status='rolled_back_paused', version=previous['version'], checkedAt=now()))


def update(root, npm_cli, force=False):
    if (root / 'paused.json').exists():
        return 'paused'
    last = read(root / 'status.json', {})
    if not force and last.get('checkedAt') and (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(last['checkedAt'])).total_seconds() < 43_200:
        return 'not_due'
    with urllib.request.urlopen(REGISTRY + '@openai%2fcodex/latest', timeout=30) as response:
        metadata = json.load(response)
    version = metadata['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise RuntimeError('stable_version_required')
    current = read(root / 'current.json', {}).get('active', {}).get('version')
    if current and tuple(map(int, version.split('.'))) <= tuple(map(int, current.split('.'))):
        save(root / 'status.json', dict(status='up_to_date', version=current, checkedAt=now()))
        return 'up_to_date'
    install = root / 'versions' / version
    # npm 自己校验官方包 integrity；不执行包脚本，不修改全局 npm 安装。
    command([shutil.which('node') or 'node', npm_cli, 'install', '--prefix', install, '--no-audit', '--no-fund',
             '--ignore-scripts', '--registry=' + REGISTRY, '@openai/codex@' + version], timeout=600)
    candidates = list((install / 'node_modules' / '@openai').glob('codex-*/vendor/*/bin/codex.exe'))
    if len(candidates) != 1:
        raise RuntimeError('windows_binary_not_found')
    binary = candidates[0]
    if command([binary, '--version']).strip() != 'codex-cli ' + version:
        raise RuntimeError('version_mismatch')
    report = probe(binary, root)
    activate(root, binary, version, report)
    return 'updated'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--npm-cli', type=Path)
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--rollback', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve(); root.mkdir(parents=True, exist_ok=True)
    with lock(root):
        try:
            if args.rollback:
                rollback(root); result = 'rolled_back_paused'
            else:
                if not args.npm_cli or not args.npm_cli.is_file():
                    raise RuntimeError('npm_cli_required')
                result = update(root, args.npm_cli, args.force)
            print(json.dumps(dict(status=result), ensure_ascii=False))
        except Exception as error:
            # 保留现有指针；错误只写种类与固定原因，绝不记录原始模型或 npm 响应。
            reason = str(error) if type(error) is RuntimeError else type(error).__name__
            save(root / 'status.json', dict(status='failed_previous_preserved', checkedAt=now(), reason=reason))
            print(json.dumps(dict(status='failed_previous_preserved', reason=reason)))
            return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
