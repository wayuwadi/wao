#!/usr/bin/env python3
"""Stage games through the already authorized cloud command helper, without new credentials.
Requires the existing public-release coordinator to be owned by this exact version.
This command never activates a main-site release or touches backend processes.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import time


def checked_sha(value):
    if not re.fullmatch(r'[a-f0-9]{40}', value): raise ValueError('Invalid commit SHA')
    return value


def load(name, path):
    spec=importlib.util.spec_from_file_location(name,path); module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


def chunk_script(destination, chunk, offset, digest):
    # destination is constructed from a validated SHA and fixed filenames, never file input.
    payload=base64.b64encode(chunk).decode()
    return f"""python3 - <<'PY'
import base64,hashlib
from pathlib import Path
p=Path({str(destination)!r}); p.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
data=base64.b64decode({payload!r}); old=p.read_bytes() if p.exists() else b''
offset={offset}
if len(old)==offset:
    with p.open('ab') as f: f.write(data)
elif len(old)>=offset+len(data) and old[offset:offset+len(data)]==data:
    pass
else: raise RuntimeError('Staged file conflict; refusing overwrite')
assert hashlib.sha256(p.read_bytes()[:offset+len(data)]).hexdigest()=={digest!r}
print('chunk-verified')
PY"""


def coordinator(runtime, version):
    state=json.loads((runtime/'public-release-coordinator.json').read_text(encoding='utf-8'))
    if state.get('state')!='publishing' or state.get('sha')!=version or state.get('authorizedByUser') is not True:
        raise ValueError('This version does not own the current authorized release batch')
    return state


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['stage','activate'])
    p.add_argument('--runtime',type=Path,required=True)
    p.add_argument('--version',type=checked_sha,required=True)
    p.add_argument('--archive',type=Path,required=True)
    p.add_argument('--expected-catalog',type=int,required=True)
    args=p.parse_args(); runtime=args.runtime.resolve(); state=coordinator(runtime,args.version)
    previous=checked_sha(state['previousSha']); version=args.version
    if args.expected_catalog<0: raise ValueError('Invalid expected catalog revision')
    transport=load('authorized_cloud_transport',runtime/'cloud_access.py')
    remote=f'/root/wao-game-releases/{version}'
    invocations=[]
    def run(script,name):
        coordinator(runtime,version)
        invocation=transport.cloud_run(script,'wao-dino-'+name+'-'+version[:8],timeout=90)
        invocations.append(invocation)
        deadline=time.monotonic()+110
        while time.monotonic()<deadline:
            records=transport.cloud_result(invocation)
            if records and records[0]['status'] in ('Success','Failed','Stopped','Timeout','Cancelled'):
                result=records[0]
                if result['status']!='Success' or result['exitCode']!=0 or result.get('dropped'):
                    raise RuntimeError('Cloud step failed: '+name+'; invocation='+invocation)
                print(name+': '+result['output'].strip(),flush=True); return
            time.sleep(2)
        raise RuntimeError('Cloud outcome unknown; inspect invocation before retry: '+invocation)
    root=Path(__file__).resolve().parents[2]
    if args.action=='stage':
        run(f"set -eu; test \"$(readlink /var/www/wao/windows1-dev/dist)\" = /var/www/wao/releases/{previous}; echo current-frontend-matched",'guard')
        files={'game-dino.tgz':args.archive,'game-release.py':Path(__file__).with_name('game-release.py'),'game-routing.py':Path(__file__).with_name('game-routing.py'),'static-release.py':root/'deploy/scripts/static-release.py'}
        for name,path in files.items():
            data=path.read_bytes()
            for offset in range(0,len(data),6000):
                chunk=data[offset:offset+6000]
                run(chunk_script(remote+'/'+name,chunk,offset,hashlib.sha256(data[:offset+len(chunk)]).hexdigest()),name.replace('.','-')+'-'+str(offset))
            run(f"set -eu; echo '{hashlib.sha256(data).hexdigest()}  {remote}/{name}' | sha256sum -c -",name.replace('.','-')+'-verify')
        run(f"python3 {remote}/game-release.py install {remote}/game-dino.tgz /var/www/wao/play-assets dino {version}",'install')
    else:
        # Existing gateway configuration is preserved byte-for-byte outside the marked game block.
        run(f"""set -eu
test "$(readlink /var/www/wao/windows1-dev/dist)" = /var/www/wao/releases/{previous}
python3 {remote}/game-release.py verify /var/www/wao/play-assets dino {version}
python3 - <<'PY'
from pathlib import Path
import json
p=Path('/var/www/wao/play-assets/catalog.json')
revision=json.loads(p.read_text())['revision'] if p.exists() else 0
assert revision=={args.expected_catalog}, 'Catalog changed; inspect release owner'
backup=Path('{remote}/catalog-before.json')
if not backup.exists(): backup.write_text(p.read_text() if p.exists() else '{{"protocol":1,"revision":0,"previousRevision":null,"games":{{}}}}')
conf=Path('/etc/nginx/conf.d/wao.wayuwa.cn.conf'); old=Path('{remote}/nginx-before-games.conf')
assert '/_wao_release/' not in conf.read_text(), 'Another transfer is still open'
if old.exists(): assert old.read_bytes()==conf.read_bytes(), 'Configuration changed since previous attempt'
else: old.write_bytes(conf.read_bytes())
PY
python3 {remote}/game-routing.py /etc/nginx/conf.d/wao.wayuwa.cn.conf {remote}/nginx-with-games.conf
cp {remote}/nginx-with-games.conf /etc/nginx/conf.d/wao.wayuwa.cn.conf
if ! nginx -t; then cp {remote}/nginx-before-games.conf /etc/nginx/conf.d/wao.wayuwa.cn.conf; exit 1; fi
if ! python3 {remote}/game-release.py activate /var/www/wao/play-assets dino {version} --expected-revision {args.expected_catalog}; then
  cp {remote}/nginx-before-games.conf /etc/nginx/conf.d/wao.wayuwa.cn.conf
  exit 1
fi
systemctl reload nginx
echo game-route-and-catalog-active
""",'activate')
    report={'action':args.action,'version':version,'previousFrontend':previous,'invocations':invocations,'backendChanged':False,'newCredentials':False}
    (runtime/f'dino-{args.action}-{version}.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report),flush=True)

if __name__=='__main__': main()
