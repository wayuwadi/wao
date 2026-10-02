#!/bin/sh
# Agent 已退役，常规发布仅构建主站浏览器制品。
set -eu
exec deploy/jenkins/ci-frontend-build.sh wao-web false true
