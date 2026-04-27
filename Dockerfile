# Multi-stage Dockerfile for the pulsar-backend Spring Boot app.
#
# Stage 1: build the fat jar with the dental domain profile (the only
# vertical we ship today). To build a different domain, pass
# --build-arg DOMAIN=<name> matching a key in host-app/build.gradle's
# `domainModules` map.
#
# Stage 2: minimal JRE-21 runtime that runs the jar. The container
# listens on SERVER_PORT (default 18080) — overridable via env. All
# other config (DB url, JWT secret, automation base url, sync secret)
# is supplied at runtime via env vars per .env.example.
#
# Built locally:
#   docker build -t pulsar-backend:dental .
#
# Run with the existing MySQL compose:
#   docker compose -f docker-compose.yml up -d
#   docker run --rm --network host \
#     -e PULSAR_ADMIN_PASSCODE=PULS-DEV-0000 \
#     -e PULSAR_JWT_SECRET=<32+ chars> \
#     -e SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3316/pulsar_platform \
#     pulsar-backend:dental

# ── stage 1: build ─────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy gradle wrapper + settings first so the heavy `gradle --version`
# fetch is cached separately from source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

# Pre-fetch dependencies. With no source yet this just resolves the
# gradle distribution; once we copy source the `bootJar` task downloads
# any missing libs.
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Copy module build files separately so Docker can cache the
# dependency-resolution step.
COPY kernel/build.gradle ./kernel/
COPY host-app/build.gradle ./host-app/
COPY modules ./modules

# Now the source — this layer changes on every commit.
COPY kernel/src ./kernel/src
COPY host-app/src ./host-app/src

ARG DOMAIN=dental
RUN ./gradlew :host-app:bootJar --no-daemon -Pdomain=${DOMAIN}

# ── stage 2: runtime ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user. The fat jar is the only thing we run.
RUN useradd -r -u 1001 -g root pulsar
COPY --from=build /src/host-app/build/libs/*.jar /app/app.jar
RUN chown -R pulsar:root /app
USER pulsar

# Default port matches .env.example. Override with `-e SERVER_PORT=…`.
ENV SERVER_PORT=18080
EXPOSE 18080

# Healthcheck: Spring Boot Actuator's /actuator/health if exposed,
# otherwise the admin login endpoint (returns 405 on GET, which is
# enough to confirm the listener is up).
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=30s \
  CMD curl -fsS "http://localhost:${SERVER_PORT}/api/admin/login" -o /dev/null -X OPTIONS || exit 1

ENTRYPOINT ["sh","-c","exec java -jar /app/app.jar"]
