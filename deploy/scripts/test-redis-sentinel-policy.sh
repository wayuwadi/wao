#!/bin/sh
set -eu

sentinel_script='deploy/redis/start-sentinel.sh'
deploy_script='deploy/scripts/deploy-linux.sh'
slave_validator='deploy/scripts/validate-slave.sh'

grep -Fq 'state=/data/sentinel.conf' "$sentinel_script"
! grep -Fq 'cat >/tmp/sentinel.conf' "$sentinel_script"
grep -Fq 'REDIS_SENTINEL_QUORUM: ${REDIS_SENTINEL_QUORUM:-3}' deploy/docker-compose-master.yml
grep -Fq 'REDIS_SENTINEL_QUORUM: ${REDIS_SENTINEL_QUORUM:-3}' deploy/docker-compose-slave.yml
! grep -Fq 'compose up -d --force-recreate --no-deps "${sentinel_service}"' "$deploy_script"
grep -Fq 'expected ${expected_master_host}:6379' "$slave_validator"

echo 'Redis Sentinel no-auto-failover policy checks passed.'
