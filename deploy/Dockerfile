FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
COPY wao-server/pom.xml wao-server/pom.xml
COPY wao-gateway/pom.xml wao-gateway/pom.xml
RUN mvn -B -pl wao-server,wao-gateway -am dependency:go-offline -DskipTests

COPY wao-server/src wao-server/src
COPY wao-gateway/src wao-gateway/src
RUN mvn -B -pl wao-server,wao-gateway -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache curl \
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
