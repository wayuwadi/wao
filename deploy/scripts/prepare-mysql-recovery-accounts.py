#!/usr/bin/env python3
"""Provision missing WAO recovery identities without promoting the read-only replica."""
import json
import re
import signal
import subprocess


def literal(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "''") + "'"


def environment(container):
    result = subprocess.run(['docker', 'inspect', container], capture_output=True, check=True)
    return dict(value.split('=', 1) for value in json.loads(result.stdout)[0]['Config']['Env'])


def sql(statement):
    result = subprocess.run(
        ['docker', 'exec', '-i', 'wao-slave-mysql-slave-1', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot -N --batch'],
        input=statement.encode(), capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Recovery account SQL failed; no statement or credential is logged.')
    return result.stdout.decode().strip()


def prepare():
    app = environment('wao-slave-wao-server-1')
    replica = environment('wao-slave-mysql-slave-1')
    assert app['DB_NAME'] == replica['DB_NAME'] == 'wao'
    user = app['DB_USER']
    repl = replica['MYSQL_REPLICATION_USER']
    assert user == 'wao_app' and repl == 'wao_repl'
    assert sql('SELECT @@read_only,@@super_read_only,@@server_id') == '1\t1\t2'
    users = [(user, app['DB_PASS'], 'ALL PRIVILEGES ON `wao`.*'),
             (repl, replica['MYSQL_REPLICATION_PASSWORD'], 'REPLICATION SLAVE ON *.*')]
    pending = []
    for name, password, grant in users:
        assert password and re.fullmatch(r'[a-z_]+', name)
        count = sql('SELECT COUNT(*) FROM mysql.user WHERE User=' + literal(name))
        if count != '0':
            raise RuntimeError('Recovery account already exists; inspect it rather than resetting its password.')
        identity = literal(name) + "@'%'"
        pending += ['CREATE USER ' + identity + ' IDENTIFIED BY ' + literal(password) + ';',
                    'GRANT ' + grant + ' TO ' + identity + ';']
    # read_only stays ON throughout. Only the privileged local connection can
    # change users; neither application identity receives a bypass privilege.
    try:
        sql('SET SESSION sql_log_bin=0; SET GLOBAL super_read_only=OFF;\n' + '\n'.join(pending))
    finally:
        sql('SET GLOBAL super_read_only=ON;')
    assert sql('SELECT @@read_only,@@super_read_only') == '1\t1'
    assert sql("SELECT COUNT(*) FROM mysql.user WHERE User IN ('wao_app','wao_repl') AND Host='%'") == '2'
    print('Recovery identities provisioned; replica remains read_only=ON and super_read_only=ON.')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
    prepare()
