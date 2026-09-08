#!/usr/bin/env python3
"""Initialize WAO's integration after its Flyway migration, without logging keys.

Only first installation is written. Later schedule / scope edits belong to the
WAO UI. Uses the already configured WAO database and vault encryption key.
"""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def configure(owner, modules, private_file):
    if os.geteuid()!=0 or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',owner):
        raise SystemExit('Run as root with a valid existing WAO member ID')
    for module in modules:
        if not re.fullmatch(r'[a-z-]{1,64}',module):raise SystemExit('Invalid module')
    p=json.loads(Path(private_file).read_text())
    request=urllib.request.Request('http://127.0.0.1:18780/api/v1/knowledge-bases/'+p['kbId'],headers={'X-API-Key':p['apiKey']})
    with urllib.request.urlopen(request,timeout=15) as response:
        kb=json.load(response)['data']
    if not kb['indexing_strategy'].get('wiki_enabled') or kb['indexing_strategy'].get('vector_enabled'):
        raise SystemExit('Target is not Wiki-only')
    e=dict(x.split('=',1) for x in json.loads(subprocess.check_output(['docker','inspect','wao-master-wao-server-1']))[0]['Config']['Env'])
    vault_key=base64.b64decode(e.get('WAO_VAULT_KEY') or e.get('VAULT_KEY') or '')
    if len(vault_key)!=32:raise SystemExit('WAO vault key is missing')
    nonce=os.urandom(12)
    ciphertext=AESGCM(vault_key).encrypt(nonce,p['apiKey'].encode(),None)
    def literal(value):return "CONVERT(0x"+value.encode().hex()+" USING utf8mb4)"
    host='127.0.0.1' if e['MYSQL_HOST']=='host.docker.internal' else e['MYSQL_HOST']
    command=['mysql','--batch','--skip-column-names','-h',host,'-P',e.get('MYSQL_PORT','3306'),'-u',e['DB_USER'],e['DB_NAME']]
    environment=dict(os.environ,MYSQL_PWD=e['DB_PASS'])
    def sql(query):
        result=subprocess.run(command,input=query,text=True,capture_output=True,env=environment)
        if result.returncode:raise SystemExit('WAO configuration database operation failed; no private values printed')
        return result.stdout.strip()
    if sql("SELECT COUNT(*) FROM weknora_sync_config;")!='0':
        print('Existing WAO sync configuration preserved; edit it in WAO.')
        return
    if sql("SELECT COUNT(*) FROM member WHERE id="+literal(owner)+";")!='1':raise SystemExit('WAO owner does not exist')
    # Shanghai datetime, same representation as AutomationService / the WAO UI.
    now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    next_fire=now.replace(hour=2,minute=0,second=0,microsecond=0)
    if next_fire<=now:next_fire+=datetime.timedelta(days=1)
    query=f"""START TRANSACTION;
    SELECT cfg_key FROM system_setting WHERE cfg_key='weknora-sync-lock' FOR UPDATE;
    INSERT INTO weknora_sync_config(id,owner_member_id,kb_id,api_key_cipher,api_key_nonce,modules_json,batch_limit,sync_deletes)
      VALUES('default',{literal(owner)},{literal(p['kbId'])},0x{ciphertext.hex()},0x{nonce.hex()},{literal(json.dumps(modules))},20,true);
    INSERT INTO automation_job(id,name,icon,schedule_label,cron_expr,status,last_run_label,description,source,target,steps_json,next_fire_at,ord,handler)
      VALUES('weknora-content-sync',{literal('WAO 内容同步至 WeKnora')},'ri-book-open-line',{literal('每天 02:00（北京时间）')},'0 0 2 * * *',{literal('正常')},{literal('尚未运行')},{literal('增量同步，详细进度在 WeKnora 内容同步页面')},'WAO','WeKnora',{literal(json.dumps(['读取所选范围','核对并同步变化内容','记录传输结果']))},'{next_fire.strftime('%Y-%m-%d %H:%M:%S')}',999,'weknora-content-sync');
    COMMIT;"""
    sql(query)
    print('WAO sync configured: encrypted key, daily 02:00 Asia/Shanghai, batch limit 20, modules:',len(modules))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--owner-member-id',required=True)
    parser.add_argument('--modules',required=True,help='Comma separated source adapter IDs from the deployed version')
    parser.add_argument('--private-file',default='/opt/wao-weknora/upstream/weknora-sync.private.json')
    args=parser.parse_args()
    configure(args.owner_member_id,args.modules.split(','),args.private_file)
