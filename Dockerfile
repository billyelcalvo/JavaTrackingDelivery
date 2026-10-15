# syntax=docker/dockerfile:1.7

# ---- Build stage: compile and package with the Maven wrapper ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src

# The cache mount keeps ~/.m2 between builds, so only the first build downloads dependencies.
# (dependency:go-offline was dropped: it misses plugins resolved at runtime and broke CI builds.)
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests \
      -Daether.connector.http.retryHandler.count=5 package \
 && java -Djarmode=tools -jar target/tracking-delivery-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: JRE only, non-root, layered jar ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

# Layers ordered from least to most likely to change, so pushes only ship the app layer
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8080 \
    LOG_FORMAT=ecs

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
