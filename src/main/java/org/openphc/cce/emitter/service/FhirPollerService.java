package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.FhirPollingConfig;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FhirPollerService {

    private static final Logger log = LoggerFactory.getLogger(FhirPollerService.class);

    private final EmitterProperties properties;
    private final FhirContext fhirContext;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final CheckpointStore checkpointStore;
    private final ForwardingEngine forwardingEngine;
    private final PatientReferenceRewriter patientReferenceRewriter;
    private final OrderRevisionResolver orderRevisionResolver;
    private final OrderIdentifierEnricher orderIdentifierEnricher;
    private final MeterRegistry meterRegistry;

    public FhirPollerService(EmitterProperties properties,
                             FhirContext fhirContext,
                             RestTemplate restTemplate,
                             AuthService authService,
                             CheckpointStore checkpointStore,
                             ForwardingEngine forwardingEngine,
                             PatientReferenceRewriter patientReferenceRewriter,
                             OrderRevisionResolver orderRevisionResolver,
                             OrderIdentifierEnricher orderIdentifierEnricher,
                             MeterRegistry meterRegistry) {
        this.properties = properties;
        this.fhirContext = fhirContext;
        this.restTemplate = restTemplate;
        this.authService = authService;
        this.checkpointStore = checkpointStore;
        this.forwardingEngine = forwardingEngine;
        this.patientReferenceRewriter = patientReferenceRewriter;
        this.orderRevisionResolver = orderRevisionResolver;
        this.orderIdentifierEnricher = orderIdentifierEnricher;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${emitter.polling.fhir.interval-seconds:30}000")
    @ConditionalOnProperty(prefix = "emitter.polling.fhir", name = "enabled", havingValue = "true", matchIfMissing = true)
    public void poll() {
        FhirPollingConfig cfg = properties.getPolling().getFhir();
        if (!cfg.isEnabled()) return;
        List<String> types = cfg.getResourceTypes();
        if (types == null || types.isEmpty()) {
            log.warn("No FHIR resource types configured for polling");
            return;
        }
        for (String resourceType : types) {
            try {
                pollResourceType(resourceType.trim());
            } catch (Exception e) {
                log.error("Poll cycle for {} failed: {}", resourceType, e.getMessage(), e);
            }
        }
    }

    void pollResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) return;
        FhirPollingConfig cfg = properties.getPolling().getFhir();
        OpenmrsConfig openmrs = properties.getOpenmrs();
        Timer.Sample sample = Timer.start(meterRegistry);
        MDC.put("resourceType", resourceType);
        counter("openmrs.emitter.poll.executed", "resourceType", resourceType).increment();

        try {
            String queryTime = checkpointStore.resolveQueryTime(resourceType,
                    cfg.getIntervalSeconds(), cfg.getOverlapSeconds());
            String url = buildFhirUrl(openmrs, resourceType, queryTime, cfg.getPageSize());
            HttpHeaders headers = buildOpenmrsHeaders(openmrs);

            Set<String> seen = new HashSet<>();
            String maxLastUpdated = null;
            int detected = 0;
            String nextUrl = url;
            IParser parser = fhirContext.newJsonParser();

            while (nextUrl != null) {
                ResponseEntity<String> response = restTemplate.exchange(nextUrl, HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);
                if (response.getBody() == null || response.getBody().isBlank()) break;
                Bundle bundle = (Bundle) parser.parseResource(response.getBody());
                if (bundle.getEntry() != null) {
                    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                        IBaseResource resource = entry.getResource();
                        if (resource == null) continue;
                        String id = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : null;
                        String typ = resource.fhirType();
                        if (id == null) continue;
                        String key = typ + "/" + id;
                        if (!seen.add(key)) continue;

                        String lastUpdated = entry.hasResource() && resource.getMeta() != null
                                ? safeInstant(resource.getMeta().getLastUpdated())
                                : null;
                        if (lastUpdated != null && (maxLastUpdated == null || lastUpdated.compareTo(maxLastUpdated) > 0)) {
                            maxLastUpdated = lastUpdated;
                        }

                        OrderRevisionResolver.Decision decision =
                                orderRevisionResolver.evaluate(resource, seen, parser);
                        if (decision.prior() != null) {
                            String priorKey = decision.priorType() + "/" + decision.priorId();
                            if (seen.add(priorKey)) {
                                if (forwardOne(parser, decision.prior(), decision.priorType(), decision.priorId())) {
                                    detected++;
                                    counter("openmrs.emitter.poll.resources.detected",
                                            "resourceType", decision.priorType()).increment();
                                }
                            }
                        }
                        if (decision.skipNew()) {
                            log.info("Skipping {}/{} — discontinue revision (status={})",
                                    typ, id, fhirStatusOf(resource));
                            continue;
                        }

                        if (forwardOne(parser, resource, typ, id)) {
                            detected++;
                            counter("openmrs.emitter.poll.resources.detected", "resourceType", resourceType).increment();
                        }
                    }
                }
                nextUrl = extractNextLink(bundle);
            }

            if (maxLastUpdated != null) {
                checkpointStore.saveCheckpoint(resourceType, maxLastUpdated);
            }
            log.info("Poll {} -> detected {}", resourceType, detected);
        } finally {
            MDC.remove("resourceType");
            MDC.remove("resourceId");
            sample.stop(meterRegistry.timer("openmrs.emitter.poll.duration",
                    Tags.of("resourceType", resourceType)));
        }
    }

    private boolean forwardOne(IParser parser, IBaseResource resource, String resourceType, String resourceId) {
        MDC.put("resourceId", resourceId);
        try {
            PatientReferenceRewriter.Result rr = patientReferenceRewriter.rewrite(resource);
            switch (rr.outcome()) {
                case SKIP_MISSING_NATIONAL_ID -> {
                    forwardingEngine.recordSkipped(resourceType, "missing-national-id");
                    return false;
                }
                case FAIL_MISSING_NATIONAL_ID -> {
                    forwardingEngine.recordSkipped(resourceType, "missing-national-id-fail");
                    log.error("Failing forward of {}/{} — missing national-id (Patient/{})",
                            resourceType, resourceId, rr.missingPatientId());
                    return false;
                }
                default -> { /* REWRITTEN or UNCHANGED — proceed */ }
            }
            IBaseResource enriched = rr.resource() != null ? rr.resource() : resource;
            orderIdentifierEnricher.enrich(enriched);
            String json = parser.encodeResourceToString(enriched);
            ForwardResult result = forwardingEngine.forward(json, resourceType, resourceId);
            return result.isSuccess();
        } finally {
            MDC.remove("resourceId");
        }
    }

    private String buildFhirUrl(OpenmrsConfig openmrs, String resourceType, String queryTime, int pageSize) {
        String base = stripTrailingSlash(openmrs.getBaseUrl()) + ensureLeadingSlash(openmrs.getFhirPath());
        return UriComponentsBuilder.fromHttpUrl(base + "/" + resourceType)
                .queryParam("_lastUpdated", "gt" + queryTime)
                .queryParam("_count", pageSize)
                .queryParam("_sort", "-_lastUpdated")
                .build(false)
                .encode(StandardCharsets.UTF_8)
                .toUriString();
    }

    private HttpHeaders buildOpenmrsHeaders(OpenmrsConfig openmrs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return headers;
    }

    private String extractNextLink(Bundle bundle) {
        if (bundle.getLink() == null) return null;
        for (Bundle.BundleLinkComponent link : bundle.getLink()) {
            if ("next".equalsIgnoreCase(link.getRelation())) {
                return link.getUrl();
            }
        }
        return null;
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return meterRegistry.counter(name, Tags.of(tagKey, tagValue));
    }

    private static String safeInstant(java.util.Date d) {
        return d == null ? null : Instant.ofEpochMilli(d.getTime()).toString();
    }

    private static String fhirStatusOf(IBaseResource r) {
        if (r instanceof org.hl7.fhir.r4.model.ServiceRequest sr && sr.hasStatus()) {
            return sr.getStatus().toCode();
        }
        if (r instanceof org.hl7.fhir.r4.model.MedicationRequest mr && mr.hasStatus()) {
            return mr.getStatus().toCode();
        }
        return "unknown";
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String ensureLeadingSlash(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.startsWith("/") ? url : "/" + url;
    }
}
