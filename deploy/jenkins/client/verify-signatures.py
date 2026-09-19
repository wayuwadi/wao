"""在切换下载清单前确认两个更新包仍由旧客户端信任的公钥签名。"""
import base64
import json
from pathlib import Path
import subprocess
import tempfile


def verify(root, raw):
    config = json.loads((root / 'wao-client/src-tauri/tauri.conf.json').read_text())
    files = list(raw.rglob('*-setup.exe')) + list(raw.rglob('*.app.tar.gz'))
    if len(files) != 2 or len(list(raw.rglob('*-setup.exe'))) != 1:
        raise ValueError('必须包含一个 Windows 安装包和一个 Mac 更新归档')
    with tempfile.TemporaryDirectory(prefix='wao-signature-check-') as directory:
        public = Path(directory) / 'public.key'
        public.write_bytes(base64.b64decode(config['plugins']['updater']['pubkey'], validate=True))
        for index, artifact in enumerate(files):
            signature = Path(directory) / f'{index}.sig'
            signature.write_bytes(base64.b64decode(Path(str(artifact) + '.sig').read_text().strip(), validate=True))
            subprocess.run(['minisign', '-Vm', str(artifact), '-p', str(public), '-x', str(signature)], check=True)


if __name__ == '__main__':
    verify(Path.cwd(), Path('client-release/raw'))
