# pulsar-backend

Java 21 + Spring Boot 3 modular monolith for the Pulsar platform.

## Prerequisites
- Java 21 (sdkman recommended)
- Docker + Docker Compose
- `/etc/hosts` entries for `admin.pulsar.local` and each tenant slug

## Quickstart

```bash
docker compose -f infra/docker-compose.yml up -d
export PULSAR_ADMIN_PASSCODE="PULS-DEV-0000"
./gradlew :host-app:bootRun
```

See `../docs/superpowers/specs/2026-04-20-pulsar-platform-design.md` for the design.
