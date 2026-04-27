# Operations Runbook — OpenMRS CCE Emitter Adaptor

## Service Info

| Property | Value |
|----------|-------|
| Service | `openmrs-cce-emitter-adaptor` |
| Port | `8080` (default) |
| Health | `GET /actuator/health` |
| Metrics | `GET /actuator/prometheus` |

---

## Common Operations

```bash
# Start / Stop / Restart
docker compose up -d openmrs-cce-emitter-adaptor
docker compose stop openmrs-cce-emitter-adaptor
docker compose restart openmrs-cce-emitter-adaptor

# View logs
docker compose logs -f openmrs-cce-emitter-adaptor

# Check health
curl -s http://localhost:8080/actuator/health | jq

# Inspect checkpoints
docker exec openmrs-cce-emitter-adaptor cat /app/data/checkpoints.json | jq
```

### Reset Checkpoints (Re-poll from scratch)

```bash
docker compose stop openmrs-cce-emitter-adaptor
docker exec openmrs-cce-emitter-adaptor rm /app/data/checkpoints.json
docker compose start openmrs-cce-emitter-adaptor
```

> **Warning:** Deleting the checkpoint file triggers the sliding window seed — the service will re-poll only the last ~35 seconds of data (`intervalSeconds + overlapSeconds`, default 30s + 5s), not all historical resources. May produce duplicates for resources updated in that window (handled by CCE deduplication).

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| No `poll.executed` metric increase | Polling disabled | Check `POLLING_FHIR_ENABLED` |
| `401 Unauthorized` from OpenMRS | Bad credentials or expired token | Check `OPENMRS_AUTH_USERNAME` / `OPENMRS_AUTH_PASSWORD`; for OAuth2 verify `OPENMRS_AUTH_OAUTH2_TOKEN_URL` and client credentials |
| `0 resources detected` | Checkpoint ahead of data | Reset checkpoints |
| `forward.failure` rising | OpenHIM unreachable | Check `OPENHIM_BASE_URL`; verify OpenHIM is running |
| `SSL handshake failed` | Self-signed cert | Set `OPENHIM_SSL_TRUST_ALL=true` (non-production) |
| Checkpoint file empty after restart | Volume not mounted | Mount `/app/data` as Docker volume |

---

## Key Metrics to Monitor

| Metric | Healthy | Investigate |
|--------|---------|-------------|
| `openmrs_emitter_poll_executed_total` | Increasing | Flat (polling stopped) |
| `openmrs_emitter_forward_failure_total` | 0 or very low | Any increase |
| `openmrs_emitter_poll_duration_seconds` | < 10s | > 30s |
| `openmrs_emitter_checkpoint_age_seconds` | < 120s | > 600s |

---

## Recommended Alerts

```yaml
groups:
  - name: openmrs-emitter
    rules:
      - alert: OpenmrsEmitterPollStalled
        expr: increase(openmrs_emitter_poll_executed_total[5m]) == 0
        for: 5m
        labels: { severity: warning }

      - alert: OpenmrsEmitterForwardFailures
        expr: increase(openmrs_emitter_forward_failure_total[5m]) > 10
        for: 2m
        labels: { severity: critical }

      - alert: OpenmrsEmitterDown
        expr: up{job="openmrs-cce-emitter-adaptor"} == 0
        for: 1m
        labels: { severity: critical }
```

---

## Maintenance

- **Single instance** only — no active-active scaling (would cause duplicates)
- **Checkpoint file** is the only persistent state — back up before major changes
- **Upgrading:** build new image → stop → start with same volume mounts
