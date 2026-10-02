FROM alpine:3.20 AS codex-egress-proxy
ARG ALPINE_MIRROR_HOST=dl-cdn.alpinelinux.org
RUN sed -i "s/dl-cdn.alpinelinux.org/${ALPINE_MIRROR_HOST}/g" /etc/apk/repositories \
    && apk add --no-cache curl tinyproxy \
    && install -d -o tinyproxy -g tinyproxy /run/tinyproxy
COPY deploy/tinyproxy/codex-egress.conf /etc/tinyproxy/tinyproxy.conf
COPY deploy/scripts/healthcheck-codex-egress.sh /usr/local/bin/healthcheck-codex-egress
COPY deploy/scripts/start-codex-vpn.sh /usr/local/bin/start-codex-vpn
RUN chmod 0755 /usr/local/bin/healthcheck-codex-egress /usr/local/bin/start-codex-vpn
USER tinyproxy
ENTRYPOINT ["tinyproxy", "-d", "-c", "/etc/tinyproxy/tinyproxy.conf"]

FROM maven:3.9.9-eclipse-temurin-17 AS build
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}
ENV MAVEN_OPTS="-Xmx2048m -XX:ActiveProcessorCount=2"
WORKDIR /workspace
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
COPY wao-server/pom.xml wao-server/pom.xml
COPY wao-gateway/pom.xml wao-gateway/pom.xml
COPY agent-server/pom.xml agent-server/pom.xml
RUN mvn -B -pl wao-server,wao-gateway -am dependency:go-offline -DskipTests

COPY wao-server/src wao-server/src
COPY wao-gateway/src wao-gateway/src
# Runner 脚本会被打包进 wao-server，后台一键分发更新到各执行电脑。
COPY deploy/ai-runner/wao-ai-runner.py deploy/ai-runner/wao-ai-runner.py
RUN mvn -B -pl wao-server,wao-gateway -am package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-alpine AS runtime
ARG ALPINE_MIRROR_HOST=dl-cdn.alpinelinux.org
WORKDIR /app
RUN sed -i "s/dl-cdn.alpinelinux.org/${ALPINE_MIRROR_HOST}/g" /etc/apk/repositories \
    && apk add --no-cache curl \
    && addgroup -S wao \
    && adduser -S wao -G wao
USER wao
# Use explicit heap ceilings: percentage ergonomics can use host RAM even when
# cgroup metrics report the container limit (observed on the Linux2 host).
# Gateway/agent have 1 GiB containers; leave room for native buffers and threads.
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m"
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/tmp/heapdump.hprof", "-jar", "app.jar"]

FROM runtime AS server-runtime
# The application container has a 3 GiB limit; 200 MiB dual-write files are materialized in memory.
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx2048m"
COPY --from=build /workspace/wao-server/target/wao-server-*.jar app.jar
EXPOSE 8080

FROM runtime AS gateway-runtime
COPY --from=build /workspace/wao-gateway/target/wao-gateway-*.jar app.jar
EXPOSE 9000
