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
grep -Fq 'run_slave_installer()' "$deploy_script"
grep -Fq 'run_marked_slave_command()' "$deploy_script"
grep -Fq 'WAO_LINUX1_INSTALL_VERIFIED=' "$deploy_script"
grep -Fq 'WAO_LINUX1_RELEASE_VERIFIED=' "$deploy_script"
grep -Fq 'Linux1 部署完成态验收' "$deploy_script"
grep -Fq 'validate-deployment-node.sh slave' "$deploy_script"
grep -Fq 'run_slave_installer \' "$deploy_script"
if grep -Fq 'retry_remote "Linux1 从节点" slave_deploy_ssh' "$deploy_script"; then
  echo 'The one-time Linux1 installer must not be retried after its SSH connection ends.' >&2
  exit 1
fi

run_marked_slave_command_definition="$(sed -n '/^run_marked_slave_command() {/,/^}/p' "$deploy_script")"
run_slave_installer_definition="$(sed -n '/^run_slave_installer() {/,/^}/p' "$deploy_script")"
eval "$run_marked_slave_command_definition"
eval "$run_slave_installer_definition"
WAO_GIT_SHA='test-sha'
WAO_RUN_TEMP="$(mktemp -d)"
trap 'rm -rf "$WAO_RUN_TEMP"' EXIT

slave_deploy_ssh() {
  if [ "$installer_marker_output" = true ]; then
    printf '%s\n' "WAO_LINUX1_INSTALL_VERIFIED=$WAO_GIT_SHA"
  fi
  return "$installer_result"
}
slave_ssh() {
  if [ "$verification_marker_output" = true ]; then
    printf '%s\n' "WAO_LINUX1_RELEASE_VERIFIED=$WAO_GIT_SHA"
  fi
  return "$verification_result"
}
retry_remote() {
  shift
  "$@"
}

installer_result=0
installer_marker_output=true
verification_result=1
verification_marker_output=false
run_slave_installer 'install once'

installer_result=255
installer_marker_output=false
verification_result=255
verification_marker_output=true
recovered_output="$(run_slave_installer 'install once')"
printf '%s' "$recovered_output" | grep -Fq '目标版本与节点健康检查均已通过'

installer_result=143
installer_marker_output=false
verification_result=1
verification_marker_output=false
if run_slave_installer 'install once'; then
  echo 'A failed installer and failed post-check must remain a deployment failure.' >&2
  exit 1
else
  test "$?" -eq 143
fi

echo 'Linux slave availability policy checks passed.'
