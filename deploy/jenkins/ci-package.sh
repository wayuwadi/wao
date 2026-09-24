#!/bin/sh
set -eu

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
: "${WAO_RUN_TEMP:?WAO_RUN_TEMP is required}"

umask 077
install -d "$WAO_RUN_TEMP"
git archive --format=tar.gz -o "$WAO_RUN_TEMP/wao-release.tgz" HEAD

# 静态发布沿用不可变源码归档与前端产物，后端不变时不重复导出整套镜像。
if [ "${WAO_WEB_ONLY:-false}" = true ]; then
  echo 'Browser-only release: backend images unchanged.'
  exit 0
fi

pull_image() {
  image="$1"
  attempt=1
  max_attempts=3
  while ! docker pull "$image"; do
    if [ "$attempt" -ge "$max_attempts" ]; then
      printf 'Failed to pull %s after %s attempts.\n' "$image" "$attempt" >&2
      return 1
    fi
    printf 'Failed to pull %s (attempt %s/%s); retrying in 5 seconds.\n' \
      "$image" "$attempt" "$max_attempts" >&2
    attempt=$((attempt + 1))
    sleep 5
  done
}

docker build --target server-runtime -t "wao-server:$WAO_GIT_SHA" -f deploy/Dockerfile .
docker build --target agent-runtime -t "wao-agent-server:$WAO_GIT_SHA" -f deploy/Dockerfile .
docker build --target gateway-runtime -t "wao-gateway:$WAO_GIT_SHA" -f deploy/Dockerfile .
docker build --target codex-egress-proxy -t "wao-codex-egress-proxy:$WAO_GIT_SHA" -f deploy/Dockerfile .

# Linux1 生产从节点不能依赖 Docker Hub 在发布窗口内保持可达。把 Compose
# 所需的固定第三方镜像和应用镜像一起打进不可变发布包。
for image in mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2; do
  # 已有本地副本时直接复用，避免每次打包都访问 Docker Hub；Linux1 按镜像 ID 对齐。
  docker image inspect "$image" >/dev/null 2>&1 || pull_image "$image"
done

# 影子构建仍生成完整的可交付镜像包；生产发布则在部署阶段按 Linux1
# 已有镜像 ID 选择缺少的部分，避免每次打包和上传同一批镜像。
if [ "${DEPLOY_PRODUCTION:-true}" != true ]; then
  docker save \
    "wao-server:$WAO_GIT_SHA" "wao-agent-server:$WAO_GIT_SHA" \
    "wao-gateway:$WAO_GIT_SHA" "wao-codex-egress-proxy:$WAO_GIT_SHA" \
    mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2 \
    | gzip -1 >"$WAO_RUN_TEMP/wao-images.tar.gz"
  gzip -t "$WAO_RUN_TEMP/wao-images.tar.gz"
  chmod 0640 "$WAO_RUN_TEMP/wao-images.tar.gz"
fi
chmod 0640 "$WAO_RUN_TEMP/wao-release.tgz"
