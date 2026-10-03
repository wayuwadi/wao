#!/bin/sh
set -eu

project_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
planner="$project_root/deploy/jenkins/ci-change-plan.sh"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT HUP INT TERM

git -C "$fixture" init -q
git -C "$fixture" config user.name 'WAO CI test'
git -C "$fixture" config user.email 'ci-test@invalid.example'
printf 'base\n' >"$fixture/README.md"
git -C "$fixture" add README.md
git -C "$fixture" commit -qm base
base="$(git -C "$fixture" rev-parse HEAD)"

assert_plan() {
  expected="$1"
  plan="$2"
  printf '%s\n' "$plan" | grep -qx "$expected" || {
    printf 'Missing plan entry %s in:\n%s\n' "$expected" "$plan" >&2
    exit 1
  }
}

mkdir -p "$fixture/docs"
printf 'docs\n' >"$fixture/docs/change.md"
git -C "$fixture" add docs/change.md
git -C "$fixture" commit -qm docs
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=false' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/wao-client/src"
printf 'desktop shell\n' >"$fixture/wao-client/src/App.vue"
git -C "$fixture" add wao-client
git -C "$fixture" commit -qm desktop-client-only
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=false' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=false' "$plan"
assert_plan 'WAO_TEST_AGENT_WEB=false' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/wao-server/src/main/java"
printf 'server\n' >"$fixture/wao-server/src/main/java/Example.java"
git -C "$fixture" add wao-server
git -C "$fixture" commit -qm server
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=true' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=wao-server' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=false' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/wao-web/src" "$fixture/agent-web/src"
printf 'web\n' >"$fixture/wao-web/src/App.vue"
printf 'agent\n' >"$fixture/agent-web/src/App.vue"
git -C "$fixture" add wao-web agent-web
git -C "$fixture" commit -qm frontends
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=true' "$plan"
assert_plan 'WAO_TEST_AGENT_WEB=true' "$plan"
assert_plan 'WAO_WEB_ONLY=true' "$plan"

# 同批次混入后端变更必须回到完整发布。
printf 'server change\n' >>"$fixture/wao-server/src/main/java/Example.java"
git -C "$fixture" add wao-server
git -C "$fixture" commit -qm mixed-backend
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_WEB_ONLY=false' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/wao-web/src/__tests__"
printf 'web test\n' >"$fixture/wao-web/src/__tests__/App.spec.ts"
git -C "$fixture" add wao-web
git -C "$fixture" commit -qm wao-web-test-only
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=false' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=true' "$plan"
assert_plan 'WAO_TEST_AGENT_WEB=false' "$plan"
assert_plan 'WAO_TEST_AI_RUNNER=false' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/agent-web/src/components"
printf 'agent test\n' >"$fixture/agent-web/src/components/PublishCenter.spec.ts"
git -C "$fixture" add agent-web
git -C "$fixture" commit -qm agent-web-test-only
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=false' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=false' "$plan"
assert_plan 'WAO_TEST_AGENT_WEB=true' "$plan"
assert_plan 'WAO_TEST_AI_RUNNER=false' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/deploy/ai-runner"
printf 'runner test\n' >"$fixture/deploy/ai-runner/test_runner.py"
git -C "$fixture" add deploy/ai-runner
git -C "$fixture" commit -qm ai-runner-test-only
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=false' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=false' "$plan"
assert_plan 'WAO_TEST_AGENT_WEB=false' "$plan"
assert_plan 'WAO_TEST_AI_RUNNER=true' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
printf 'pipeline\n' >"$fixture/Jenkinsfile"
git -C "$fixture" add Jenkinsfile
git -C "$fixture" commit -qm pipeline
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=true' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=all' "$plan"
assert_plan 'WAO_WEB_ONLY=true' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=true' "$plan"
assert_plan 'WAO_TEST_AGENT_WEB=true' "$plan"

plan="$(cd "$fixture" && sh "$planner" missing-ref HEAD)"
assert_plan 'WAO_DEPLOY_REQUIRED=true' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=all' "$plan"
assert_plan 'WAO_WEB_ONLY=false' "$plan"

base="$(git -C "$fixture" rev-parse HEAD)"
mkdir -p "$fixture/wao-game/games/dino"
printf 'engine\n' >"$fixture/wao-game/games/dino/engine.ts"
git -C "$fixture" add wao-game
git -C "$fixture" commit -qm game-only
plan="$(cd "$fixture" && sh "$planner" "$base" HEAD)"
assert_plan 'WAO_WEB_ONLY=true' "$plan"
assert_plan 'WAO_BACKEND_PROJECTS=none' "$plan"
assert_plan 'WAO_DEPLOY_REQUIRED=true' "$plan"
assert_plan 'WAO_TEST_WAO_WEB=false' "$plan"
echo 'Jenkins change plan tests passed.'
