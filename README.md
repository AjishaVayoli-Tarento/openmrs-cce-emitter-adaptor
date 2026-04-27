# OpenMRS CCE Emitter Adaptor

## Overview

The **OpenMRS CCE Emitter Adaptor** is a **Spring Boot 3.4 / Java 21** microservice that polls OpenMRS APIs for resource changes (CREATE/UPDATE events) and forwards them to **OpenHIM**, where a downstream mediator wraps and routes events to CCE.

It uses **polling** because OpenMRS does not support FHIR Subscriptions:
- **FHIR R4 API** (`_lastUpdated` queries) for all resource types including Orders (via `ServiceRequest`/`MedicationRequest`) and Visits (via tagged `Encounter`)

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 21 LTS | Runtime |
| Spring Boot | 3.4.x | Application framework |
| Gradle | 8.x (Groovy DSL) | Build tool |
| HAPI FHIR | 7.4.0 | FHIR R4 parsing & client |
| Micrometer + Prometheus | — | Metrics & monitoring |
| WireMock | 3.9.x | Integration test stubs |

## Quick Start

```bash
# Build
./gradlew clean build

# Run
./gradlew bootRun --args='--spring.profiles.active=local'

# Health check
curl -s http://localhost:8080/actuator/health | jq
```

## Architecture

```
OpenMRS Instance ──(FHIR R4 API)──► ★ OpenMRS Emitter Adaptor ★
                                                     │
                                                     │ HTTP POST (FHIR JSON)
                                                     ▼
                                            OpenHIM → CCE Collector → Kafka
```

### Key Components

| Component | Description |
|-----------|-------------|
| `FhirPollerService` | `@Scheduled` — polls FHIR API using `_lastUpdated` |
| `ForwardingEngine` | Forwards to OpenHIM with retry and auth |
| `CheckpointStore` | Persists poll timestamps (file-based) |
| `EmitterProperties` | `@ConfigurationProperties` — type-safe config |

## Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── OpenmrsCceEmitterAdaptorApplication.java
├── config/
│   ├── EmitterProperties.java
│   ├── FhirConfig.java
│   ├── RestClientConfig.java
│   └── SchedulingConfig.java
├── service/
│   ├── CheckpointStore.java
│   ├── FhirPollerService.java
│   ├── ForwardingEngine.java
│   └── ForwardResult.java
└── model/
    └── PollCheckpoint.java
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/architecture.md) | System context, component design, API selection |
| [Configuration Guide](docs/configuration-guide.md) | All properties, env vars, examples |
| [Deployment Guide](docs/deployment-guide.md) | Build, Docker, checklist |
| [API Reference](docs/api-reference.md) | Actuator endpoints, metrics, consumed APIs |
| [Operations Runbook](docs/operations-runbook.md) | Troubleshooting, alerts, monitoring |
| [OpenMRS REST vs FHIR](docs/openmrs-rest-vs-fhir-comparison.md) | API comparison and selection rationale |

## Key Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENMRS_BASE_URL` | `http://localhost:8080/openmrs` | OpenMRS instance URL |
| `OPENMRS_AUTH_TYPE` | `basic` | `basic`, `bearer`, or `oauth2` |
| `OPENHIM_BASE_URL` | `http://localhost:5001/fhir` | OpenHIM target URL |
| `POLLING_FHIR_INTERVAL_SECONDS` | `30` | FHIR poll interval |
| `CHECKPOINT_FILE_PATH` | `./data/checkpoints.json` | Checkpoint persistence |