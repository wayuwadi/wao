#!/bin/sh
set -eu

cache="${JENKINS_HOME:-$HOME}/.m2"
mkdir -p "$cache"

projects="${WAO_BACKEND_PROJECTS:-all}"
case "$projects" in
  all) maven_projects='' ;;
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
    ;;
esac

set -- mvn -B -Dmaven.repo.local=/tmp/m2/repository
if [ -n "$maven_projects" ]; then
  set -- "$@" -pl "$maven_projects" -am
fi
set -- "$@" test

docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e MAVEN_CONFIG=/tmp/m2 \
  -v "$PWD:/workspace" \
  -v "$cache:/tmp/m2" \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-17 \
  "$@"
