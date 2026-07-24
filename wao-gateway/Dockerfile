FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY wao-server/pom.xml wao-server/pom.xml
COPY wao-gateway/pom.xml wao-gateway/pom.xml
RUN mvn -B -pl wao-gateway -am dependency:go-offline
COPY wao-gateway/src wao-gateway/src
RUN mvn -B -pl wao-gateway -am package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl && addgroup -S wao && adduser -S wao -G wao
COPY --from=build /workspace/wao-gateway/target/wao-gateway-*.jar app.jar
USER wao
EXPOSE 9000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/tmp/heapdump.hprof", "-jar", "app.jar"]
