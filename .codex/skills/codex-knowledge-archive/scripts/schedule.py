#!/usr/bin/env python3
"""Install/remove a current-user OS schedule; never includes credentials in commands."""
import argparse
import os
from pathlib import Path
import plistlib
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET
import archive

LABEL = 'cn.wayuwa.codex-archive'


def run(argv):
    subprocess.run(argv, check=True, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('install', 'remove'))
    args = parser.parse_args()
    script = str(Path(__file__).with_name('archive.py').resolve())
    state = str(archive.STATE.resolve())
    system = platform.system()
    if args.action == 'install':
        archive.config_read()  # Do not install a task which cannot run.
    if system == 'Darwin':
        path = Path.home() / 'Library/LaunchAgents' / (LABEL + '.plist')
        domain = 'gui/%s' % os.getuid()
        if args.action == 'remove':
            if path.exists():
                run(['launchctl', 'bootout', domain, str(path)])
                path.unlink()
        else:
            if path.exists():
                raise RuntimeError('计划已存在；先 remove 再重新 install，不覆盖现有任务')
            path.parent.mkdir(parents=True, exist_ok=True)
            data = {'Label': LABEL, 'ProgramArguments': [sys.executable, script, 'sync'],
                'EnvironmentVariables': {'WAO_CODEX_ARCHIVE_HOME': state}, 'StartInterval': 300,
                'RunAtLoad': True, 'StandardOutPath': state + '/scheduler.log',
                'StandardErrorPath': state + '/scheduler-error.log', 'Umask': 0o077}
            path.write_bytes(plistlib.dumps(data)); path.chmod(0o600)
            try:
                run(['launchctl', 'bootstrap', domain, str(path)])
            except Exception:
                path.unlink(); raise
    elif system == 'Linux':
        path = Path.home() / '.config/systemd/user'
        service, timer = path / (LABEL + '.service'), path / (LABEL + '.timer')
        if args.action == 'remove':
            run(['systemctl', '--user', 'disable', '--now', LABEL + '.timer'])
            service.unlink(missing_ok=True); timer.unlink(missing_ok=True)
            run(['systemctl', '--user', 'daemon-reload'])
        else:
            if service.exists() or timer.exists():
                raise RuntimeError('计划已存在；先 remove 再重新 install')
            # systemd specifiers must be escaped even inside quoted arguments.
            quote = lambda x: '"' + x.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
            path.mkdir(parents=True, exist_ok=True)
            service.write_text('[Unit]\nDescription=Codex knowledge archive\n[Service]\nType=oneshot\nUMask=0077\nEnvironment=' + quote('WAO_CODEX_ARCHIVE_HOME=' + state) + '\nExecStart=' + ' '.join(map(quote, [sys.executable, script, 'sync'])) + '\n')
            timer.write_text('[Unit]\nDescription=Codex archive every five minutes\n[Timer]\nOnBootSec=1m\nOnUnitActiveSec=5m\n[Install]\nWantedBy=timers.target\n')
            run(['systemctl', '--user', 'daemon-reload'])
            run(['systemctl', '--user', 'enable', '--now', LABEL + '.timer'])
    elif system == 'Windows':
        if args.action == 'remove':
            run(['schtasks', '/Delete', '/TN', LABEL, '/F'])
        else:
            # Use an XML task: paths, Unicode and spaces stay separate from shell parsing.
            ns = 'http://schemas.microsoft.com/windows/2004/02/mit/task'
            ET.register_namespace('', ns)
            def element(parent, name, text=None, **attrs):
                e = ET.SubElement(parent, '{%s}%s' % (ns, name), attrs); e.text = text; return e
            root = ET.Element('{%s}Task' % ns, {'version': '1.2'})
            trigger = element(element(root, 'Triggers'), 'TimeTrigger')
            element(trigger, 'StartBoundary', '2026-01-01T00:00:00')
            repeat = element(trigger, 'Repetition'); element(repeat, 'Interval', 'PT5M'); element(repeat, 'StopAtDurationEnd', 'false')
            principal = element(element(root, 'Principals'), 'Principal', id='Author')
            user = subprocess.check_output(['whoami'], text=True).strip()
            element(principal, 'UserId', user); element(principal, 'LogonType', 'InteractiveToken')
            element(principal, 'RunLevel', 'LeastPrivilege')
            settings = element(root, 'Settings'); element(settings, 'MultipleInstancesPolicy', 'IgnoreNew')
            element(settings, 'StartWhenAvailable', 'true'); element(settings, 'DisallowStartIfOnBatteries', 'false')
            element(settings, 'StopIfGoingOnBatteries', 'false'); element(settings, 'ExecutionTimeLimit', 'PT30M')
            action = element(element(root, 'Actions', Context='Author'), 'Exec')
            element(action, 'Command', sys.executable)
            element(action, 'Arguments', subprocess.list2cmdline([script, '--state', state, 'sync']))
            path = archive.STATE / 'task.xml'
            path.write_bytes(ET.tostring(root, encoding='utf-16', xml_declaration=True))
            run(['schtasks', '/Create', '/TN', LABEL, '/XML', str(path)])
    else:
        raise RuntimeError('不支持此操作系统；可手动定时执行 archive.py sync')
    print('%s: %s' % (args.action, LABEL))


if __name__ == '__main__':
    main()
