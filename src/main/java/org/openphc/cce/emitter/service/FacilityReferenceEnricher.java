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
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
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
 * Generically populates empty Location reference fields on any outgoing FHIR
 * resource, using HAPI FHIR's runtime resource definitions to discover which
 * fields target Location — no hardcoded resource types needed.
 *
 * <p>Uses {@link RuntimeChildResourceDefinition#getResourceTypes()} to determine
 * which fields on a resource accept Location references. If a field is empty
 * and the linked Encounter has a location, it is populated automatically.
 *
 * <p>Special case: For {@code Encounter} resources, the {@code location}
 * backbone element is handled separately since it is not a flat Reference field.
 *
 * <p>Skipped: {@code Patient.managingOrganization} targets Organization, not Location.
 */
@Service
@RequiredArgsConstructor
public class FacilityReferenceEnricher {

    private static final Logger log = LoggerFactory.getLogger(FacilityReferenceEnricher.class);
    private static final long CACHE_TTL_SECONDS = 3600L;
    private static final int CACHE_MAX_SIZE = 10_000;

    private static final Set<Class<? extends IBaseResource>> LOCATION_TYPES = Set.of(
            Location.class
    );

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final FhirContext fhirContext;

    /** Cache: Encounter UUID → first Location Reference from encounter.location */
    private final ConcurrentHashMap<String, CacheEntry> encounterLocationCache = new ConcurrentHashMap<>();

    public void enrich(IBaseResource resource) {
        if (resource == null) return;
        // Patient.managingOrganization targets Organization, not Location — skip
        if (resource instanceof Patient) return;

        // Special case: Encounter uses backbone element (location[].location)
        if (resource instanceof Encounter encounter) {
            enrichEncounterLocation(encounter);
            return;
        }

        // Generic path: discover encounter ref → resolve location → populate empty fields
        FhirTerser terser = fhirContext.newTerser();
        String encounterId = findEncounterReference(resource, terser);
        if (encounterId == null) return;

        Reference locRef = resolveLocationFromEncounter(encounterId);
        if (locRef == null) return;

        populateEmptyLocationFields(resource, locRef);
    }

    /**
     * Encounter special case — location is a backbone element, not a flat Reference.
     * Re-fetches the encounter to populate location[].location if empty.
     */
    private void enrichEncounterLocation(Encounter encounter) {
        if (encounter.hasLocation() && !encounter.getLocation().isEmpty()) return;
        String encId = encounter.getIdElement() != null ? encounter.getIdElement().getIdPart() : null;
        if (encId == null) return;

        Reference locRef = resolveLocationFromEncounter(encId);
        if (locRef != null) {
            Encounter.EncounterLocationComponent loc = encounter.addLocation();
            loc.setLocation(locRef);
            log.debug("Enriched Encounter.location with {}", locRef.getReference());
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
     * whose target types include Location.
     */
    private void populateEmptyLocationFields(IBaseResource resource, Reference locRef) {
        RuntimeResourceDefinition resDef = fhirContext.getResourceDefinition(resource);

        for (BaseRuntimeChildDefinition child : resDef.getChildren()) {
            if (!(child instanceof RuntimeChildResourceDefinition resourceChild)) continue;

            // Check if this field's targets include Location
            boolean targetsLocation = resourceChild.getResourceTypes().stream()
                    .anyMatch(LOCATION_TYPES::contains);
            if (!targetsLocation) continue;

            // Skip if field already has a populated reference
            List<IBase> values = child.getAccessor().getValues(resource);
            if (values != null && !values.isEmpty()) {
                boolean hasPopulated = values.stream()
                        .anyMatch(v -> v instanceof Reference r && r.hasReference());
                if (hasPopulated) continue;
            }

            // Populate with location reference
            child.getMutator().addValue(resource, locRef.copy());
            log.debug("Enriched {}.{} with location {}",
                    resource.fhirType(), child.getElementName(), locRef.getReference());
        }
    }

    /**
     * Fetches the FHIR Encounter and extracts the first location reference
     * from {@code location[].location}. Result is cached.
     */
    private Reference resolveLocationFromEncounter(String encounterId) {
        evictIfOverCapacity();
        Instant now = Instant.now();

        CacheEntry entry = encounterLocationCache.get(encounterId);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.locationRef();
        }

        Reference ref = fetchLocationFromEncounter(encounterId);
        encounterLocationCache.put(encounterId,
                new CacheEntry(ref, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
        return ref;
    }

    private Reference fetchLocationFromEncounter(String encounterId) {
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
            if (body == null || body.isBlank()) return null;

            IBaseResource parsed = fhirContext.newJsonParser().parseResource(body);
            if (!(parsed instanceof Encounter enc)) return null;

            if (!enc.hasLocation()) return null;
            for (Encounter.EncounterLocationComponent loc : enc.getLocation()) {
                if (loc.hasLocation() && loc.getLocation().hasReference()
                        && loc.getLocation().getReference().contains("Location/")) {
                    return copyReference(loc.getLocation());
                }
            }
            return null;
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Encounter/{} not found while resolving facility", encounterId);
            return null;
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("Failed to resolve facility from Encounter/{}: {}", encounterId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error resolving facility from Encounter/{}: {}", encounterId, e.getMessage());
            return null;
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
        if (encounterLocationCache.size() >= CACHE_MAX_SIZE) {
            encounterLocationCache.clear();
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

    private record CacheEntry(Reference locationRef, Instant expiresAt) { }
}
