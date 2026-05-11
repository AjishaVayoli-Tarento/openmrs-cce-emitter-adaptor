package org.openphc.cce.emitter.service;

import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.openphc.cce.emitter.config.EmitterProperties;
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
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;

/**
 * Resolves OpenMRS order revision chains so downstream consumers see the
 * terminal status of a discontinued/revised prior order.
 *
 * <p>OpenMRS orders are append-only: a revision/discontinue creates a new row
 * with {@code basedOn} (ServiceRequest) or {@code priorPrescription}
 * (MedicationRequest) pointing at the previous UUID. The previous row is
 * mutated in place (its status flips to revoked/completed) but
 * {@code meta.lastUpdated} is not bumped, so the FHIR poll never re-detects
 * it. This resolver inspects each newly polled order; if it points to a
 * prior, it refetches the prior so the latest status is observed, and
 * (per project convention) suppresses pure discontinue tombstones whose
 * only purpose was to close the prior row.
 */
@Service
public class OrderRevisionResolver {

    private static final Logger log = LoggerFactory.getLogger(OrderRevisionResolver.class);

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;

    public OrderRevisionResolver(EmitterProperties properties,
                                 RestTemplate restTemplate,
                                 AuthService authService) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.authService = authService;
    }

    public record Decision(boolean skipNew,
                           IBaseResource prior,
                           String priorId,
                           String priorType) {
        public static Decision none() { return new Decision(false, null, null, null); }
    }

    /**
     * Inspect the polled resource and decide whether to also forward its
     * prior order and whether the polled resource itself should be skipped.
     *
     * @param resource     the freshly polled resource
     * @param seenInCycle  ids already processed in the current poll cycle
     *                     ({@code Type/Id}); the resolver consults this set
     *                     to avoid double-forwarding a prior already seen
     *                     in the same bundle
     * @param parser       JSON parser to decode the prior fetch response
     */
    public Decision evaluate(IBaseResource resource, Set<String> seenInCycle, IParser parser) {
        if (!properties.getPolling().getFhir().isFollowPriorOrder()) {
            return Decision.none();
        }
        if (resource instanceof ServiceRequest sr) {
            // OpenMRS fhir2 surfaces the revision link on `replaces`, not `basedOn`.
            // `basedOn` is checked as a fallback in case other order types use it.
            String priorId = firstReferenceId(sr.getReplaces(), "ServiceRequest/");
            if (priorId == null) {
                priorId = firstReferenceId(sr.getBasedOn(), "ServiceRequest/");
            }
            // fhir2 emits `status=unknown` for OpenMRS DISCONTINUE actions (no clinical
            // content of its own), and `status=revoked` is the FHIR-correct value for
            // a discontinue. Treat either as a tombstone when a prior link is present.
            ServiceRequest.ServiceRequestStatus s = sr.getStatus();
            boolean discontinue = priorId != null
                    && (s == ServiceRequest.ServiceRequestStatus.REVOKED
                        || s == ServiceRequest.ServiceRequestStatus.UNKNOWN);
            return buildDecision("ServiceRequest", priorId, discontinue, seenInCycle, parser);
        }
        if (resource instanceof MedicationRequest mr) {
            String priorId = mr.hasPriorPrescription()
                    ? referenceId(mr.getPriorPrescription(), "MedicationRequest/") : null;
            MedicationRequest.MedicationRequestStatus s = mr.getStatus();
            boolean discontinue = priorId != null
                    && (s == MedicationRequest.MedicationRequestStatus.CANCELLED
                        || s == MedicationRequest.MedicationRequestStatus.STOPPED
                        || s == MedicationRequest.MedicationRequestStatus.UNKNOWN);
            return buildDecision("MedicationRequest", priorId, discontinue, seenInCycle, parser);
        }
        return Decision.none();
    }

    private Decision buildDecision(String type, String priorId, boolean discontinue,
                                   Set<String> seenInCycle, IParser parser) {
        if (priorId == null) {
            return Decision.none();
        }
        String key = type + "/" + priorId;
        if (seenInCycle.contains(key)) {
            // Prior already (or will be) emitted by the main loop this cycle.
            return new Decision(discontinue, null, null, null);
        }
        IBaseResource prior = fetchPrior(type, priorId, parser);
        if (prior == null) {
            return new Decision(discontinue, null, null, null);
        }
        return new Decision(discontinue, prior, priorId, type);
    }

    private IBaseResource fetchPrior(String type, String id, IParser parser) {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + ensureLeadingSlash(openmrs.getFhirPath())
                + "/" + type + "/" + id;
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String auth = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (auth != null) {
            headers.set(HttpHeaders.AUTHORIZATION, auth);
        }
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            if (resp.getBody() == null || resp.getBody().isBlank()) {
                return null;
            }
            return parser.parseResource(resp.getBody());
        } catch (HttpClientErrorException.NotFound nf) {
            log.warn("Prior {}/{} not found while resolving order revision", type, id);
            return null;
        } catch (Exception e) {
            log.warn("Failed to fetch prior {}/{} while resolving order revision: {}",
                    type, id, e.getMessage());
            return null;
        }
    }

    private static String firstReferenceId(List<Reference> refs, String prefix) {
        if (refs == null) return null;
        for (Reference r : refs) {
            String v = referenceId(r, prefix);
            if (v != null) return v;
        }
        return null;
    }

    private static String referenceId(Reference r, String prefix) {
        if (r == null || !r.hasReference()) return null;
        String ref = r.getReference();
        if (ref == null) return null;
        if (ref.startsWith(prefix)) {
            return ref.substring(prefix.length());
        }
        // Tolerate absolute references (.../{Type}/{id}).
        String marker = "/" + prefix;
        int idx = ref.lastIndexOf(marker);
        if (idx >= 0) {
            return ref.substring(idx + marker.length());
        }
        return null;
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
