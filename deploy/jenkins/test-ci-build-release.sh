#!/bin/sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
# Concurrent npm installs must stay outside Docker's backend build context.
grep -qx 'wao-web' "$repo_root/.dockerignore"
grep -qx 'agent-web' "$repo_root/.dockerignore"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
mkdir -p "$fixture/deploy/jenkins" "$fixture/state" "$fixture/tmp"
cp "$repo_root/deploy/jenkins/ci-build-release.sh" "$fixture/deploy/jenkins/"

cat >"$fixture/deploy/jenkins/ci-build-frontends.sh" <<'EOF'
#!/bin/sh
set -eu
touch "$MOCK_STATE/web.started"
attempt=0
while [ ! -f "$MOCK_STATE/package.started" ]; do
  attempt=$((attempt + 1))
  [ "$attempt" -le 100 ] || exit 9
  sleep 0.02
done
echo 'web fixture complete'
exit "${WEB_RESULT:-0}"
EOF
cat >"$fixture/deploy/jenkins/ci-package.sh" <<'EOF'
#!/bin/sh
set -eu
touch "$MOCK_STATE/package.started"
attempt=0
while [ ! -f "$MOCK_STATE/web.started" ]; do
  attempt=$((attempt + 1))
  [ "$attempt" -le 100 ] || exit 9
  sleep 0.02
done
echo 'package fixture complete'
exit "${PACKAGE_RESULT:-0}"
EOF
chmod +x "$fixture/deploy/jenkins/"*.sh

run_case() {
  rm -f "$fixture/state/"*.started
  (cd "$fixture" && MOCK_STATE="$fixture/state" WAO_RUN_TEMP="$fixture/tmp" \
    WEB_RESULT="$1" PACKAGE_RESULT="$2" \
    deploy/jenkins/ci-build-release.sh >"$fixture/output" 2>&1)
}

run_case 0 0
grep -q '网页与镜像发布制品均已完成' "$fixture/output"
if run_case 7 0; then echo 'Failed to propagate frontend error' >&2; exit 1; fi
grep -q '网页=7，镜像与源码包=0' "$fixture/output"
if run_case 0 8; then echo 'Failed to propagate package error' >&2; exit 1; fi
grep -q '网页=0，镜像与源码包=8' "$fixture/output"
echo '并行构建与双路失败传播检查通过。'
