FROM alpine:3.20 AS codex-egress-proxy
ARG ALPINE_MIRROR_HOST=dl-cdn.alpinelinux.org
RUN sed -i "s/dl-cdn.alpinelinux.org/${ALPINE_MIRROR_HOST}/g" /etc/apk/repositories \
    && apk add --no-cache curl tinyproxy \
    && install -d -o tinyproxy -g tinyproxy /run/tinyproxy
COPY deploy/tinyproxy/codex-egress.conf /etc/tinyproxy/tinyproxy.conf
USER tinyproxy
ENTRYPOINT ["tinyproxy", "-d", "-c", "/etc/tinyproxy/tinyproxy.conf"]

FROM maven:3.9.9-eclipse-temurin-17 AS build
ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}
WORKDIR /workspace
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
COPY wao-server/pom.xml wao-server/pom.xml
COPY wao-gateway/pom.xml wao-gateway/pom.xml
COPY agent-server/pom.xml agent-server/pom.xml
RUN mvn -B -pl wao-server,wao-gateway,agent-server -am dependency:go-offline -DskipTests

COPY wao-server/src wao-server/src
COPY wao-gateway/src wao-gateway/src
COPY agent-server/src agent-server/src
# Runner 脚本会被打包进 wao-server，后台一键分发更新到各执行电脑。
COPY deploy/ai-runner/wao-ai-runner.py deploy/ai-runner/wao-ai-runner.py
RUN mvn -B -pl wao-server,wao-gateway,agent-server -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS runtime
ARG ALPINE_MIRROR_HOST=dl-cdn.alpinelinux.org
WORKDIR /app
RUN sed -i "s/dl-cdn.alpinelinux.org/${ALPINE_MIRROR_HOST}/g" /etc/apk/repositories \
    && apk add --no-cache curl util-linux-misc \
    && addgroup -S wao \
    && adduser -S wao -G wao
USER wao
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/tmp/heapdump.hprof", "-jar", "app.jar"]

FROM runtime AS server-runtime
COPY --from=build /workspace/wao-server/target/wao-server-*.jar app.jar
EXPOSE 8080

FROM runtime AS gateway-runtime
COPY --from=build /workspace/wao-gateway/target/wao-gateway-*.jar app.jar
EXPOSE 9000

FROM runtime AS agent-runtime
COPY --from=build /workspace/agent-server/target/agent-server-*.jar app.jar
EXPOSE 8081
