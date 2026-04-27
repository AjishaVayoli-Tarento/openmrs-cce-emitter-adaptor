# Deployment Guide — OpenMRS CCE Emitter Adaptor

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21 LTS |
| Gradle | 8.x (wrapper included) |
| Docker | 24.x+ (for containerized deployment) |
| OpenMRS | 2.5+ with `fhir2` and `webservices.rest` modules |
| OpenHIM | 8.x+ |

---

## Build

```bash
./gradlew clean build          # with tests
./gradlew clean build -x test  # skip tests
docker build -t openmrs-cce-emitter-adaptor:latest .
```

---

## Run Locally

```bash
./gradlew bootRun --args='--spring.profiles.active=local'

# Verify
curl -s http://localhost:8080/actuator/health | jq
```

---

## Docker Compose

```yaml
services:
  openmrs-cce-emitter-adaptor:
    build: .
    image: openmrs-cce-emitter-adaptor:latest
    container_name: openmrs-cce-emitter-adaptor
    ports:
      - "8080:8080"
    restart: unless-stopped
    volumes:
      - emitter-data:/app/data
    environment:
      SERVER_PORT: 8080
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx512m -XX:+UseG1GC"
      OPENMRS_BASE_URL: http://openmrs:8080/openmrs
      OPENMRS_AUTH_TYPE: basic
      OPENMRS_AUTH_USERNAME: admin
      OPENMRS_AUTH_PASSWORD: Admin123
      OPENHIM_BASE_URL: https://openhim-api.cce.mdtlabs.org/fhir
      OPENHIM_AUTH_TYPE: basic
      OPENHIM_AUTH_USERNAME: openmrs-emitter-client
      OPENHIM_AUTH_PASSWORD: password
      OPENHIM_SSL_TRUST_ALL: "true"
      CHECKPOINT_FILE_PATH: /app/data/checkpoints.json
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  emitter-data:
```

---

## Resource Allocation

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 0.5 vCPU | 1 vCPU |
| Memory | 256 MB | 512 MB |
| Disk | 10 MB | 50 MB |

---

## Deployment Checklist

- [ ] OpenMRS instance is reachable from the emitter
- [ ] OpenMRS user credentials have sufficient privileges for FHIR + REST APIs
- [ ] OpenHIM channel is configured for the emitter client
- [ ] Checkpoint file volume is mounted and writable
- [ ] Environment variables for credentials are set (not hardcoded)
- [ ] `OPENHIM_SSL_TRUST_ALL` is `false` in production
- [ ] Health check endpoint responds: `GET /actuator/health`
- [ ] Prometheus scraping configured for `/actuator/prometheus`
