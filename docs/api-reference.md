# API Reference — OpenMRS CCE Emitter Adaptor

## Overview

This is a **polling service** — it does not expose REST endpoints for triggering event forwarding. It exposes standard **Spring Boot Actuator** endpoints for health and monitoring.

---

## Actuator Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/actuator/health` | Overall health status |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe |
| GET | `/actuator/prometheus` | Prometheus metrics |
| GET | `/actuator/metrics` | Metrics (JSON) |
| GET | `/actuator/info` | Application info |

---

## Custom Metrics

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `openmrs.emitter.poll.executed` | Counter | `resourceType` | Poll cycles completed |
| `openmrs.emitter.poll.resources.detected` | Counter | `resourceType` | Changed resources detected |
| `openmrs.emitter.poll.duration` | Timer | `resourceType` | Poll cycle duration |
| `openmrs.emitter.forward.success` | Counter | `resourceType` | Successful forwards to OpenHIM |
| `openmrs.emitter.forward.failure` | Counter | `resourceType` | Failed forwards after all retries |
| `openmrs.emitter.checkpoint.age.seconds` | Gauge | `resourceType` | Age of last checkpoint |

---

## OpenMRS APIs Consumed

### FHIR R4 API (`/ws/fhir2/R4`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/{ResourceType}?_lastUpdated=gt{ts}&_count=50&_sort=-_lastUpdated` | Delta polling |
| GET | `/metadata` | Health check (CapabilityStatement) |

### OpenHIM (Target)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/{baseUrl}/{ResourceType}` | Forward FHIR resources |
