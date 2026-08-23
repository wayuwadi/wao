#!/bin/sh
set -eu

cache="${JENKINS_HOME:-$HOME}/.m2"
mkdir -p "$cache"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e MAVEN_CONFIG=/tmp/m2 \
  -v "$PWD:/workspace" \
  -v "$cache:/tmp/m2" \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-17 \
  mvn -B -Dmaven.repo.local=/tmp/m2/repository test
