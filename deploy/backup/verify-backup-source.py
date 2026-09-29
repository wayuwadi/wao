#!/usr/bin/env python3
"""Verify that the local Linux2 MySQL is the writable WAO backup source."""
import os
import subprocess


def check(sql, writer):
    if sql('SELECT @@read_only,@@super_read_only,@@log_replica_updates') != '0\t0\t1':
        raise ValueError('Unexpected native backup source role or log_replica_updates disabled')
    locked = sql("SELECT account_locked FROM mysql.user WHERE User='wao_app' AND Host='%'")
    if writer != 'linux2' or locked != 'N':
        raise ValueError('Linux2 must be the configured, unfenced WAO writer')
    return 'linux2-four-database-writer'


def main():
    def sql(statement):
        command = ['mysql', '-h127.0.0.1', '-P3306', '-uroot', '-N', '--batch', '--raw']
        result = subprocess.run(command, input=statement.encode(), env=dict(os.environ, MYSQL_PWD=os.environ['DB_ROOT_PASS']), capture_output=True, timeout=40)
        if result.returncode:
            raise ValueError('Could not verify authoritative WAO recovery source')
        return result.stdout.decode().strip()
    print(check(sql, os.environ.get('WAO_MYSQL_PRIMARY', 'linux2')))


if __name__ == '__main__':
    main()
