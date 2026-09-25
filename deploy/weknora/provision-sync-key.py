#!/usr/bin/env python3
"""One-time owner-operated provisioning of a hash-only, KB-scoped machine key.

Run as root on the existing WeKnora host. This does not reset user credentials,
create users, or grant tenant/platform administration. Runtime synchronization
uses official knowledge APIs only. The plaintext is retained solely in a 0600
private file; PostgreSQL stores its SHA-256 hash and no recoverable plaintext.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import uuid


def provision(kb_id, tenant_id, owner_id, private_file):
    uuid.UUID(kb_id)
    uuid.UUID(owner_id)
    if os.geteuid() != 0:
        raise SystemExit('Run as root on the WeKnora host')
    target = Path(private_file)
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if target.exists():
        if target.stat().st_mode & 0o077:
            raise SystemExit('Private file permissions must be 0600')
        payload = json.loads(target.read_text())
        if payload['kbId'] != kb_id or payload['tenantId'] != tenant_id:
            raise SystemExit('Existing private file belongs to another target')
    else:
        payload = {'kbId': kb_id, 'tenantId': tenant_id, 'apiKey': 'wk_' + secrets.token_urlsafe(32)}
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as handle:
            json.dump(payload, handle)
            handle.flush()
            os.fsync(handle.fileno())
    digest = hashlib.sha256(payload['apiKey'].encode()).hexdigest()
    env = dict(v.split('=', 1) for v in json.loads(subprocess.check_output(
        ['docker', 'inspect', 'WeKnora-postgres']))[0]['Config']['Env'])
    # Inputs are UUIDs / integer / SHA-256, never unvalidated SQL or plaintext secrets.
    query = f"""
    BEGIN;
    SELECT pg_advisory_xact_lock(81240801);
    DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM knowledge_bases k
        JOIN tenant_members m ON m.tenant_id=k.tenant_id
        WHERE k.id='{kb_id}' AND k.tenant_id={int(tenant_id)}
        AND m.user_id='{owner_id}' AND m.role='owner'
        AND k.deleted_at IS NULL
        AND (k.indexing_strategy->>'wiki_enabled')::boolean=true)
      THEN RAISE EXCEPTION 'Target / ownership / Wiki mode validation failed'; END IF;
    END $$;
    INSERT INTO tenant_api_keys(tenant_id,scope_type,name,key_hash,api_key,full_access,knowledge_base_ids,capabilities,created_at,updated_at)
    SELECT {int(tenant_id)},'tenant','WAO 内容同步','{digest}','',false,
      '["{kb_id}"]'::jsonb,'["retrieve","ingest"]'::jsonb,NOW(),NOW()
    WHERE NOT EXISTS (SELECT 1 FROM tenant_api_keys WHERE key_hash='{digest}');
    SELECT id,scope_type,full_access,knowledge_base_ids,capabilities FROM tenant_api_keys WHERE key_hash='{digest}';
    COMMIT;
    """
    result = subprocess.run(['docker','exec','-i','WeKnora-postgres','psql','-v','ON_ERROR_STOP=1',
                             '-U',env['POSTGRES_USER'],'-d',env['POSTGRES_DB'],'-At'],
                            input=query,text=True,capture_output=True)
    if result.returncode:
        # Never print a SQL error that may include the full query or private state.
        raise SystemExit('Provisioning failed; verify target ownership and deployed v0.8 schema')
    print(result.stdout.strip())
    print('Machine key provisioned; plaintext retained only in the root-private file.')


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--kb-id',required=True)
    parser.add_argument('--tenant-id',type=int,required=True)
    parser.add_argument('--owner-id',required=True)
    parser.add_argument('--private-file',default='/opt/wao-weknora/upstream/weknora-sync.private.json')
    args=parser.parse_args()
    provision(args.kb_id,args.tenant_id,args.owner_id,args.private_file)
