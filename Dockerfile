# syntax=docker/dockerfile:1

# --- Build stage: compile the :server fat JAR with the Gradle wrapper ----------
# Temurin 17 matches the project's jvmToolchain(17).
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Warm the Gradle/dependency cache: copy only build scripts first so this layer
# is reused unless the build config changes.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY client/build.gradle.kts ./client/
COPY server/build.gradle.kts ./server/
RUN chmod +x gradlew && ./gradlew --no-daemon :server:dependencies > /dev/null 2>&1 || true

# Now the sources, then build the self-contained jar.
COPY . .
RUN ./gradlew --no-daemon :server:shadowJar

# --- Runtime stage: JRE only ---------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Run as non-root.
RUN useradd --system --uid 10001 --create-home appuser

COPY --from=build /src/server/build/libs/server-all.jar ./server.jar

# Persistent SQLite store lives on a volume so measurements survive restarts.
ENV DB_PATH=/data/measurements.db
RUN mkdir -p /data && chown -R appuser:appuser /data /app
VOLUME ["/data"]

USER appuser

# HTTP+SSE transport for VPS deploy. Endpoint: http://0.0.0.0:3001/sse
EXPOSE 3001

# UTF-8 so non-ASCII entity names aren't mangled (matches the gradle run config).
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "server.jar"]
CMD ["--sse", "3001"]
