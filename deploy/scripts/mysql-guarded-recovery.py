#!/usr/bin/env python3
"""Operator-driven WAO-only fencing and promotion; never an automatic failover daemon."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess


def quote(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "''") + "'"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def status(sql, channel=''):
    raw = sql('SHOW REPLICA STATUS' + (' FOR CHANNEL ' + quote(channel) if channel else ''))
    return {line.split(':', 1)[0].strip(): line.split(':', 1)[1].strip()
            for line in raw.splitlines() if ':' in line}


def fence(sql, source_id=1):
    require(source_id in (1, 2), 'Unsupported source identity')
    require(sql('SELECT @@server_id,@@read_only,@@super_read_only') == str(source_id) + '\t0\t0', 'Unexpected source role')
    grants = set(sql("SHOW GRANTS FOR 'wao_app'@'%'").splitlines())
    require(grants == {'GRANT USAGE ON *.* TO `wao_app`@`%`',
                       'GRANT ALL PRIVILEGES ON `wao`.* TO `wao_app`@`%`'}, 'Application grants require separate review')
    require(sql("SELECT COUNT(*) FROM mysql.db WHERE Db='wao' AND User<>'wao_app'") == '0', 'Unexpected WAO database writer grant')
    require(sql("SELECT COUNT(*) FROM mysql.db WHERE User='wao_app' AND Db<>'wao'") == '0', 'Application identity also owns another database')
    require(sql("SELECT COUNT(*) FROM mysql.tables_priv WHERE Db='wao' AND User<>'wao_app'") == '0', 'Unexpected WAO table grant')
    require(sql("SELECT COUNT(*) FROM mysql.role_edges WHERE TO_USER='wao_app'") == '0', 'Application roles require separate review')
    require(sql("SELECT COUNT(*) FROM information_schema.events WHERE EVENT_SCHEMA='wao'") == '0', 'WAO events need separate fencing')
    require(sql("SELECT COUNT(*) FROM mysql.user WHERE User='wao_app' AND Host='%'") == '1', 'Unexpected application identity')
    # ACCOUNT LOCK does not end existing connections. Kill them before capturing
    # the GTID barrier, and never replicate this local account fence to the target.
    sql("SET SESSION sql_log_bin=0; ALTER USER 'wao_app'@'%' ACCOUNT LOCK;")
    for connection in sql("SELECT ID FROM information_schema.processlist WHERE USER='wao_app'").splitlines():
        require(connection.isdigit(), 'Invalid connection identifier')
        try:
            sql('KILL CONNECTION ' + connection)
        except RuntimeError:
            # A connection may have ended naturally; the independent count below
            # must still be zero, otherwise promotion remains forbidden.
            pass
    require(sql("SELECT COUNT(*) FROM information_schema.processlist WHERE USER='wao_app'") == '0', 'Application sessions are not drained')
    require(sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'") == 'Y', 'Old writer is not fenced')
    return dict(kind='wao-account-fence', source_uuid=sql('SELECT @@server_uuid'),
                gtid=sql('SELECT @@global.gtid_executed'),
                captured_utc=dt.datetime.now(dt.timezone.utc).isoformat())


def fence_linux1(sql):
    """Fence the current dedicated WAO writer before a separately approved return."""
    receipt = fence(sql, source_id=2)
    sql('SET PERSIST read_only=ON; SET PERSIST super_read_only=ON;')
    require(sql('SELECT @@read_only,@@super_read_only') == '1\t1', 'Linux1 fence did not persist')
    receipt['kind'] = 'wao-linux1-fence'
    receipt['gtid'] = sql('SELECT @@global.gtid_executed')
    return receipt


def promote_linux2(sql, receipt):
    """Promote the fenced WAO schema only; never stop the other three databases."""
    require(receipt['kind'] == 'wao-linux1-fence', 'Requires an explicit Linux1 fence receipt')
    age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(receipt['captured_utc'])).total_seconds()
    require(0 <= age <= 120, 'Fence receipt expired; re-check Linux1 before promotion')
    require(re.fullmatch(r'[0-9a-f-]{36}', receipt['source_uuid']), 'Invalid source UUID')
    require(re.fullmatch(r'[0-9a-f:,\-\n]+', receipt['gtid']), 'Invalid GTID barrier')
    require(sql('SELECT @@server_id,@@read_only,@@super_read_only') == '1\t0\t0', 'Unexpected native Linux2 role')
    require(sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'") == 'Y', 'Linux2 must still be fenced')
    current = status(sql, 'wao-return')
    require(current.get('Source_UUID') == receipt['source_uuid'], 'Return replica follows another source')
    require(current.get('Replica_IO_Running') == current.get('Replica_SQL_Running') == 'Yes', 'Return replication is not healthy')
    require(current.get('Replicate_Wild_Do_Table') == 'wao.%', 'Unexpected return replication scope')
    require(sql('SELECT WAIT_FOR_EXECUTED_GTID_SET(' + quote(receipt['gtid']) + ',30)') == '0', 'Linux2 has not applied the fenced source barrier')
    baseline = sql('SELECT @@global.gtid_executed')
    sql("STOP REPLICA FOR CHANNEL 'wao-return'; RESET REPLICA ALL FOR CHANNEL 'wao-return';")
    sql("SET SESSION sql_log_bin=0; ALTER USER 'wao_app'@'%' ACCOUNT UNLOCK;")
    require(sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'") == 'N', 'Linux2 application identity unavailable')
    # Native Linux2 has unrelated-schema and historical reseed GTIDs. Do not
    # replay that history into Linux1: a consistent WAO reseed is mandatory.
    return dict(kind='wao-linux2-promoted', source_uuid=sql('SELECT @@server_uuid'),
                previous_source_uuid=receipt['source_uuid'], fence_gtid=receipt['gtid'],
                baseline_gtid=baseline, old_source_requires_reseed=True,
                promoted_utc=dt.datetime.now(dt.timezone.utc).isoformat())


def promote(sql, receipt):
    require(receipt['kind'] == 'wao-account-fence', 'Unsupported fence evidence')
    age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(receipt['captured_utc'])).total_seconds()
    require(0 <= age <= 120, 'Fence receipt expired; re-check old writer before promotion')
    require(re.fullmatch(r'[0-9a-f-]{36}', receipt['source_uuid']), 'Invalid source UUID')
    require(re.fullmatch(r'[0-9a-f:,\-\n]+', receipt['gtid']), 'Invalid GTID barrier')
    require(sql('SELECT @@server_id,@@read_only,@@super_read_only') == '2\t1\t1', 'Target is not the expected read-only replica')
    current = status(sql)
    require(current.get('Source_UUID') == receipt['source_uuid'], 'Replica follows another source')
    require(current.get('Replica_IO_Running') == current.get('Replica_SQL_Running') == 'Yes', 'Replication is not healthy')
    require(current.get('Replicate_Wild_Do_Table') == 'wao.%', 'Unexpected replication scope')
    require(sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'") == 'N', 'Target application account unavailable')
    require(sql('SELECT WAIT_FOR_EXECUTED_GTID_SET(' + quote(receipt['gtid']) + ',30)') == '0', 'Target has not applied the fenced source barrier')
    baseline = sql('SELECT @@global.gtid_executed')
    # A provisioned replica can retain local bootstrap GTIDs (including DROP
    # DATABASE) even when its current data is correct. AUTO_POSITION would replay
    # those historical transactions back into the old source after promotion.
    # Concurrent non-WAO transactions from the known source are safe to exclude.
    source_history = ','.join(group.strip() for group in baseline.split(',')
                              if group.strip().startswith(receipt['source_uuid'] + ':'))
    require(sql('SELECT GTID_SUBTRACT(GTID_SUBTRACT(' + quote(baseline) + ',' +
                quote(receipt['gtid']) + '),' + quote(source_history) + ')') == '',
            'Replica has unaccounted local GTID history; reseed before promotion')
    sql('STOP REPLICA;')
    # Removing the old channel is deliberate: reboot must not restart a reverse
    # cycle. Existing data and GTID execution history are never reset or erased.
    sql('RESET REPLICA ALL; SET PERSIST super_read_only=OFF; SET PERSIST read_only=OFF;')
    require(sql('SELECT @@read_only,@@super_read_only') == '0\t0', 'Promotion did not persist')
    return dict(kind='wao-promoted', source_uuid=sql('SELECT @@server_uuid'),
                previous_source_uuid=receipt['source_uuid'], fence_gtid=receipt['gtid'], baseline_gtid=baseline,
                promoted_utc=dt.datetime.now(dt.timezone.utc).isoformat())


def rejoin(sql, promoted, password, source_host='10.66.0.3'):
    require(promoted['kind'] == 'wao-promoted', 'Missing promoted role evidence')
    require(sql('SELECT @@server_uuid') == promoted['previous_source_uuid'], 'This is not the fenced old source')
    require(sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'") == 'Y', 'Old application account must stay locked')
    require(not sql('SHOW REPLICA STATUS'), 'Existing replication channel requires manual review')
    require(bool(promoted.get('baseline_gtid')), 'Missing pre-promotion GTID baseline; refuse historical replay')
    require(sql('SELECT GTID_SUBSET(' + quote(promoted['baseline_gtid']) + ',@@global.gtid_executed)') == '1',
            'Old source does not cover pre-promotion history; reseed instead of replaying it')
    require(password and len(password) <= 32, 'Invalid replication credential length')
    sql("CHANGE REPLICATION SOURCE TO SOURCE_HOST=" + quote(source_host) + ", SOURCE_PORT=3306, SOURCE_USER='wao_repl', SOURCE_PASSWORD="
        + quote(password) + ", SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1 FOR CHANNEL 'wao-return';"
        "CHANGE REPLICATION FILTER REPLICATE_WILD_DO_TABLE=('wao.%') FOR CHANNEL 'wao-return';"
        "START REPLICA FOR CHANNEL 'wao-return';")
    # Keep all other databases on the old instance writable. The durable WAO
    # account fence is its schema-scoped protection; never globally stop MySQL.
    return dict(kind='wao-rejoin-started', expected_source_uuid=promoted['source_uuid'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['fence', 'promote', 'rejoin', 'fence-linux1', 'promote-linux2'])
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--maintenance-confirmed', action='store_true')
    args = parser.parse_args()
    require(args.maintenance_confirmed, 'Requires an approved maintenance window and deployment/privileged-write pause')
    args.state.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not args.state.is_symlink(), 'State directory must not be a symbolic link')
    replica = args.action in ('promote', 'fence-linux1')

    def sql(statement):
        if replica:
            command = ['docker', 'exec', '-i', 'wao-slave-mysql-slave-1', 'sh', '-c',
                       'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --batch --raw' +
                       (' --vertical' if statement.startswith('SHOW REPLICA STATUS') else ' -N')]
            env = None
        else:
            require(bool(os.environ.get('DB_ROOT_PASS')), 'Load the existing root-owned deployment environment locally')
            command = ['mysql', '-h127.0.0.1', '-P3306', '-uroot', '-N', '--batch', '--raw']
            if statement.startswith('SHOW REPLICA STATUS'):
                command.remove('-N')
                command.append('--vertical')
            env = dict(os.environ, MYSQL_PWD=os.environ['DB_ROOT_PASS'])
        process = subprocess.run(command, input=statement.encode(), env=env, capture_output=True, timeout=45)
        require(process.returncode == 0, 'MySQL recovery command failed; stop and inspect current role, never blindly undo fencing')
        return process.stdout.decode().strip()

    # Preserve a non-secret pre-operation state before any destructive channel
    # mutation. No credentials from SHOW REPLICA STATUS are included.
    before = dict(server_uuid=sql('SELECT @@server_uuid'), gtid=sql('SELECT @@global.gtid_executed'),
                  role=sql('SELECT @@read_only,@@super_read_only'))
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    (args.state / (stamp + '-' + args.action + '-before.json')).write_text(json.dumps(before))
    evidence = json.loads(args.evidence.read_text()) if args.evidence else None
    if args.action == 'fence':
        result = fence(sql)
    elif args.action == 'fence-linux1':
        result = fence_linux1(sql)
    elif args.action == 'promote-linux2':
        require(evidence is not None, 'Missing fresh Linux1 fence receipt')
        result = promote_linux2(sql, evidence)
    elif args.action == 'promote':
        require(evidence is not None, 'Missing fresh fence receipt')
        result = promote(sql, evidence)
    else:
        require(evidence is not None, 'Missing promoted role receipt')
        persistent_filter = Path('/etc/wao-mysql-recovery/return.cnf')
        require(persistent_filter.is_file() and
                'replicate-wild-do-table=wao-return:wao.%' in persistent_filter.read_text().splitlines(),
                'Install and validate the persistent channel filter before rejoining')
        require('!include /etc/wao-mysql-recovery/return.cnf' in Path('/etc/my.cnf').read_text().splitlines(),
                'Native MySQL configuration does not include the persistent return filter')
        result = rejoin(sql, evidence, os.environ.get('MYSQL_REPLICATION_PASSWORD', ''))
    destination = args.state / (stamp + '-' + args.action + '.json')
    destination.write_text(json.dumps(result))
    destination.chmod(0o600)
    print(destination)


if __name__ == '__main__':
    os.umask(0o077)
    main()
