#!/bin/sh
set -eu
# Production topology is durable; source labels cannot override elected roles.
grep -Fq 'state=/data/sentinel.conf' deploy/redis/start-sentinel.sh
! grep -Fq 'cat >/tmp/sentinel.conf' deploy/redis/start-sentinel.sh
! grep -Fq 'compose up -d --force-recreate --no-deps "${sentinel_service}"' deploy/scripts/deploy-linux.sh
grep -Fq 'validate-private-ha.py' deploy/scripts/validate-slave.sh
! grep -Fq 'expected ${expected_master_host}:6379' deploy/scripts/validate-slave.sh
grep -Fq '10.66.0.1:26379' deploy/jenkins/ci-deploy.sh
grep -Fq 'MemoryMax=128M' deploy/redis/wao-cloud-sentinel.service
echo 'Redis private Sentinel deployment policy checks passed.'
