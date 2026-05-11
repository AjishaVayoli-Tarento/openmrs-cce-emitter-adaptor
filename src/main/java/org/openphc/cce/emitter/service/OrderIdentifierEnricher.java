package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferralResponseConfig;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-injects OpenMRS order metadata that the fhir2 module strips from ServiceRequest /
 * MedicationRequest payloads — namely {@code orderNumber} (e.g. {@code ORD-395}) and
 * {@code accessionNumber} — by querying the OpenMRS REST API
 * ({@code /ws/rest/v1/order/{uuid}}) and adding them as {@code identifier[]} entries on the
 * outgoing FHIR resource.
 *
 * <p>Mapping used follows the HL7 v2-0203 identifier-type code:
 * <ul>
 *   <li>{@code accessionNumber} → identifier with {@code type.coding.code = "ACSN"} (Accession)</li>
 * </ul>
 *
 * <p>Lookups are cached in-memory with TTL because OpenMRS order numbers and accession numbers
 * are immutable for a given order UUID.
 */
@Service
@RequiredArgsConstructor
public class OrderIdentifierEnricher {

    private static final Logger log = LoggerFactory.getLogger(OrderIdentifierEnricher.class);
    private static final String METRIC_LOOKUP = "openmrs.emitter.order.identifier.lookup";

    private static final String IDENTIFIER_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
    private static final String OPENMRS_ACCESSION_NUMBER_SYSTEM = "urn:openmrs:accession-number";
    private static final long CACHE_TTL_SECONDS = 3600L;
    private static final int CACHE_MAX_SIZE = 10_000;

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Adds OpenMRS order identifiers to the resource if it is a ServiceRequest or
     * MedicationRequest and the OpenMRS REST lookup succeeds. Failures are swallowed —
     * the resource is forwarded unchanged in that case.
     */
    public void enrich(IBaseResource resource) {
        if (resource == null) return;
        String type = resource.fhirType();
        if (!"ServiceRequest".equals(type) && !"MedicationRequest".equals(type)) return;

        String uuid = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : null;
        if (uuid == null || uuid.isBlank()) return;

        OrderRefs refs = lookup(uuid);
        if (refs == null) return;

        if (resource instanceof ServiceRequest sr) {
            mergeIdentifier(sr.getIdentifier(), refs.accessionNumber(), "ACSN", "Accession ID",
                    OPENMRS_ACCESSION_NUMBER_SYSTEM, sr::addIdentifier);
            applyReferralResponseClassification(sr, refs);
        } else if (resource instanceof MedicationRequest mr) {
            mergeIdentifier(mr.getIdentifier(), refs.accessionNumber(), "ACSN", "Accession ID",
                    OPENMRS_ACCESSION_NUMBER_SYSTEM, mr::addIdentifier);
        }
    }

    /**
     * If the order is a referral and has reached a terminal status, classify the resource as a
     * referral response so that downstream PlanDefinition triggers can match on
     * {@code category} + {@code status} and resolve the placer order via {@code basedOn}.
     */
    private void applyReferralResponseClassification(ServiceRequest sr, OrderRefs refs) {
        ReferralResponseConfig cfg = properties.getReferral().getResponse();
        if (cfg == null || !cfg.isEnabled()) return;
        if (refs.orderTypeName() == null
                || !refs.orderTypeName().equalsIgnoreCase(cfg.getOrderTypeName())) {
            return;
        }
        ServiceRequest.ServiceRequestStatus status = sr.getStatus();
        if (status != ServiceRequest.ServiceRequestStatus.COMPLETED
                && status != ServiceRequest.ServiceRequestStatus.REVOKED) {
            return;
        }

        // 1) category coding — PlanDefinition action.trigger codeFilter target
        addCategoryIfAbsent(sr, cfg.getCategorySystem(), cfg.getCategoryCode(), cfg.getCategoryDisplay());

        // 2) basedOn — logical reference to the placer's ServiceRequest, sourced from the
        //    OpenMRS accessionNumber (which the placer system stamped with its own SR id).
        if (refs.accessionNumber() != null && !refs.accessionNumber().isBlank()) {
            addBasedOnIdentifierIfAbsent(sr, cfg.getBasedOnIdentifierSystem(), refs.accessionNumber());
        }

        // 3) intent flip — placer sent 'order'; fulfiller responds with 'filler-order'
        if (cfg.isFlipIntent() && sr.getIntent() == ServiceRequest.ServiceRequestIntent.ORDER) {
            sr.setIntent(ServiceRequest.ServiceRequestIntent.FILLERORDER);
        }
    }

    private void addCategoryIfAbsent(ServiceRequest sr, String system, String code, String display) {
        if (system == null || code == null) return;
        for (CodeableConcept cc : sr.getCategory()) {
            if (cc == null) continue;
            for (Coding c : cc.getCoding()) {
                if (system.equals(c.getSystem()) && code.equals(c.getCode())) return;
            }
        }
        sr.addCategory(new CodeableConcept().addCoding(
                new Coding().setSystem(system).setCode(code).setDisplay(display)));
    }

    private void addBasedOnIdentifierIfAbsent(ServiceRequest sr, String system, String value) {
        if (system == null) return;
        for (Reference ref : sr.getBasedOn()) {
            Identifier id = ref != null ? ref.getIdentifier() : null;
            if (id != null && system.equals(id.getSystem()) && value.equals(id.getValue())) return;
        }
        sr.addBasedOn(new Reference()
                .setType("ServiceRequest")
                .setIdentifier(new Identifier().setSystem(system).setValue(value)));
    }

    private void mergeIdentifier(List<Identifier> existing, String value, String typeCode,
                                 String typeDisplay, String system,
                                 java.util.function.Supplier<Identifier> adder) {
        if (value == null || value.isBlank()) return;
        // Skip if an identifier with the same system+value already exists (idempotent).
        for (Identifier id : existing) {
            if (id != null && system.equals(id.getSystem()) && value.equals(id.getValue())) {
                return;
            }
        }
        Identifier id = adder.get();
        id.setSystem(system);
        id.setValue(value);
        id.setType(new CodeableConcept().addCoding(new Coding()
                .setSystem(IDENTIFIER_TYPE_SYSTEM)
                .setCode(typeCode)
                .setDisplay(typeDisplay)));
    }

    private OrderRefs lookup(String orderUuid) {
        evictIfOverCapacity();
        Instant now = Instant.now();
        CacheEntry cached = cache.get(orderUuid);
        if (cached != null && cached.expiresAt.isAfter(now)) {
            recordOutcome("cache-hit");
            return cached.refs;
        }
        try {
            OrderRefs refs = fetchFromOpenmrs(orderUuid);
            cache.put(orderUuid, new CacheEntry(refs, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
            recordOutcome(refs == null ? "miss" : "hit");
            return refs;
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Order {} not found via OpenMRS REST — skipping identifier enrichment", orderUuid);
            recordOutcome("miss");
            return null;
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("Failed to fetch order {} for identifier enrichment: {}", orderUuid, e.getMessage());
            recordOutcome("error");
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error enriching order {}: {}", orderUuid, e.getMessage());
            recordOutcome("error");
            return null;
        }
    }

    private OrderRefs fetchFromOpenmrs(String orderUuid) throws Exception {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + "/ws/rest/v1/order/" + orderUuid
                + "?v=custom:(uuid,accessionNumber,orderType:(uuid,display))";

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) return null;
        JsonNode node = objectMapper.readTree(body);
        String accessionNumber = textOrNull(node, "accessionNumber");
        JsonNode orderTypeNode = node.get("orderType");
        String orderTypeName = orderTypeNode != null ? textOrNull(orderTypeNode, "display") : null;
        String orderTypeUuid = orderTypeNode != null ? textOrNull(orderTypeNode, "uuid") : null;
        if (accessionNumber == null && orderTypeName == null) return null;
        return new OrderRefs(accessionNumber, orderTypeName, orderTypeUuid);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private void evictIfOverCapacity() {
        if (cache.size() >= CACHE_MAX_SIZE) {
            cache.clear();
        }
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter(METRIC_LOOKUP, Tags.of("outcome", outcome)).increment();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record OrderRefs(String accessionNumber, String orderTypeName, String orderTypeUuid) { }

    private record CacheEntry(OrderRefs refs, Instant expiresAt) { }
}
