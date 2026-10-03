#!/usr/bin/env python3
"""运行小记服务端正文 AI 隔离回归并记录证据；不连接真实数据库或模型。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SUITES = (
    'cn.wayuwa.wao.service.NoteAiWorkflowServiceTest',
    'cn.wayuwa.wao.service.NoteAiWorkflowTransactionTest',
    'cn.wayuwa.wao.service.NoteAiWorkflowQueueTest',
    'cn.wayuwa.wao.mapper.KnowledgeTrashMapperIntegrationTest',
    'cn.wayuwa.wao.service.TextPolishJobServiceTest',
    'cn.wayuwa.wao.service.AiAssistantServiceTest',
    'cn.wayuwa.wao.mapper.AiAssistantRunMapperIntegrationTest',
    'cn.wayuwa.wao.service.DeepSeekApiClientTest',
    'cn.wayuwa.wao.FlywayMigrationsTest',
    'cn.wayuwa.wao.service.LocalNoteAiServiceTest',
    'cn.wayuwa.wao.service.LocalNoteCodexProcessTest',
    'cn.wayuwa.wao.service.ArticleImageServiceTest',
    'cn.wayuwa.wao.controller.ArticleImageControllerTest',
    'cn.wayuwa.wao.controller.DecisionControllerIntegrationTest',
)


def inspect_junit(directory, started):
    rows = []
    for name in SUITES:
        path = directory / ('TEST-' + name + '.xml')
        if not path.is_file() or path.stat().st_mtime < started:
            raise ValueError('缺少本轮测试结果：' + name)
        root = ET.parse(path).getroot()
        counts = {key: int(root.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
        rows.append({'suite': name, **counts})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default='mvn', help='既有 Maven 可执行文件或完整路径')
    args = parser.parse_args()
    output = ROOT / 'wao-server' / 'target' / 'notes-ai-regression' / (
        datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8])
    output.mkdir(parents=True)
    report = {'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'failed',
              'scope': 'isolated-note-ai-text-and-images', 'realAiVerified': False,
              'deploymentVerified': False, 'steps': []}
    env = os.environ.copy()
    env['PYTHONIOENCODING'] = 'utf-8'
    def run(name, command, cwd):
        began = time.time()
        log = output / (name + '.log')
        print('运行 ' + name, flush=True)
        with log.open('wb') as stream:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=600)
        report['steps'].append({'name': name, 'exitCode': result.returncode,
                                'seconds': round(time.time() - began, 2), 'log': log.name,
                                'sha256': hashlib.sha256(log.read_bytes()).hexdigest()})
        if result.returncode:
            raise ValueError(name + ' 失败，见 ' + str(log))
        return began
    try:
        report['gitSha'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        report['worktreeDirty'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip())
        maven = shutil.which(args.maven)
        if not maven:
            raise ValueError('找不到 Maven；请用 --maven 指向已有安装')
        began = run('server', [maven, '-q', '-pl', 'wao-server', '-am',
                    '-Dtest=' + ','.join(name.rsplit('.', 1)[-1] for name in SUITES),
                    '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], ROOT)
        rows = inspect_junit(ROOT / 'wao-server' / 'target' / 'surefire-reports', began)
        report['junit'] = rows
        if any(row['tests'] < 1 or row['errors'] or row['failures'] or row['skipped'] for row in rows):
            raise ValueError('服务端存在失败、跳过或空测试集')
        report['status'] = 'passed'
    except (OSError, ValueError, subprocess.SubprocessError, ET.ParseError) as exc:
        report['error'] = str(exc)
    finally:
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(str(output / 'report.json'), flush=True)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
