# Configuration Guide — OpenMRS CCE Emitter Adaptor

## Spring Profiles

| Profile | YAML file | Purpose |
|---------|-----------|---------|
| `default` | `application.yml` | Base config with sensible defaults |
| `local` | `application-local.yml` | Local dev — DEBUG logging, SSL trust-all |
| `staging` | `application-staging.yml` | Pre-production — INFO logging |
| `production` | `application-production.yml` | Production — WARN root, health details hidden |

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

---

## Configuration Reference

### Server

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |
| `server.shutdown` | — | `graceful` | Graceful shutdown |
| `spring.lifecycle.timeout-per-shutdown-phase` | `SHUTDOWN_TIMEOUT` | `30s` | Shutdown wait |

### OpenMRS Connection

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.openmrs.name` | `OPENMRS_NAME` | `openmrs` | Display name |
| `emitter.openmrs.base-url` | `OPENMRS_BASE_URL` | `http://localhost:8080/openmrs` | OpenMRS base URL |
| `emitter.openmrs.fhir-path` | `OPENMRS_FHIR_PATH` | `/ws/fhir2/R4` | FHIR API path |

### OpenMRS Authentication

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.openmrs.auth.type` | `OPENMRS_AUTH_TYPE` | `basic` | `basic`, `bearer`, or `oauth2` |
| `emitter.openmrs.auth.username` | `OPENMRS_AUTH_USERNAME` | `admin` | Username (for basic) |
| `emitter.openmrs.auth.password` | `OPENMRS_AUTH_PASSWORD` | `Admin123` | Password (for basic) |
| `emitter.openmrs.auth.token` | `OPENMRS_AUTH_TOKEN` | — | Bearer token |
| `emitter.openmrs.auth.oauth2.token-url` | `OPENMRS_AUTH_OAUTH2_TOKEN_URL` | — | OAuth2 token endpoint URL |
| `emitter.openmrs.auth.oauth2.client-id` | `OPENMRS_AUTH_OAUTH2_CLIENT_ID` | — | OAuth2 client ID |
| `emitter.openmrs.auth.oauth2.client-secret` | `OPENMRS_AUTH_OAUTH2_CLIENT_SECRET` | — | OAuth2 client secret |
| `emitter.openmrs.auth.oauth2.scope` | `OPENMRS_AUTH_OAUTH2_SCOPE` | — | OAuth2 scope (optional) |

### OpenHIM Connection & Auth

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.openhim.base-url` | `OPENHIM_BASE_URL` | `http://localhost:5001/fhir` | OpenHIM base URL |
| `emitter.openhim.auth.type` | `OPENHIM_AUTH_TYPE` | `basic` | `none`, `basic`, `jwt`, `custom-token` |
| `emitter.openhim.auth.username` | `OPENHIM_AUTH_USERNAME` | — | Basic auth username |
| `emitter.openhim.auth.password` | `OPENHIM_AUTH_PASSWORD` | — | Basic auth password |
| `emitter.openhim.auth.token` | `OPENHIM_AUTH_TOKEN` | — | JWT or custom token |
| `emitter.openhim.ssl-trust-all` | `OPENHIM_SSL_TRUST_ALL` | `false` | Trust all SSL certs |
| `emitter.openhim.append-resource-type` | `OPENHIM_APPEND_RESOURCE_TYPE` | `true` | Append `/{ResourceType}` to URL |
| `emitter.openhim.retry.max-attempts` | `OPENHIM_RETRY_MAX_ATTEMPTS` | `3` | Max retries |
| `emitter.openhim.retry.backoff-ms` | `OPENHIM_RETRY_BACKOFF_MS` | `2000` | Backoff per attempt (ms) |

### Polling

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.polling.fhir.enabled` | `POLLING_FHIR_ENABLED` | `true` | Enable FHIR polling |
| `emitter.polling.fhir.interval-seconds` | `POLLING_FHIR_INTERVAL_SECONDS` | `30` | FHIR poll interval |
| `emitter.polling.fhir.page-size` | `POLLING_FHIR_PAGE_SIZE` | `50` | FHIR `_count` |
| `emitter.polling.fhir.overlap-seconds` | `POLLING_FHIR_OVERLAP_SECONDS` | `5` | Overlap window |
| `emitter.polling.fhir.resource-types` | `POLLING_FHIR_RESOURCE_TYPES` | *(14 types)* | FHIR resource types |

### Checkpoint

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `emitter.checkpoint.store-type` | `CHECKPOINT_STORE_TYPE` | `file` | `file` (database planned as future enhancement) |
| `emitter.checkpoint.file-path` | `CHECKPOINT_FILE_PATH` | `./data/checkpoints.json` | File path (when `store-type=file`) |

---

## Example: Local Development

```bash
OPENMRS_BASE_URL=http://localhost:8080/openmrs
OPENMRS_AUTH_USERNAME=admin
OPENMRS_AUTH_PASSWORD=Admin123
OPENHIM_BASE_URL=http://localhost:5001/fhir
OPENHIM_AUTH_TYPE=none
LOG_LEVEL_APP=DEBUG
```

## Example: Production

```bash
OPENMRS_BASE_URL=http://openmrs:8080/openmrs
OPENMRS_AUTH_TYPE=basic
OPENMRS_AUTH_USERNAME=cce-emitter
OPENMRS_AUTH_PASSWORD=${SECRET_OPENMRS_PASSWORD}
OPENHIM_BASE_URL=https://openhim-api.cce.mdtlabs.org/fhir
OPENHIM_AUTH_TYPE=basic
OPENHIM_AUTH_USERNAME=openmrs-emitter-client
OPENHIM_AUTH_PASSWORD=${SECRET_OPENHIM_PASSWORD}
OPENHIM_SSL_TRUST_ALL=false
OPENHIM_RETRY_MAX_ATTEMPTS=5
CHECKPOINT_FILE_PATH=/app/data/checkpoints.json
LOG_LEVEL_ROOT=WARN
```

## Example: Production with OAuth2

```bash
OPENMRS_BASE_URL=http://openmrs:8080/openmrs
OPENMRS_AUTH_TYPE=oauth2
OPENMRS_AUTH_OAUTH2_TOKEN_URL=https://keycloak.example.org/realms/openmrs/protocol/openid-connect/token
OPENMRS_AUTH_OAUTH2_CLIENT_ID=cce-emitter-service
OPENMRS_AUTH_OAUTH2_CLIENT_SECRET=${SECRET_OAUTH2_CLIENT_SECRET}
OPENMRS_AUTH_OAUTH2_SCOPE=openid
OPENHIM_BASE_URL=https://openhim-api.cce.mdtlabs.org/fhir
OPENHIM_AUTH_TYPE=basic
OPENHIM_AUTH_USERNAME=openmrs-emitter-client
OPENHIM_AUTH_PASSWORD=${SECRET_OPENHIM_PASSWORD}
OPENHIM_SSL_TRUST_ALL=false
OPENHIM_RETRY_MAX_ATTEMPTS=5
CHECKPOINT_FILE_PATH=/app/data/checkpoints.json
LOG_LEVEL_ROOT=WARN
```

---

## Checkpoint File Format

```json
{
  "checkpoints": {
    "Patient": {
      "lastUpdated": "2026-04-13T10:30:00.000+00:00",
      "lastPollTime": "2026-04-13T10:30:15.123+00:00"
    },
    "Encounter": {
      "lastUpdated": "2026-04-13T10:29:45.000+00:00",
      "lastPollTime": "2026-04-13T10:30:15.123+00:00"
    }
  }
}
```

All checkpoint keys use FHIR PascalCase resource names. In Docker, mount the checkpoint path as a volume.

---

## Checkpoint Lifecycle (Hybrid Approach)

The service uses a **hybrid checkpoint + sliding window seed** strategy to resolve the query time for each poll cycle.

| Scenario | Query Time | Behavior |
|----------|-----------|----------|
| **Checkpoint exists** (normal operation) | `checkpoint - overlapSeconds` | Polls resources modified since last checkpoint, minus overlap window for safety |
| **No checkpoint** (first boot or lost) | `now() - (intervalSeconds + overlapSeconds)` | Sliding window seed — polls only the recent window (default ~35s), avoids full-history re-poll |

- After each successful poll, the **maximum `_lastUpdated`** from the results is saved as the new checkpoint
- The `overlap-seconds` (default `5`) compensates for `_lastUpdated` inconsistencies in OpenMRS
- If the checkpoint file is deleted or corrupted, the service gracefully falls back to the sliding window seed — no manual intervention required
- Deduplication by resource ID within each poll cycle handles any overlap-induced duplicates
