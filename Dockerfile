# Build stage: dependencies are resolved in their own layer so that a source-only
# change does not re-download the world on every image build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Runtime stage: a JRE, not a JDK, and not root.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S ledger && adduser -S ledger -G ledger
COPY --from=build /build/target/settlement-ledger-*.jar app.jar
USER ledger

EXPOSE 8080

# Container-aware heap sizing, and a crash dump worth having if it ever OOMs.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
