#!/bin/sh
set -eu

cache="${JENKINS_HOME:-$HOME}/.m2"
mkdir -p "$cache"

projects="${WAO_BACKEND_PROJECTS:-all}"
require_mysql_contracts=false
case "$projects" in
  all)
    maven_projects=''
    require_mysql_contracts=true
    ;;
  none)
    echo 'Backend tests skipped: the change plan found no affected backend module.'
    exit 0
    ;;
  *)
    old_ifs="$IFS"
    IFS=,
    for project in $projects; do
      case "$project" in
        wao-server|wao-gateway|agent-server) ;;
        *) echo "Invalid WAO_BACKEND_PROJECTS: $projects" >&2; exit 2 ;;
      esac
    done
    IFS="$old_ifs"
    maven_projects="$projects"
    case ",$projects," in
      *,wao-server,*) require_mysql_contracts=true ;;
    esac
    ;;
esac

set -- mvn -B \
  -Dmaven.repo.local=/tmp/m2/repository \
  "-Dapi.version=${WAO_DOCKER_API_VERSION:-1.40}"
if [ -n "$maven_projects" ]; then
  set -- "$@" -pl "$maven_projects" -am
fi
set -- "$@" test

docker_socket=/var/run/docker.sock
test -S "$docker_socket"
docker_socket_gid="$(stat -c %g "$docker_socket")"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --group-add "$docker_socket_gid" \
  --network host \
  -e MAVEN_CONFIG=/tmp/m2 \
  -v "$PWD:/workspace" \
  -v "$cache:/tmp/m2" \
  -v "$docker_socket:$docker_socket" \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-17 \
  "$@"

if [ "$require_mysql_contracts" = true ]; then
  for test_class in FeishuWebhookMigrationMySqlTest ChatReviewMySqlIntegrationTest WorkItemNumberMySqlTest; do
    report="wao-server/target/surefire-reports/TEST-cn.wayuwa.wao.${test_class}.xml"
    if [ ! -f "$report" ] \
      || ! grep -Eq '<testsuite [^>]*tests="[1-9][0-9]*"' "$report" \
      || ! grep -Eq '<testsuite [^>]*skipped="0"' "$report"; then
      echo "Required MySQL contract test did not run successfully: $test_class" >&2
      exit 1
    fi
  done
fi
