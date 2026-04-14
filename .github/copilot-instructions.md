# Copilot Instructions — OpenMRS CCE Emitter Adaptor

## Project Overview

This is a **Spring Boot 3.4 / Java 21** microservice that acts as an **OpenMRS-specific Emitter Adaptor** for the Care Coordination Engine (CCE) platform. It **polls** the OpenMRS FHIR R4 API for resource changes (CREATE/UPDATE events), and forwards detected changes to **OpenHIM**, where an **OpenHIM Emitter Adaptor** (registered as a mediator) wraps and routes events to CCE.

In the CCE architecture (see *CCE Solution Design v0.3*, Section 4.3.7.2), an **Emitter Adaptor** captures events from an external system and submits them toward CCE for compliance tracking. This service is the OpenMRS-flavored implementation: it polls the OpenMRS FHIR2 module for changed resources and forwards them to OpenHIM. The adaptor does **not** need to understand CCE's compliance protocol model — it captures OpenMRS resource changes and forwards them; CCE's inferred matching handles the rest.

Unlike the **FHIR CCE Emitter Adaptor** (which subscribes to FHIR REST-hook Subscriptions), this service uses **polling** because OpenMRS does not support FHIR R4 Subscriptions. It polls using `_lastUpdated` on the FHIR API, with persisted checkpoints to track the last poll time per resource type.

- **Group:** `org.openphc.cce`
- **Artifact:** `openmrs-cce-emitter-adaptor`
- **Package root:** `org.openphc.cce.emitter`
- **Build:** Gradle Groovy DSL (`build.gradle`)
- **FHIR version:** R4 (using HAPI FHIR Client library 7.4.0 — a Java FHIR SDK, not server-specific)
- **Container:** Docker multi-stage build (Eclipse Temurin 21)

### What This Service Does

1. **Polls** OpenMRS FHIR R4 API (`/ws/fhir2/R4`) for resource changes using `_lastUpdated=gt{checkpoint}`
2. **Persists** poll checkpoints (last poll timestamp per resource type) to survive restarts — file-based (`checkpoints.json`)
3. **Parses** incoming FHIR JSON to extract resource metadata (type, ID) using HAPI FHIR client library
5. **Forwards** detected changes (raw FHIR JSON) synchronously to OpenHIM
6. **Adds** auth headers for OpenHIM (Basic Auth, JWT, or Custom Token)
7. **Retries** failed forwards with configurable linear backoff
8. **Exposes** Prometheus metrics and health probes for observability

### What This Service Does NOT Do

- Transform or enrich FHIR resources — they are forwarded as-is
- Validate FHIR profile conformance — only structural parse for metadata extraction
- Produce or consume Kafka events — this is HTTP-only forwarding
- Wrap payloads in CloudEvents envelopes — the CloudEvents wrapping happens downstream (OpenHIM mediator or CCE Collector Service)
- Rate-limit or apply mTLS — those are infrastructure-layer concerns
- Perform compliance tracking or protocol matching — that is CCE core's responsibility (Compliance Service)
- Act as a Receiver Adaptor — it only emits events toward CCE, it does not receive intelligence events from CCE
- Subscribe to FHIR Subscriptions — OpenMRS does not support REST-hook Subscriptions; this service polls instead
- Detect deleted/voided records — planned as a future enhancement

---

## Position in the CCE Platform

This service is an **OpenMRS-specific Emitter Adaptor**. It is deployed on the **source system side** — co-located with the OpenMRS instance. It polls OpenMRS APIs for resource changes and forwards them to **OpenHIM**. The **OpenHIM Emitter Adaptor** (registered as a mediator in OpenHIM) handles CloudEvents wrapping and routing to CCE.

### Deployment Flow

```
  OpenMRS Instance ──(FHIR R4 API)──► ★ OpenMRS Emitter Adaptor ★
                                                       │
                                                       │ HTTP POST (FHIR JSON)
                                                       ▼
                                              OpenHIM (Mediator)
                                                       │
                                                       │ CloudEvents
                                                       ▼
                                              CCE Collector → Kafka
```

### Sister Services

| Service | Mechanism | Source System |
|---------|-----------|---------------|
| **fhir-cce-emitter-adaptor** | FHIR REST-hook Subscriptions (push) | Any FHIR R4 server (SPICE, etc.) |
| **openmrs-cce-emitter-adaptor** ★ | OpenMRS API polling (pull) | OpenMRS |
| **openhim-cce-emitter-adaptor** | OpenHIM mediator (CloudEvents wrap) | N/A — downstream from both |

---

## Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Gradle (Groovy DSL) | 8.x |
| FHIR library | HAPI FHIR Client | 7.4.0 |
| HTTP client | Spring `RestTemplate` | (Spring Boot managed) |
| Scheduling | Spring `@Scheduled` | (Spring Boot managed) |
| Observability | SLF4J + Logback, Micrometer, Prometheus | (Spring Boot managed) |
| Monitoring | Spring Boot Actuator | (Spring Boot managed) |
| Testing | JUnit 5, MockMvc, Mockito, WireMock | (Spring Boot managed) |
| Container runtime | Eclipse Temurin | 21 (JDK build, JRE runtime) |

### Key Gradle Dependencies

```groovy
// HAPI FHIR (client library for FHIR R4)
implementation "ca.uhn.hapi.fhir:hapi-fhir-base:${hapiFhirVersion}"
implementation "ca.uhn.hapi.fhir:hapi-fhir-client:${hapiFhirVersion}"
implementation "ca.uhn.hapi.fhir:hapi-fhir-structures-r4:${hapiFhirVersion}"

// Spring Boot starters
implementation 'org.springframework.boot:spring-boot-starter-web'
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'org.springframework.boot:spring-boot-starter-actuator'

// Metrics
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    OpenMRS CCE Emitter Adaptor                       │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  FHIR Poller (@Scheduled)                                     │  │
│  │  - Patient, Encounter (includes Visits), Observation,          │  │
│  │    Condition, Immunization, DiagnosticReport,                   │  │
│  │    AllergyIntolerance, Procedure, MedicationRequest (DrugOrder),│  │
│  │    MedicationDispense, MedicationAdministration,                │  │
│  │    ServiceRequest (TestOrder), Location, Practitioner           │  │
│  │  - Uses _lastUpdated=gt{checkpoint}                             │  │
│  │  - Runs every 30-60 seconds (configurable)                      │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Checkpoint Store + Forwarding Engine                         │  │
│  │  - Persists lastPollTime per resource type (file-based)       │  │
│  │  - Forwards FHIR JSON to OpenHIM with retry + auth            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Components

| Package | Class | Purpose |
|---------|-------|---------|
| `config` | `EmitterProperties` | `@ConfigurationProperties(prefix = "emitter")` — type-safe config for OpenMRS, OpenHIM, polling, auth, retry |
| `config` | `FhirConfig` | Singleton `FhirContext.forR4()` bean |
| `config` | `RestClientConfig` | Standard + trust-all `RestTemplate` beans |
| `config` | `SchedulingConfig` | `@EnableScheduling` + thread pool config for poller |
| `config` | `LoggingFilter` | MDC request tracing (requestId, resourceType) |
| `config` | `ObservabilityConfig` | Micrometer common tags registration |
| `service` | `FhirPollerService` | `@Scheduled` — polls OpenMRS FHIR API for resource changes using `_lastUpdated` |
| `service` | `ForwardingEngine` | Synchronous forwarding to OpenHIM with retry and auth support |
| `service` | `CheckpointStore` | Persists last poll timestamps per resource type (file-based; database planned as future enhancement) |

### Planned Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── OpenmrsCceEmitterAdaptorApplication.java      # Spring Boot entry point
├── config/
│   ├── EmitterProperties.java                    # @ConfigurationProperties(prefix="emitter")
│   ├── FhirConfig.java                           # FhirContext.forR4() singleton bean
│   ├── LoggingFilter.java                        # MDC request tracing
│   ├── ObservabilityConfig.java                  # Micrometer common tags
│   ├── RestClientConfig.java                     # Standard + trust-all RestTemplate beans
│   └── SchedulingConfig.java                     # @EnableScheduling + thread pool
├── service/
│   ├── CheckpointStore.java                      # Persists poll checkpoints (file or database)
│   ├── FhirPollerService.java                    # @Scheduled FHIR polling with _lastUpdated
│   ├── ForwardingEngine.java                     # Synchronous forwarding to OpenHIM with retry
│   └── ForwardResult.java                        # Forwarding outcome record
└── model/
    └── PollCheckpoint.java                       # Checkpoint data (resourceType, lastUpdated)
```

---

## OpenMRS API Strategy

This service polls the **OpenMRS FHIR R4 API** exclusively. The `fhir2` module provides `_lastUpdated` for efficient server-side delta queries across all required resource types.

> **Note:** OpenMRS Visits are returned as FHIR `Encounter` resources with `meta.tag.code = "visit"` — they are automatically included in the Encounter poll. OpenMRS TestOrders map to FHIR `ServiceRequest` and DrugOrders map to FHIR `MedicationRequest`.

### FHIR Resources Polled

| Resource | FHIR Endpoint | OpenMRS Mapping | Polling Strategy |
|----------|---------------|-----------------|------------------|
| Patient | `/ws/fhir2/R4/Patient` | Patient | `_lastUpdated=gt{checkpoint}` |
| Encounter | `/ws/fhir2/R4/Encounter` | Encounter + Visit (tagged) | `_lastUpdated=gt{checkpoint}` |
| Observation | `/ws/fhir2/R4/Observation` | Obs | `_lastUpdated=gt{checkpoint}` |
| Condition | `/ws/fhir2/R4/Condition` | Condition | `_lastUpdated=gt{checkpoint}` |
| Immunization | `/ws/fhir2/R4/Immunization` | Obs (vaccine concepts) | `_lastUpdated=gt{checkpoint}` |
| DiagnosticReport | `/ws/fhir2/R4/DiagnosticReport` | Encounter + grouped Obs | `_lastUpdated=gt{checkpoint}` |
| AllergyIntolerance | `/ws/fhir2/R4/AllergyIntolerance` | Allergy | `_lastUpdated=gt{checkpoint}` |
| Procedure | `/ws/fhir2/R4/Procedure` | Obs (procedure concepts) | `_lastUpdated=gt{checkpoint}` |
| MedicationRequest | `/ws/fhir2/R4/MedicationRequest` | DrugOrder | `_lastUpdated=gt{checkpoint}` |
| MedicationDispense | `/ws/fhir2/R4/MedicationDispense` | Dispensing module | `_lastUpdated=gt{checkpoint}` |
| MedicationAdministration | `/ws/fhir2/R4/MedicationAdministration` | Obs (medication concepts) | `_lastUpdated=gt{checkpoint}` |
| ServiceRequest | `/ws/fhir2/R4/ServiceRequest` | TestOrder | `_lastUpdated=gt{checkpoint}` |
| Location | `/ws/fhir2/R4/Location` | Location | `_lastUpdated=gt{checkpoint}` |
| Practitioner | `/ws/fhir2/R4/Practitioner` | Provider | `_lastUpdated=gt{checkpoint}` |

### Key FHIR Polling Pattern

```
GET /ws/fhir2/R4/{ResourceType}?_lastUpdated=gt{checkpoint}&_count=50&_sort=-_lastUpdated

# Page through results via Bundle.link[rel="next"]
# Forward each changed resource to OpenHIM
# Store max(_lastUpdated) from results as new checkpoint
# Add 5-second overlap window to account for _lastUpdated inconsistencies
# Deduplicate by resource ID within poll cycle
```

> **Why FHIR-only?** The `fhir2` module now covers all required resource types: TestOrders map to `ServiceRequest`, DrugOrders map to `MedicationRequest`, and Visits are included as tagged `Encounter` resources. ProgramEnrollments (`EpisodeOfCare` in FHIR) have a read-only endpoint without search/`_lastUpdated` support — REST polling for ProgramEnrollments may be added as a future enhancement if required.

---

## Processing Flow

### FHIR Polling Flow (core path)

1. **`FhirPollerService.poll()`** — triggered by `@Scheduled` at configurable interval (default 30s)
2. **Read checkpoint** — `CheckpointStore.getCheckpoint(resourceType)` returns last poll timestamp, or `null` if none exists
3. **Resolve query time** — if checkpoint exists: `checkpoint - overlapSeconds`; if null (first boot or lost): `now() - (intervalSeconds + overlapSeconds)` as sliding window seed
4. **Build FHIR query** — `GET /ws/fhir2/R4/{ResourceType}?_lastUpdated=gt{queryTime}&_count=50&_sort=-_lastUpdated`
5. **Authenticate** — Basic Auth header attached to request
6. **Execute query** — uses `RestTemplate` to GET from OpenMRS FHIR API
7. **Parse FHIR Bundle** — uses `fhirContext.newJsonParser()` to parse the response Bundle
8. **Iterate entries** — for each `Bundle.entry.resource`, extract the raw JSON
9. **Deduplicate** — skip resources already forwarded in this poll cycle (by resource type + ID)
10. **Forward to OpenHIM** — `ForwardingEngine.forward(resourceJson)` with retry
11. **Page through** — follow `Bundle.link[rel="next"]` until no more pages
12. **Update checkpoint** — `CheckpointStore.saveCheckpoint(resourceType, maxLastUpdated)`
13. **Increment metrics** — `poll.success` / `poll.failure` counters

---

## Configuration

### Profiles

| Profile | YAML file | Key overrides |
|---------|-----------|---------------|
| `default` | `application.yml` | Base config — all values env-var-wrapped with sensible defaults |
| `local` | `application-local.yml` | Local dev — `DEBUG` logging, `ssl-trust-all: true`, `show-details: always` |
| `staging` | `application-staging.yml` | Pre-production — `INFO` logging |
| `production` | `application-production.yml` | Production — `WARN` root logging, `show-details: never`, more retries |

### Base Configuration (`application.yml`)

```yaml
server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful
  servlet:
    context-path: /

spring:
  application:
    name: openmrs-cce-emitter-adaptor
  lifecycle:
    timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:30s}

emitter:
  openmrs:
    name: "${OPENMRS_NAME:openmrs}"
    base-url: "${OPENMRS_BASE_URL:http://localhost:8080/openmrs}"
    fhir-path: "${OPENMRS_FHIR_PATH:/ws/fhir2/R4}"
    auth:
      type: "${OPENMRS_AUTH_TYPE:basic}"                  # basic | bearer | oauth2
      username: "${OPENMRS_AUTH_USERNAME:admin}"
      password: "${OPENMRS_AUTH_PASSWORD:Admin123}"
      token: "${OPENMRS_AUTH_TOKEN:}"
      oauth2:
        token-url: "${OPENMRS_AUTH_OAUTH2_TOKEN_URL:}"
        client-id: "${OPENMRS_AUTH_OAUTH2_CLIENT_ID:}"
        client-secret: "${OPENMRS_AUTH_OAUTH2_CLIENT_SECRET:}"
        scope: "${OPENMRS_AUTH_OAUTH2_SCOPE:}"

  openhim:
    name: "${OPENHIM_NAME:openhim}"
    base-url: "${OPENHIM_BASE_URL:http://localhost:5001/fhir}"
    auth:
      type: "${OPENHIM_AUTH_TYPE:basic}"                  # none | basic | jwt | custom-token
      username: "${OPENHIM_AUTH_USERNAME:}"
      password: "${OPENHIM_AUTH_PASSWORD:}"
      token: "${OPENHIM_AUTH_TOKEN:}"
    ssl-trust-all: ${OPENHIM_SSL_TRUST_ALL:false}
    append-resource-type: ${OPENHIM_APPEND_RESOURCE_TYPE:true}
    retry:
      max-attempts: ${OPENHIM_RETRY_MAX_ATTEMPTS:3}
      backoff-ms: ${OPENHIM_RETRY_BACKOFF_MS:2000}

  polling:
    fhir:
      enabled: ${POLLING_FHIR_ENABLED:true}
      interval-seconds: ${POLLING_FHIR_INTERVAL_SECONDS:30}
      page-size: ${POLLING_FHIR_PAGE_SIZE:50}
      overlap-seconds: ${POLLING_FHIR_OVERLAP_SECONDS:5}
      resource-types: ${POLLING_FHIR_RESOURCE_TYPES:Patient,Encounter,Observation,Condition,Immunization,DiagnosticReport,AllergyIntolerance,Procedure,MedicationRequest,MedicationDispense,MedicationAdministration,ServiceRequest,Location,Practitioner}

  checkpoint:
    store-type: "${CHECKPOINT_STORE_TYPE:file}"            # file (database planned as future enhancement)
    file-path: "${CHECKPOINT_FILE_PATH:./data/checkpoints.json}"

logging:
  level:
    root: ${LOG_LEVEL_ROOT:INFO}
    org.openphc.cce: ${LOG_LEVEL_APP:INFO}
    org.springframework.web: ${LOG_LEVEL_SPRING_WEB:INFO}
    ca.uhn.fhir: ${LOG_LEVEL_FHIR:INFO}

management:
  endpoints:
    web:
      exposure:
        include: ${MANAGEMENT_ENDPOINTS_INCLUDE:health,info,prometheus,metrics}
  endpoint:
    health:
      show-details: ${HEALTH_SHOW_DETAILS:when-authorized}
      probes:
        enabled: ${HEALTH_PROBES_ENABLED:true}
  metrics:
    tags:
      application: ${spring.application.name}
```

### OpenMRS Authentication

OpenMRS supports multiple authentication mechanisms. This service uses Basic Auth by default. OAuth2 is supported via the `openmrs-module-oauth2login` module with an external identity provider (e.g., Keycloak).

| Auth Type | Description | Required Fields |
|-----------|-------------|------------------|
| `basic` | HTTP Basic Auth (default, recommended) | `username`, `password` |
| `bearer` | Static Bearer token | `token` |
| `oauth2` | OAuth2 Client Credentials flow (requires `oauth2login` module + IdP) | `oauth2.token-url`, `oauth2.client-id`, `oauth2.client-secret` |

### Environment Variables

| Variable | Description | Default |
|---|---|---|
| `OPENMRS_NAME` | OpenMRS instance display name | `openmrs` |
| `OPENMRS_BASE_URL` | OpenMRS base URL | `http://localhost:8080/openmrs` |
| `OPENMRS_AUTH_TYPE` | OpenMRS auth type (`basic`, `bearer`, `oauth2`) | `basic` |
| `OPENMRS_AUTH_USERNAME` | OpenMRS auth username | `admin` |
| `OPENMRS_AUTH_PASSWORD` | OpenMRS auth password | `Admin123` |
| `OPENMRS_AUTH_TOKEN` | OpenMRS Bearer token (for `bearer` auth) | — |
| `OPENMRS_AUTH_OAUTH2_TOKEN_URL` | OAuth2 token endpoint URL (for `oauth2` auth) | — |
| `OPENMRS_AUTH_OAUTH2_CLIENT_ID` | OAuth2 client ID | — |
| `OPENMRS_AUTH_OAUTH2_CLIENT_SECRET` | OAuth2 client secret | — |
| `OPENMRS_AUTH_OAUTH2_SCOPE` | OAuth2 scope (optional) | — |
| `OPENHIM_BASE_URL` | OpenHIM base URL | `http://localhost:5001/fhir` |
| `OPENHIM_AUTH_TYPE` | OpenHIM auth type (`none`, `basic`, `jwt`, `custom-token`) | `basic` |
| `OPENHIM_AUTH_USERNAME` | OpenHIM auth username | *(must be set for basic)* |
| `OPENHIM_AUTH_PASSWORD` | OpenHIM auth password | *(must be set for basic)* |
| `OPENHIM_AUTH_TOKEN` | OpenHIM auth token | *(must be set for jwt/custom-token)* |
| `OPENHIM_SSL_TRUST_ALL` | Trust all SSL certificates for OpenHIM | `false` |
| `OPENHIM_RETRY_MAX_ATTEMPTS` | Maximum retry attempts for OpenHIM | `3` |
| `OPENHIM_RETRY_BACKOFF_MS` | Retry backoff in milliseconds | `2000` |
| `POLLING_FHIR_ENABLED` | Enable FHIR polling | `true` |
| `POLLING_FHIR_INTERVAL_SECONDS` | FHIR polling interval | `30` |
| `POLLING_FHIR_PAGE_SIZE` | FHIR polling page size (`_count`) | `50` |
| `POLLING_FHIR_RESOURCE_TYPES` | Comma-separated FHIR resource types to poll | *(14 defaults)* |
| `CHECKPOINT_FILE_PATH` | Checkpoint file path | `./data/checkpoints.json` |

---

## Coding Conventions

### General Patterns

- **Java 21** — use text blocks, records, switch expressions, pattern matching
- Use **constructor injection** exclusively — no `@Autowired` on fields
- Use `@RequiredArgsConstructor` (Lombok) for constructor injection
- **Lombok** — `@Data` on config/mutable classes; avoid manual getters/setters
- **Records** for immutable data and value objects (e.g., `ForwardResult`, `PollCheckpoint`)
- Validate inputs with Bean Validation (`@Valid`, `@NotNull`, `@NotBlank`)

### Logging

- **SLF4J** via `LoggerFactory.getLogger(ClassName.class)` — project convention (not `@Slf4j`)
- Use parameterized logging: `log.info("Message: {}", value)` — never string concatenation

### Error Handling

- Use `try-catch` in poller methods — polling failures should be logged but NOT stop subsequent polls
- ForwardingEngine returns `ForwardResult` record with `status` field: `"forwarded"`, `"failed: <message>"`
- Failed forwards are retried with linear backoff; after final failure, logged as `ERROR` and the resource is skipped

---

## Observability

### Metrics (Micrometer + Prometheus)

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `openmrs.emitter.poll.executed` | Counter | `resourceType` | Poll cycles executed |
| `openmrs.emitter.poll.resources.detected` | Counter | `resourceType` | Resources detected as changed |
| `openmrs.emitter.forward.success` | Counter | `resourceType` | Successful forwards to OpenHIM |
| `openmrs.emitter.forward.failure` | Counter | `resourceType` | Failed forwards (all retries exhausted) |
| `openmrs.emitter.poll.duration` | Timer | `resourceType` | Poll cycle duration |
| `openmrs.emitter.checkpoint.age.seconds` | Gauge | `resourceType` | Seconds since last successful checkpoint |

### Health Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Overall health status |
| `/actuator/health/liveness` | Liveness probe (JVM is running) |
| `/actuator/health/readiness` | Readiness probe (OpenMRS reachable) |
| `/actuator/prometheus` | Micrometer metrics for Prometheus scraping |

---

## Testing

### Test Structure

```
src/test/java/org/openphc/cce/emitter/
├── OpenmrsCceEmitterAdaptorApplicationTests.java     # Context load test
├── config/
│   ├── EmitterPropertiesTest.java                    # Config binding tests
│   ├── FhirConfigTest.java                           # FhirContext bean tests
│   └── RestClientConfigTest.java                     # RestTemplate bean tests
├── service/
│   ├── FhirPollerServiceTest.java                    # FHIR polling tests
│   ├── ForwardingEngineTest.java                     # Forwarding, retry, metrics tests
│   └── CheckpointStoreTest.java                      # Checkpoint persistence tests
└── integration/
    ├── AbstractIntegrationTest.java                  # WireMock base (OpenMRS + OpenHIM)
    ├── FhirPollingIntegrationTest.java               # End-to-end FHIR poll → forward
    └── HealthEndpointIntegrationTest.java            # Health endpoint tests
```

---

## Docker

- Multi-stage Dockerfile: build with JDK 21, run with JRE 21
- Runs as non-root `appuser`
- Port: 8080 (default; override with `SERVER_PORT`)
- Volume mount for checkpoint persistence (`./data/`)

---

## Common Gotchas

1. **`_lastUpdated` inconsistency** — Some OpenMRS resources may not update `meta.lastUpdated` on all field changes. Mitigate with the overlap window (`polling.fhir.overlap-seconds: 5`) and deduplication by resource ID.
2. **FHIR hides deleted resources** — Voided records vanish from FHIR search results. Delete/void detection is a future enhancement.
3. **No FHIR Subscriptions in OpenMRS** — OpenMRS `fhir2` module does NOT implement FHIR Subscriptions. This service must poll, not subscribe.
4. **Encounter/Visit overlap in FHIR** — OpenMRS Visits and clinical Encounters both appear as FHIR `Encounter` resources. Visits are tagged with `meta.tag.code = "visit"`. Downstream consumers can distinguish by this tag.
5. **Checkpoint file permissions** — Ensure the Docker volume mount and `appuser` have write access to the checkpoint file directory.
6. **Bundle size** — Large FHIR Bundles (>100 entries) can timeout. Use `_count=50` and page through.
7. **Polling interval vs processing time** — Use `fixedDelay` (not `fixedRate`) so the next poll starts after the previous one completes.
8. **Missing `meta.lastUpdated`** — In practice, OpenMRS always sets `meta.lastUpdated` (from `dateChanged ?? dateCreated`). Defensively, if a resource lacks it: still forward the resource, exclude it from `maxLastUpdated` calculation, and log a warning.

---

## Future Enhancements

- **Delete/Void Reconciliation** — Periodic reconciliation using REST API with `includeAll=true` to detect voided records invisible via FHIR.
- **ProgramEnrollment Polling** — REST API polling for ProgramEnrollments (`/ws/rest/v1/programenrollment`) if required. The FHIR `EpisodeOfCare` endpoint currently supports read-only by UUID without search/`_lastUpdated` support.
- **Database-Backed Checkpoint Store** — Replace file-based checkpoint persistence with a database for multi-instance deployments and operational resilience.

---

## Out of Scope

- CloudEvents envelope wrapping (happens downstream — OpenHIM mediator)
- Protocol matching or compliance tracking (CCE Compliance Service)
- FHIR resource transformation or enrichment (resources forwarded as-is)
- Kafka integration (HTTP forwarding only)
- FHIR Subscriptions (not supported by OpenMRS)

---

## Upstream Design Documents

- `CCE Solution Design v0.3 Draft.docx` — overall CCE platform architecture, Emitter/Receiver Adaptor model (Section 4.3.7)
- `RHIE Technical Documentation v2.0.0` — Rwanda HIE FHIR R4 API documentation
- `docs/openmrs-rest-vs-fhir-comparison.md` — OpenMRS REST vs FHIR API comparison and selection rationale
