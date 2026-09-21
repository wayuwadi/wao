#!/usr/bin/env python3
"""Reject stale WAO recovery points when Linux2 becomes its fenced replica."""
import os
import subprocess


def check(sql, writer, expected_host='10.66.0.3', expected_port=3306):
    if sql('SELECT @@read_only,@@super_read_only,@@log_replica_updates') != '0\t0\t1':
        raise ValueError('Unexpected native backup source role or log_replica_updates disabled')
    locked = sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'")
    if writer == 'linux2':
        if locked != 'N':
            raise ValueError('Configured WAO writer is fenced; refuse claiming a current recovery point')
        return 'linux2-four-database-writer'
    if writer != 'linux1' or locked != 'Y':
        raise ValueError('Invalid WAO primary role or missing old-writer fence')
    raw = sql("SHOW REPLICA STATUS FOR CHANNEL 'wao-return'")
    state = {line.split(':', 1)[0].strip(): line.split(':', 1)[1].strip()
             for line in raw.splitlines() if ':' in line}
    if not (state.get('Source_Host') == expected_host
            and state.get('Source_Port') == str(expected_port)
            and state.get('Replica_IO_Running') == state.get('Replica_SQL_Running') == 'Yes'
            and state.get('Replicate_Wild_Do_Table') == 'wao.%'):
        raise ValueError('WAO return replication is not healthy')
    try:
        if not 0 <= int(state['Seconds_Behind_Source']) <= 30:
            raise ValueError('WAO return replication is stale')
    except (KeyError, TypeError):
        raise ValueError('Unknown WAO return replication lag') from None
    upstream = sql('SELECT @@server_uuid,@@global.gtid_executed', upstream=True).split('\t', 1)
    if len(upstream) != 2 or upstream[0] != state.get('Source_UUID'):
        raise ValueError('WAO backup replica follows a different primary')
    gtid = upstream[1].replace("'", "''")
    if sql("SELECT WAIT_FOR_EXECUTED_GTID_SET('" + gtid + "',30)") != '0':
        raise ValueError('WAO backup replica has not applied the observed primary barrier')
    return 'linux2-three-database-writer-and-verified-wao-replica'


def main():
    def sql(statement, upstream=False):
        password = os.environ['DB_PASS'] if upstream else os.environ['DB_ROOT_PASS']
        user = os.environ['DB_USER'] if upstream else 'root'
        command = ['mysql', '-h' + ('10.66.0.3' if upstream else '127.0.0.1'), '-P3306', '-u' + user, '-N', '--batch', '--raw']
        if statement.startswith('SHOW REPLICA STATUS'):
            command.remove('-N')
            command.append('--vertical')
        result = subprocess.run(command, input=statement.encode(), env=dict(os.environ, MYSQL_PWD=password), capture_output=True, timeout=40)
        if result.returncode:
            raise ValueError('Could not verify authoritative WAO recovery source')
        return result.stdout.decode().strip()
    print(check(sql, os.environ.get('WAO_MYSQL_PRIMARY', 'linux2'),
                expected_host=os.environ.get('MYSQL_RETURN_SOURCE_HOST', '10.66.0.3'),
                expected_port=int(os.environ.get('MYSQL_RETURN_SOURCE_PORT', '3306'))))


if __name__ == '__main__':
    main()
