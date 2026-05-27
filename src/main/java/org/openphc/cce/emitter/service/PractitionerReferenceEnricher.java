package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeChildResourceDefinition;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.util.FhirTerser;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generically populates empty Practitioner/PractitionerRole reference fields on
 * any outgoing FHIR resource, using HAPI FHIR's runtime resource definitions to
 * discover which fields target Practitioner — no hardcoded resource types needed.
 *
 * <p>Uses {@link RuntimeChildResourceDefinition#getResourceTypes()} to determine
 * which fields on a resource accept Practitioner references. If a field is empty
 * and the linked Encounter has a participant, it is populated automatically.
 *
 * <p>Special case: For {@code Encounter} resources, the {@code participant}
 * backbone element is handled separately since it is not a flat Reference field.
 */
@Service
@RequiredArgsConstructor
public class PractitionerReferenceEnricher {

    private static final Logger log = LoggerFactory.getLogger(PractitionerReferenceEnricher.class);
    private static final long CACHE_TTL_SECONDS = 3600L;
    private static final int CACHE_MAX_SIZE = 10_000;

    private static final Set<Class<? extends IBaseResource>> PRACTITIONER_TYPES = Set.of(
            Practitioner.class, PractitionerRole.class
    );



    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final FhirContext fhirContext;

    /** Cache: Encounter UUID → lookup result (practitioner + visit UUID) */
    private final ConcurrentHashMap<String, CacheEntry> encounterPractitionerCache = new ConcurrentHashMap<>();

    public void enrich(IBaseResource resource) {
        if (resource == null) return;
        // Patient — no reliable practitioner source from encounter context
        if (resource instanceof Patient) return;

        // Special case: Encounter uses backbone element (participant.individual)
        if (resource instanceof Encounter encounter) {
            enrichEncounterParticipant(encounter);
            return;
        }

        // Special case: ServiceRequest — populate performer (fulfiller),
        // leave requester as-is (placer/source system)
        if (resource instanceof ServiceRequest sr) {
            enrichServiceRequestPerformer(sr);
            return;
        }

        // Generic path: discover encounter ref → resolve practitioner → populate empty fields
        FhirTerser terser = fhirContext.newTerser();
        String encounterId = findEncounterReference(resource, terser);
        if (encounterId == null) return;

        Reference practRef = resolvePractitionerFromEncounter(encounterId);
        if (practRef == null) return;

        populateEmptyPractitionerFields(resource, practRef);
    }

    /**
     * Encounter special case — participant is a backbone element, not a flat Reference.
     * Re-fetches the encounter to populate participant.individual if empty.
     */
    private void enrichEncounterParticipant(Encounter encounter) {
        if (encounter.hasParticipant() && !encounter.getParticipant().isEmpty()) return;
        String encId = encounter.getIdElement() != null ? encounter.getIdElement().getIdPart() : null;
        if (encId == null) return;

        Reference practRef = resolvePractitionerFromEncounter(encId);
        if (practRef != null) {
            Encounter.EncounterParticipantComponent participant = encounter.addParticipant();
            participant.setIndividual(practRef);
            log.debug("Enriched Encounter.participant with {}", practRef.getReference());
        }
    }

    /**
     * ServiceRequest: the requester is left as-is (placer/source system).
     * The OpenMRS practitioner who performed the service goes into performer,
     * resolved from the linked encounter's participant. If the linked encounter
     * has no participant (e.g. "Referral In" auto-created by SPICE), falls back
     * to searching for a sibling "Referral Response" encounter under the same
     * visit to find the fulfilling practitioner.
     */
    private void enrichServiceRequestPerformer(ServiceRequest sr) {
        // Skip if performer is already populated
        if (sr.hasPerformer() && sr.getPerformer().stream().anyMatch(Reference::hasReference)) {
            return;
        }

        // Resolve from the linked encounter's participant
        String encounterId = null;
        if (sr.hasEncounter() && sr.getEncounter().hasReference()) {
            encounterId = extractEncounterId(sr.getEncounter());
        }
        if (encounterId == null) return;

        EncounterLookupResult lookup = resolveEncounterLookup(encounterId);
        Reference practRef = lookup.practitionerRef();

        // Fallback: if linked encounter has no participant (e.g. "Referral In"),
        // find the latest "Referral Response" encounter under the same visit
        // and resolve the practitioner from its participant.
        if (practRef == null && lookup.visitUuid() != null) {
            practRef = resolvePractitionerFromSiblingReferralResponse(lookup.visitUuid());
        }

        if (practRef == null) return;

        sr.addPerformer(practRef.copy());
        log.info("Enriched ServiceRequest/{}.performer from sibling Referral Response: {}",
                sr.getIdElement().getIdPart(), practRef.getReference());
    }

    /**
     * Searches for the latest "Referral Response" encounter under the given visit
     * (using OpenMRS REST API) and resolves the practitioner from its FHIR
     * participant. This scopes the lookup to the same visit as the ServiceRequest's
     * encounter, avoiding cross-visit contamination.
     */
    private Reference resolvePractitionerFromSiblingReferralResponse(String visitUuid) {
        String encounterTypeUuid = properties.getReferral().getResponse().getResponseEncounterTypeUuid();
        if (encounterTypeUuid == null || encounterTypeUuid.isBlank()) {
            log.debug("No referral response encounter type UUID configured; skipping sibling lookup");
            return null;
        }

        // Fetch the visit resource with its encounters and their encounter types
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + "/ws/rest/v1/visit/" + visitUuid
                + "?v=custom:(uuid,encounters:(uuid,encounterType:(uuid)))";

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String auth = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (auth != null) {
            headers.set(HttpHeaders.AUTHORIZATION, auth);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) return null;

            // Find the first encounter matching the configured Referral Response type
            // (encounters are returned newest-first by OpenMRS)
            String siblingEncUuid = extractFirstEncounterByType(body, encounterTypeUuid);
            if (siblingEncUuid == null) {
                log.debug("No Referral Response encounter found in visit {}", visitUuid);
                return null;
            }

            // Resolve practitioner from that encounter's FHIR participant
            Reference practRef = resolvePractitionerFromEncounter(siblingEncUuid);
            if (practRef != null) {
                log.debug("Resolved performer from sibling Referral Response encounter {} in visit {}",
                        siblingEncUuid, visitUuid);
            }
            return practRef;
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Visit {} not found", visitUuid);
            return null;
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("Failed to fetch visit/{}: {}", visitUuid, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error fetching visit/{}: {}", visitUuid, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the first encounter UUID matching the given encounter type from a visit REST response.
     * Visit response format: {"uuid":"...","encounters":[{"uuid":"...","encounterType":{"uuid":"..."}},...]}
     * Encounters are returned newest-first by OpenMRS.
     */
    private String extractFirstEncounterByType(String json, String encounterTypeUuid) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode encounters = root.get("encounters");
            if (encounters == null || !encounters.isArray() || encounters.isEmpty()) return null;
            for (com.fasterxml.jackson.databind.JsonNode enc : encounters) {
                com.fasterxml.jackson.databind.JsonNode encType = enc.get("encounterType");
                if (encType != null) {
                    com.fasterxml.jackson.databind.JsonNode typeUuid = encType.get("uuid");
                    if (typeUuid != null && encounterTypeUuid.equals(typeUuid.asText())) {
                        com.fasterxml.jackson.databind.JsonNode uuid = enc.get("uuid");
                        return uuid != null ? uuid.asText() : null;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to parse visit REST response: {}", e.getMessage());
            return null;
        }
    }



    /**
     * Generically finds the encounter reference on any resource by trying
     * the standard FHIR field names: "encounter", "context".
     */
    private String findEncounterReference(IBaseResource resource, FhirTerser terser) {
        for (String path : List.of("encounter", "context")) {
            try {
                List<IBase> values = terser.getValues(resource, path);
                if (values != null && !values.isEmpty() && values.get(0) instanceof Reference ref) {
                    String encId = extractEncounterId(ref);
                    if (encId != null) return encId;
                }
            } catch (Exception e) {
                // Path doesn't exist on this resource type — continue
            }
        }
        return null;
    }

    /**
     * Walks the resource's runtime definition and populates any empty Reference field
     * whose target types include Practitioner or PractitionerRole.
     */
    private void populateEmptyPractitionerFields(IBaseResource resource, Reference practRef) {
        RuntimeResourceDefinition resDef = fhirContext.getResourceDefinition(resource);

        for (BaseRuntimeChildDefinition child : resDef.getChildren()) {
            if (!(child instanceof RuntimeChildResourceDefinition resourceChild)) continue;

            // Check if this field's targets include Practitioner/PractitionerRole
            boolean targetsPractitioner = resourceChild.getResourceTypes().stream()
                    .anyMatch(PRACTITIONER_TYPES::contains);
            if (!targetsPractitioner) continue;

            // Skip if field already has a populated reference
            List<IBase> values = child.getAccessor().getValues(resource);
            if (values != null && !values.isEmpty()) {
                boolean hasPopulated = values.stream()
                        .anyMatch(v -> v instanceof Reference r && r.hasReference());
                if (hasPopulated) continue;
            }

            // Populate with practitioner reference
            child.getMutator().addValue(resource, practRef.copy());
            log.debug("Enriched {}.{} with practitioner {}",
                    resource.fhirType(), child.getElementName(), practRef.getReference());
        }
    }

    /**
     * Resolves encounter lookup: practitioner from participant + visit UUID from partOf.
     * Result is cached.
     */
    private EncounterLookupResult resolveEncounterLookup(String encounterId) {
        evictIfOverCapacity();
        Instant now = Instant.now();

        CacheEntry entry = encounterPractitionerCache.get(encounterId);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return new EncounterLookupResult(entry.practitionerRef(), entry.visitUuid());
        }

        EncounterLookupResult result = fetchEncounterLookup(encounterId);
        encounterPractitionerCache.put(encounterId,
                new CacheEntry(result.practitionerRef(), result.visitUuid(),
                        now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
        return result;
    }

    /**
     * Fetches the FHIR Encounter and extracts the first practitioner reference
     * from {@code participant[].individual}. Also used by other enrichment paths.
     */
    private Reference resolvePractitionerFromEncounter(String encounterId) {
        return resolveEncounterLookup(encounterId).practitionerRef();
    }

    private EncounterLookupResult fetchEncounterLookup(String encounterId) {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + ensureLeadingSlash(openmrs.getFhirPath())
                + "/Encounter/" + encounterId;

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String auth = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (auth != null) {
            headers.set(HttpHeaders.AUTHORIZATION, auth);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) return EncounterLookupResult.EMPTY;

            IBaseResource parsed = fhirContext.newJsonParser().parseResource(body);
            if (!(parsed instanceof Encounter enc)) return EncounterLookupResult.EMPTY;

            // Extract visit UUID from partOf
            String visitUuid = null;
            if (enc.hasPartOf() && enc.getPartOf().hasReference()) {
                visitUuid = extractEncounterId(enc.getPartOf());
            }

            // Extract practitioner from participant
            Reference practRef = null;
            if (enc.hasParticipant()) {
                for (Encounter.EncounterParticipantComponent p : enc.getParticipant()) {
                    if (p.hasIndividual() && p.getIndividual().hasReference()
                            && p.getIndividual().getReference().contains("Practitioner/")) {
                        practRef = copyReference(p.getIndividual());
                        break;
                    }
                }
            }

            return new EncounterLookupResult(practRef, visitUuid);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Encounter/{} not found while resolving practitioner", encounterId);
            return EncounterLookupResult.EMPTY;
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("Failed to resolve practitioner from Encounter/{}: {}", encounterId, e.getMessage());
            return EncounterLookupResult.EMPTY;
        } catch (Exception e) {
            log.warn("Unexpected error resolving practitioner from Encounter/{}: {}", encounterId, e.getMessage());
            return EncounterLookupResult.EMPTY;
        }
    }

    private static Reference copyReference(Reference src) {
        Reference ref = new Reference();
        ref.setReference(src.getReference());
        if (src.hasType()) ref.setType(src.getType());
        if (src.hasDisplay()) ref.setDisplay(src.getDisplay());
        if (src.hasIdentifier()) ref.setIdentifier(src.getIdentifier());
        return ref;
    }

    private static String extractEncounterId(Reference encounterRef) {
        if (encounterRef == null || !encounterRef.hasReference()) return null;
        String ref = encounterRef.getReference();
        if (ref == null) return null;
        String prefix = "Encounter/";
        if (ref.startsWith(prefix)) {
            return ref.substring(prefix.length());
        }
        int idx = ref.lastIndexOf("/" + prefix);
        if (idx >= 0) {
            return ref.substring(idx + ("/" + prefix).length());
        }
        return null;
    }

    private void evictIfOverCapacity() {
        if (encounterPractitionerCache.size() >= CACHE_MAX_SIZE) {
            encounterPractitionerCache.clear();
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String ensureLeadingSlash(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.startsWith("/") ? url : "/" + url;
    }

    private record CacheEntry(Reference practitionerRef, String visitUuid, Instant expiresAt) { }

    private record EncounterLookupResult(Reference practitionerRef, String visitUuid) {
        static final EncounterLookupResult EMPTY = new EncounterLookupResult(null, null);
    }
}
