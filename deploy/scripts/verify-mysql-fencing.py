"""Disposable MySQL 9.7 fencing/reverse-replication experiment, never production data."""
import json
import secrets
import subprocess
import time
import uuid
import signal
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('recovery', Path(__file__).with_name('mysql-guarded-recovery.py'))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)
backup_spec = importlib.util.spec_from_file_location('backup_source', Path(__file__).resolve().parent.parent / 'backup/verify-backup-source.py')
backup_source = importlib.util.module_from_spec(backup_spec)
backup_spec.loader.exec_module(backup_source)

tag = uuid.uuid4().hex[:8]
network = 'wao-fence-test-' + tag
source = network + '-source'
replica = network + '-replica'
password = secrets.token_hex(16)  # MySQL replication SOURCE_PASSWORD is limited to 32 characters.
created = []
network_created = False

def docker(*args, payload=None):
    result = subprocess.run(['docker', *args], input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    if result.returncode:
        raise RuntimeError('Isolated Docker command failed: ' + args[0])
    return result.stdout.decode().strip()

def sql(container, statement, user='root', allow_fail=False):
    # Fake test credentials exist only in the disposable container environment.
    command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u' + user + ' -N --batch --raw --skip-column-names'
    if statement.startswith('SHOW REPLICA STATUS'):
        command = command.replace(' -N', '').replace(' --skip-column-names', '') + ' --vertical'
    result = subprocess.run(['docker','exec','-i',container,'sh','-c',command],input=statement.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
    if result.returncode and not allow_fail:
        # Avoid logging statement text or fake passwords as a bad pattern.
        detail = result.stderr.decode(errors='replace').replace(password, '[redacted-test-credential]')[:450]
        raise RuntimeError('Isolated SQL command failed in ' + container + ': ' + detail)
    return result.returncode, result.stdout.decode().strip()

def value(container, statement):
    return sql(container, statement)[1]

def wait_gtid(container, gtid):
    assert value(container, "SELECT WAIT_FOR_EXECUTED_GTID_SET('"+gtid+"',30);") == '0'

def interrupted(*_):
    raise SystemExit('Interrupted; cleaning disposable MySQL experiment')

signal.signal(signal.SIGTERM, interrupted)
try:
    docker('network','create','--internal',network)
    network_created = True
    for name, sid in ((source, '1'),(replica,'2')):
        extra = ['--replicate-wild-do-table=wao-return:wao.%'] if name == source else []
        docker('create','--name',name,'--network',network,'--memory','2g','--cpus','1','--volume','/var/lib/mysql',
               '--env-file','/dev/stdin','mysql:9.7.0','--server-id='+sid,'--gtid-mode=ON','--enforce-gtid-consistency=ON',
               '--log-bin=mysql-bin','--log-replica-updates=ON','--event-scheduler=OFF','--innodb-buffer-pool-size=128M',
               '--innodb-redo-log-capacity=128M',*extra,payload=('MYSQL_ROOT_PASSWORD='+password+'\n').encode())
        created.append(name)
        docker('start',name)
        for _ in range(100):
            code,_ = sql(name,'SELECT 1;',allow_fail=True)
            if code == 0 and docker('exec',name,'cat','/proc/1/comm') == 'mysqld':
                break
            time.sleep(2)
        else:
            raise RuntimeError('Isolated MySQL startup timed out')
    value(source,"CREATE DATABASE wao; CREATE DATABASE tools; CREATE TABLE wao.marker (id INT PRIMARY KEY); CREATE TABLE tools.marker (id INT PRIMARY KEY); CREATE USER 'wao_app'@'%' IDENTIFIED BY '"+password+"'; GRANT ALL ON wao.* TO 'wao_app'@'%'; CREATE USER 'wao_repl'@'%' IDENTIFIED BY '"+password+"'; GRANT REPLICATION SLAVE ON *.* TO 'wao_repl'@'%'; INSERT INTO wao.marker VALUES(1);")
    value(replica,"CHANGE REPLICATION SOURCE TO SOURCE_HOST='"+source+"', SOURCE_USER='wao_repl', SOURCE_PASSWORD='"+password+"', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA;")
    wait_gtid(replica,value(source,'SELECT @@global.gtid_executed;'))
    value(replica,"STOP REPLICA; CHANGE REPLICATION FILTER REPLICATE_WILD_DO_TABLE=('wao.%'); START REPLICA;")
    value(replica,'SET PERSIST read_only=ON; SET PERSIST super_read_only=ON;')
    assert value(replica,'SELECT COUNT(*) FROM wao.marker;') == '1'
    receipt = recovery.fence(lambda statement: value(source, statement))
    assert sql(source,'INSERT INTO wao.marker VALUES(2);',user='wao_app',allow_fail=True)[0] != 0
    assert value(replica,"SELECT account_locked FROM mysql.user WHERE user='wao_app';") == 'N'
    value(source,'INSERT INTO tools.marker VALUES(1);')
    promoted = recovery.promote(lambda statement: value(replica, statement), receipt)
    sql(replica,'INSERT INTO wao.marker VALUES(2);',user='wao_app')
    print('PASS: account fence blocks old WAO writes while unrelated schema remains writable; replica caught up before promotion.',flush=True)
    recovery.rejoin(lambda statement: value(source, statement), promoted, password, source_host=replica)
    wait_gtid(source,value(replica,'SELECT @@global.gtid_executed;'))
    assert value(source,'SELECT COUNT(*) FROM wao.marker;') == '2'
    assert value(source,'SELECT COUNT(*) FROM tools.marker;') == '1'
    verified = backup_source.check(lambda statement, upstream=False: value(replica if upstream else source, statement),
                                   'linux1', expected_host=replica)
    assert verified == 'linux2-three-database-writer-and-verified-wao-replica'
    print('PASS: backup source verifies the promoted writer barrier before claiming a current four-schema recovery point.', flush=True)
    assert sql(source,'INSERT INTO wao.marker VALUES(3);',user='wao_app',allow_fail=True)[0] != 0
    docker('restart',replica)
    for _ in range(60):
        code,answer=sql(replica,'SELECT @@global.read_only, @@global.super_read_only;',allow_fail=True)
        if not code:
            assert answer == '0\t0'
            assert value(replica,'SHOW REPLICA STATUS;') == ''
            break
        time.sleep(2)
    else:
        raise RuntimeError('Promoted role did not survive restart')
    print('PASS: filtered reverse replication restores the old WAO copy without interrupting tools; promoted role survives restart.',flush=True)
    docker('restart',source)
    for _ in range(60):
        code,_=sql(source,'SELECT 1;',allow_fail=True)
        if not code:
            current=recovery.status(lambda statement: value(source,statement),'wao-return')
            if current.get('Replica_IO_Running') == current.get('Replica_SQL_Running') == 'Yes':
                assert current['Replicate_Wild_Do_Table'] == 'wao.%'
                assert value(source,"SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'") == 'Y'
                break
        time.sleep(2)
    else:
        raise RuntimeError('Old source did not retain its filtered channel and account fence after restart')
    print('PASS: old source restart preserves WAO-only replication and the durable application account fence.',flush=True)
finally:
    for name in reversed(created):
        docker('rm','-fv',name)
    if network_created:
        docker('network','rm',network)
    print('Disposable MySQL containers and private test network cleaned.',flush=True)
