#!/bin/sh
set -eu

installer_script='deploy/scripts/install-release-linux.sh'
deploy_script='deploy/jenkins/ci-deploy.sh'

grep -Fq 'flock -w 600 9' "$installer_script"
grep -Fq 'docker info' "$installer_script"
grep -Fq 'bootstrap-mysql-slave.sh' "$installer_script"
grep -Fq 'validate-deployment-node.sh" slave' "$installer_script"

grep -Fq 'deployment attempt %s/3' "$deploy_script"
grep -Fq 'set -o pipefail; "$deploy_function" 2>&1 | tee -a "$log_file"' "$deploy_script"
grep -Fq 'ServerAliveInterval 30' "$deploy_script"
grep -Fq 'timeout --foreground --signal=TERM 30m ssh' "$deploy_script"
grep -Fq 'retry_remote "Linux1 从节点" slave_deploy_ssh' "$deploy_script"

echo 'Linux slave availability policy checks passed.'
