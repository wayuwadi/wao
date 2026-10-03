#!/bin/sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
mkdir -p "$fixture/bin" "$fixture/deploy/jenkins" "$fixture/wao-web"
cp "$repo_root/deploy/jenkins/ci-frontend-build.sh" "$fixture/deploy/jenkins/"
cp "$repo_root/deploy/jenkins/cleanup-workspace.sh" "$fixture/deploy/jenkins/"
printf '{"name":"fixture"}\n' >"$fixture/wao-web/package.json"
printf '{"lockfileVersion":3}\n' >"$fixture/wao-web/package-lock.json"

cat >"$fixture/bin/docker" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$MOCK_DOCKER_LOG"
case " $* " in
  *' npm ci '*)
    [ "${MOCK_FAIL_CI:-false}" != true ] || exit 7
    mkdir -p "$MOCK_ROOT/wao-web/node_modules/.bin"
    : >"$MOCK_ROOT/wao-web/node_modules/.package-lock.json"
    printf '#!/bin/sh\nexit 0\n' >"$MOCK_ROOT/wao-web/node_modules/.bin/vite"
    chmod +x "$MOCK_ROOT/wao-web/node_modules/.bin/vite"
    ;;
esac
EOF
chmod +x "$fixture/bin/docker" "$fixture/deploy/jenkins/"*.sh

git -C "$fixture" init -q
git -C "$fixture" config user.name 'WAO fixture'
git -C "$fixture" config user.email 'fixture@invalid.example'
git -C "$fixture" add wao-web/package.json wao-web/package-lock.json
git -C "$fixture" commit -qm 'docs: 初始记录'

run_build() {
  (cd "$fixture" && PATH="$fixture/bin:$PATH" MOCK_ROOT="$fixture" \
    MOCK_DOCKER_LOG="$fixture/docker.log" JENKINS_HOME="$fixture/jenkins" \
    WAO_GIT_SHA="$(git rev-parse HEAD)" MOCK_FAIL_CI="${1:-false}" deploy/jenkins/ci-frontend-build.sh wao-web false true)
}
# 每次构建从宿主 Git 导出，容器不需要 Git。
assert_history() {
  [ "$(cut -f1 "$fixture/wao-web/node_modules/.cache/wao-git-history.tsv" | head -1)" = "$(git -C "$fixture" rev-parse HEAD)" ]
  grep -q 'WAO_GIT_HISTORY=/workspace/wao-web/node_modules/.cache/wao-git-history.tsv' "$fixture/docker.log"
}
ci_count() { grep -c ' npm ci$' "$fixture/docker.log" || true; }

run_build >"$fixture/output"
[ "$(ci_count)" -eq 1 ]
assert_history
run_build >"$fixture/output"
[ "$(ci_count)" -eq 1 ]
grep -q 'dependencies unchanged' "$fixture/output"

printf '{"name":"fixture","changed":true}\n' >"$fixture/wao-web/package.json"
run_build >"$fixture/output"
[ "$(ci_count)" -eq 2 ]

rm "$fixture/wao-web/node_modules/.bin/vite"
run_build >"$fixture/output"
[ "$(ci_count)" -eq 3 ]

printf '{"lockfileVersion":3,"changed":true}\n' >"$fixture/wao-web/package-lock.json"
if run_build true >"$fixture/output" 2>&1; then
  echo 'Failed npm ci incorrectly succeeded' >&2
  exit 1
fi
[ ! -e "$fixture/wao-web/node_modules/.wao-install-key" ]
run_build >"$fixture/output"
[ "$(ci_count)" -eq 5 ]

(cd "$fixture" && WORKSPACE="$fixture/workspace" \
  WAO_RUN_TEMP="$fixture/workspace@tmp/wao-test" \
  deploy/jenkins/cleanup-workspace.sh)
[ -f "$fixture/wao-web/node_modules/.wao-install-key" ]
echo '前端依赖键、失效重装与缓存保留检查通过。'
