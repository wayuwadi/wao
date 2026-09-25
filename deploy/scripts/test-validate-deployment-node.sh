#!/bin/sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/project/deploy/scripts" "$fixture/bin"
cp "$repo_root/deploy/scripts/validate-deployment-node.sh" "$fixture/project/deploy/scripts/"
cp "$repo_root/deploy/scripts/image-content.sh" "$fixture/project/deploy/scripts/"
: >"$fixture/project/deploy/.env"
: >"$fixture/project/deploy/docker-compose-master.yml"

cat >"$fixture/bin/docker" <<'SH'
#!/bin/sh
case "$1" in
  info) exit 0 ;;
  compose)
    service=''
    for argument do service="$argument"; done
    printf '%s\n' "$service"
    ;;
  image)
    service=''
    for argument do service="$argument"; done
    case "$service" in
      *:current|sha256:*)
        case "$service" in
          wao-server:*|sha256:*0001) number=1 ;;
          wao-agent-server:*|sha256:*0002) number=2 ;;
          *) number=3 ;;
        esac
        printf 'linux/amd64 {"service":%s} ["layer"]\n' "$number"
        ;;
      *) printf 'linux/amd64 {"service":9} ["layer"]\n' ;;
    esac
    ;;
  inspect)
    format="$3"
    service="$4"
    case "$format" in
      '{{.Image}}')
        case "$service" in
          wao-server) printf 'sha256:%064d\n' 1 ;;
          wao-agent-server) printf 'sha256:%064d\n' 2 ;;
          wao-gateway) printf 'sha256:%064d\n' 3 ;;
        esac
        ;;
      *) printf 'healthy\n' ;;
    esac
    ;;
  exec)
    case "$*" in
      *PrintFlagsFinal*)
        case "$2" in wao-server) echo 1073741824 ;; *) echo 536870912 ;; esac
        ;;
      *)
        if [ "${WAO_READY_ALWAYS_FAIL:-false}" = true ]; then exit 28; fi
        if [ "${WAO_READY_FAIL_ONCE:-false}" = true ] && [ ! -f "$WAO_READY_TEST_MARKER" ]; then
          touch "$WAO_READY_TEST_MARKER"
          exit 28
        fi
        echo '{"code":0,"data":{"protocol":1}}'
        ;;
    esac
    ;;
  *) exit 2 ;;
esac
SH
chmod +x "$fixture/bin/docker"

PATH="$fixture/bin:$PATH" sh "$fixture/project/deploy/scripts/validate-deployment-node.sh" master current \
  >"$fixture/current.log"
grep -q 'wao-server_image=wao-server:current content=' "$fixture/current.log"
WAO_READY_FAIL_ONCE=true WAO_READY_TEST_MARKER="$fixture/first-ready-failed" \
  PATH="$fixture/bin:$PATH" sh "$fixture/project/deploy/scripts/validate-deployment-node.sh" master current \
  >"$fixture/retried.log"
test -f "$fixture/first-ready-failed"
grep -q 'gateway_ready=' "$fixture/retried.log"
if WAO_READY_ALWAYS_FAIL=true PATH="$fixture/bin:$PATH" \
  sh "$fixture/project/deploy/scripts/validate-deployment-node.sh" master current \
    >"$fixture/unready.log" 2>&1; then
  echo 'A persistently unready gateway passed validation.' >&2
  exit 1
fi
grep -q 'Gateway database readiness failed after 3 attempts' "$fixture/unready.log"
if PATH="$fixture/bin:$PATH" sh "$fixture/project/deploy/scripts/validate-deployment-node.sh" master stale \
    >"$fixture/stale.log" 2>&1; then
  echo 'A stale image passed release validation.' >&2
  exit 1
fi
grep -q 'expected [0-9a-f]' "$fixture/stale.log"
echo 'Content-tagged node image validation tests passed.'
