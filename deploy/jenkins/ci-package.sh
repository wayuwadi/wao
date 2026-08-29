#!/bin/sh
set -eu

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
: "${WAO_RUN_TEMP:?WAO_RUN_TEMP is required}"

umask 077
install -d "$WAO_RUN_TEMP"
git archive --format=tar.gz -o "$WAO_RUN_TEMP/wao-release.tgz" HEAD

docker build --target server-runtime -t "wao-server:$WAO_GIT_SHA" -f deploy/Dockerfile .
docker build --target agent-runtime -t "wao-agent-server:$WAO_GIT_SHA" -f deploy/Dockerfile .
docker build --target gateway-runtime -t "wao-gateway:$WAO_GIT_SHA" -f deploy/Dockerfile .
docker build --target codex-egress-proxy -t "wao-codex-egress-proxy:$WAO_GIT_SHA" -f deploy/Dockerfile .

# Linux1 生产从节点不能依赖 Docker Hub 在发布窗口内保持可达。把 Compose
# 所需的固定第三方镜像和应用镜像一起打进不可变发布包。
for image in mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2; do
  docker pull "$image"
done

docker save \
  "wao-server:$WAO_GIT_SHA" \
  "wao-agent-server:$WAO_GIT_SHA" \
  "wao-gateway:$WAO_GIT_SHA" \
  "wao-codex-egress-proxy:$WAO_GIT_SHA" \
  mysql:9.7.0 \
  redis:7-alpine \
  jeessy/ddns-go:v6.17.2 \
  | gzip -1 >"$WAO_RUN_TEMP/wao-images.tar.gz"
gzip -t "$WAO_RUN_TEMP/wao-images.tar.gz"
chmod 0640 "$WAO_RUN_TEMP/wao-release.tgz" "$WAO_RUN_TEMP/wao-images.tar.gz"
