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

for task_script in \
  deploy/scripts/install-wsl-keepalive.ps1 \
  deploy/scripts/install-windows-ops-tasks.ps1; do
  grep -Fq -- '-AllowStartIfOnBatteries' "$task_script"
  grep -Fq -- '-DontStopIfGoingOnBatteries' "$task_script"
done

echo 'Windows node availability policy checks passed.'
