package org.openphc.cce.emitter.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.NationalIdConfig;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import ca.uhn.fhir.context.FhirContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves OpenMRS Patient internal IDs to their {@code national-id} identifier value.
 * Caches mappings in memory with TTL and a max-size cap.
 */
@Service
@RequiredArgsConstructor
public class NationalIdResolver {

    private static final Logger log = LoggerFactory.getLogger(NationalIdResolver.class);
    private static final String METRIC_LOOKUP = "openmrs.emitter.nationalid.lookup";

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final FhirContext fhirContext;
    private final AuthService authService;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * @return national-id value for the given OpenMRS Patient ID, or empty if not found.
     */
    public Optional<String> resolve(String openmrsPatientId) {
        if (openmrsPatientId == null || openmrsPatientId.isBlank()) {
            return Optional.empty();
        }
        evictIfOverCapacity();

        CacheEntry entry = cache.get(openmrsPatientId);
        Instant now = Instant.now();
        if (entry != null && entry.expiresAt.isAfter(now)) {
            recordOutcome(entry.value == null ? "cache-miss" : "cache-hit");
            return Optional.ofNullable(entry.value);
        }

        try {
            String value = lookup(openmrsPatientId);
            long ttl = Math.max(1L, properties.getPatient().getNationalId().getCacheTtlSeconds());
            cache.put(openmrsPatientId, new CacheEntry(value, now.plus(Duration.ofSeconds(ttl))));
            recordOutcome(value == null ? "miss" : "hit");
            registerCacheGauge();
            return Optional.ofNullable(value);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Patient {} not found in OpenMRS — no national-id available", openmrsPatientId);
            recordOutcome("miss");
            return Optional.empty();
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("Failed to resolve national-id for Patient {}: {}", openmrsPatientId, e.getMessage());
            recordOutcome("error");
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error resolving national-id for Patient {}: {}", openmrsPatientId, e.getMessage());
            recordOutcome("error");
            return Optional.empty();
        }
    }

    /** Drop a single cache entry (e.g., after detecting a stale value). */
    public void invalidate(String openmrsPatientId) {
        if (openmrsPatientId != null) {
            cache.remove(openmrsPatientId);
        }
    }

    private String lookup(String openmrsPatientId) {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + ensureLeadingSlash(openmrs.getFhirPath())
                + "/Patient/" + openmrsPatientId;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        Patient patient = (Patient) fhirContext.newJsonParser().parseResource(body);
        return extractNationalId(patient);
    }

    /** Extract the national-id value from a parsed Patient resource. */
    public String extractNationalId(Patient patient) {
        if (patient == null || patient.getIdentifier() == null) {
            return null;
        }
        NationalIdConfig cfg = properties.getPatient().getNationalId();
        String code = cfg.getCode();
        String system = cfg.getSystem();
        for (Identifier id : patient.getIdentifier()) {
            if (id == null || id.getValue() == null || id.getValue().isBlank()) continue;
            if (matchesType(id, code) || matchesText(id, code) || matchesSystem(id, system)) {
                return id.getValue();
            }
        }
        return null;
    }

    private boolean matchesType(Identifier id, String code) {
        if (code == null || code.isBlank() || id.getType() == null || !id.getType().hasCoding()) {
            return false;
        }
        return id.getType().getCoding().stream()
                .anyMatch(c -> c != null && code.equalsIgnoreCase(c.getCode()));
    }

    private boolean matchesText(Identifier id, String code) {
        if (code == null || code.isBlank() || id.getType() == null) {
            return false;
        }
        return code.equalsIgnoreCase(id.getType().getText());
    }

    private boolean matchesSystem(Identifier id, String system) {
        return system != null && !system.isBlank() && system.equals(id.getSystem());
    }

    private void evictIfOverCapacity() {
        int max = properties.getPatient().getNationalId().getCacheMaxSize();
        if (max > 0 && cache.size() >= max) {
            // simple cap: clear when full — avoids LRU dependency for now
            cache.clear();
        }
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter(METRIC_LOOKUP, Tags.of("outcome", outcome)).increment();
    }

    private void registerCacheGauge() {
        // gauge is registered idempotently by Micrometer on the same id+tags
        meterRegistry.gauge("openmrs.emitter.nationalid.cache.size", cache, ConcurrentHashMap::size);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String ensureLeadingSlash(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.startsWith("/") ? url : "/" + url;
    }

    private record CacheEntry(String value, Instant expiresAt) { }
}
