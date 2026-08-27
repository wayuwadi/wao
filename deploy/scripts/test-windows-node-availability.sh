#!/bin/sh
set -eu

availability_script='deploy/scripts/configure-windows-node-availability.ps1'
provision_script='deploy/scripts/provision-windows-node.ps1'
installer_script='deploy/scripts/install-release-windows.ps1'

grep -Fq "foreach (\$powerMode in @('ac', 'dc'))" "$availability_script"
grep -Fq '"standby-timeout-$powerMode"' "$availability_script"
grep -Fq '"hibernate-timeout-$powerMode"' "$availability_script"

grep -Fq "Get-Service -Name 'sshd'" "$availability_script"
grep -Fq 'sc.exe failure sshd' "$availability_script"
grep -Fq 'sc.exe failureflag sshd 1' "$availability_script"

grep -Fq 'configure-windows-node-availability.ps1' "$provision_script"
grep -Fq 'configure-windows-node-availability.ps1' "$installer_script"
grep -Fq 'install-windows-ops-tasks.ps1' "$installer_script"
grep -Fq 'waiting for the deployment lock' "$installer_script"
grep -Fq 'AddSeconds(30)' "$installer_script"

deploy_script='deploy/jenkins/ci-deploy.sh'
grep -Fq 'deployment attempt %s/3' "$deploy_script"
grep -Fq 'set -o pipefail; "$deploy_function" 2>&1 | tee -a "$log_file"' "$deploy_script"
grep -Fq 'ServerAliveInterval 30' "$deploy_script"
grep -Fq 'timeout --foreground --signal=TERM 30m ssh' "$deploy_script"
grep -Fq 'retry_remote "Windows 从节点" windows_deploy_ssh' "$deploy_script"

for task_script in \
  deploy/scripts/install-wsl-keepalive.ps1 \
  deploy/scripts/install-windows-ops-tasks.ps1; do
  grep -Fq -- '-AllowStartIfOnBatteries' "$task_script"
  grep -Fq -- '-DontStopIfGoingOnBatteries' "$task_script"
done

echo 'Windows node availability policy checks passed.'
