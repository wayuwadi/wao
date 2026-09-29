#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
sed -n '/^start_services() {/,/^}/p' "${script_dir}/deploy-linux.sh" >"${test_dir}/function.sh"
printf '#!/bin/sh\nexit 0\n' >"${test_dir}/ensure-codex-egress.sh"

run_case() (
  role="$1"
  MANAGE_MASTER_INFRA="$2"
  fail_service="$3"
  legacy_agent_id=''
  script_dir="$test_dir"
  export role MANAGE_MASTER_INFRA fail_service
  : >"${test_dir}/calls"
  compose() {
    printf '%s\n' "$*" >>"${test_dir}/calls"
    case " $* " in
      *" ${fail_service} "*) return 1 ;;
    esac
  }
  . "${test_dir}/function.sh"
  start_services
)

run_case master true codex-vpn
grep -Fq 'up -d --wait mysql-master nacos nacos-init-admin ddns-go redis-master wao-server wao-agent-server wao-gateway' "${test_dir}/calls"
grep -Fq 'up -d --no-deps codex-vpn codex-egress-proxy' "${test_dir}/calls"

run_case master false codex-vpn
grep -Fq 'up -d --wait redis-master wao-server wao-agent-server wao-gateway' "${test_dir}/calls"
if grep -Fq 'mysql-master' "${test_dir}/calls"; then
  echo 'External infrastructure mode must not start managed MySQL.' >&2
  exit 1
fi

if run_case master true wao-server; then
  echo 'A failed business service must fail the deployment.' >&2
  exit 1
fi
if grep -Fq 'codex-vpn' "${test_dir}/calls"; then
  echo 'Optional egress must not start after a failed business deployment.' >&2
  exit 1
fi

if run_case retired false unused >"${test_dir}/retired.out" 2>&1; then
  echo 'A retired deployment role must not be accepted.' >&2
  exit 1
fi
grep -Fq 'Only the Linux2 deployment role is supported.' "${test_dir}/retired.out"
test ! -s "${test_dir}/calls"
echo 'Optional Codex egress deployment tests passed.'
