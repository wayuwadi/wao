#!/bin/sh
set -eu

base="${1:-${WAO_CHANGE_BASE:-HEAD^}}"
head="${2:-${WAO_CHANGE_HEAD:-HEAD}}"

deploy_required=false
backend_projects=''
test_wao_web=false
test_agent_web=false
test_ai_runner=false
force_full=false
changed_count=0

add_backend_project() {
  project="$1"
  case ",${backend_projects}," in
    *",${project},"*) ;;
    *) backend_projects="${backend_projects:+${backend_projects},}${project}" ;;
  esac
}

force_full_validation() {
  force_full=true
  deploy_required=true
  test_wao_web=true
  test_agent_web=true
}

if ! git rev-parse --verify "${base}^{commit}" >/dev/null 2>&1 \
    || ! git rev-parse --verify "${head}^{commit}" >/dev/null 2>&1; then
  force_full_validation
else
  changed_files="$(git diff --name-only "$base" "$head")"
  if [ -z "$changed_files" ]; then
    force_full_validation
  else
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      changed_count=$((changed_count + 1))
      case "$path" in
        docs/*|*.md|.github/*)
          # 文档和 GitHub 辅助流程不改变主站运行制品。
          ;;
        wao-client/*|Jenkinsfile.client|deploy/jenkins/client/*)
          # 桌面外壳由 wao-client-release 独立构建发布；不重启 Web/后端。
          ;;
        pom.xml|deploy/Dockerfile|deploy/maven-settings.xml|Jenkinsfile|deploy/jenkins/ci-*.sh)
          # Reactor、镜像或流水线自身变化必须用全量验证证明新门禁可用。
          force_full_validation
          ;;
        wao-server/*)
          add_backend_project wao-server
          case "$path" in
            wao-server/src/test/*) ;;
            *) deploy_required=true ;;
          esac
          ;;
        wao-gateway/*)
          add_backend_project wao-gateway
          case "$path" in
            wao-gateway/src/test/*) ;;
            *) deploy_required=true ;;
          esac
          ;;
        agent-server/*)
          add_backend_project agent-server
          case "$path" in
            agent-server/src/test/*) ;;
            *) deploy_required=true ;;
          esac
          ;;
        wao-web/src/__tests__/*)
          test_wao_web=true
          ;;
        wao-web/*)
          test_wao_web=true
          deploy_required=true
          ;;
        agent-web/src/__tests__/*|agent-web/src/*.spec.*|agent-web/src/*.test.*)
          test_agent_web=true
          ;;
        agent-web/*)
          test_agent_web=true
          deploy_required=true
          ;;
        deploy/ai-runner/test_*.py)
          test_ai_runner=true
          ;;
        deploy/ai-runner/*)
          add_backend_project wao-server
          test_ai_runner=true
          deploy_required=true
          ;;
        *)
          # 未登记路径一律回退全量，新增模块不会被静默漏测。
          force_full_validation
          ;;
      esac
    done <<EOF
$changed_files
EOF
  fi
fi

if [ "$force_full" = true ]; then
  backend_projects=all
elif [ -z "$backend_projects" ]; then
  backend_projects=none
fi

printf '%s\n' \
  "WAO_DEPLOY_REQUIRED=$deploy_required" \
  "WAO_BACKEND_PROJECTS=$backend_projects" \
  "WAO_TEST_WAO_WEB=$test_wao_web" \
  "WAO_TEST_AGENT_WEB=$test_agent_web" \
  "WAO_TEST_AI_RUNNER=$test_ai_runner" \
  "WAO_CHANGED_FILE_COUNT=$changed_count"
