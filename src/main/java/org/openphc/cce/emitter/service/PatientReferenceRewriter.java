package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.util.FhirTerser;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.NationalIdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a FHIR resource and rewrites every {@code Patient/{openmrsId}} reference
 * with {@code Patient/{national-id-value}}.
 *
 * <p>Behavior follows the {@code emitter.patient.national-id.on-missing} policy:
 * {@code skip}, {@code forward-as-is}, or {@code fail}.
 */
@Service
@RequiredArgsConstructor
public class PatientReferenceRewriter {

    private static final Logger log = LoggerFactory.getLogger(PatientReferenceRewriter.class);
    private static final String OPENMRS_UUID_SYSTEM = "urn:openmrs:patient-uuid";

    private final EmitterProperties properties;
    private final FhirContext fhirContext;
    private final NationalIdResolver resolver;

    public enum Outcome {
        REWRITTEN,
        UNCHANGED,
        SKIP_MISSING_NATIONAL_ID,
        FAIL_MISSING_NATIONAL_ID
    }

    public record Result(Outcome outcome, IBaseResource resource, String missingPatientId) { }

    public Result rewrite(IBaseResource resource) {
        NationalIdConfig cfg = properties.getPatient().getNationalId();
        if (resource == null) {
            return new Result(Outcome.UNCHANGED, null, null);
        }
        if (!cfg.isEnabled()) {
            return new Result(Outcome.UNCHANGED, resource, null);
        }

        // Special case: rewriting the Patient resource itself.
        if (resource instanceof Patient patient) {
            return rewritePatient(patient, cfg);
        }

        FhirTerser terser = fhirContext.newTerser();
        List<Reference> references = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);

        Set<String> unresolved = new HashSet<>();
        boolean changed = false;
        for (Reference reference : references) {
            if (reference == null) continue;
            String refStr = reference.getReference();
            if (refStr == null || !refStr.startsWith("Patient/")) continue;
            String openmrsId = refStr.substring("Patient/".length());
            if (openmrsId.isBlank()) continue;

            Optional<String> nationalId = resolver.resolve(openmrsId);
            if (nationalId.isEmpty()) {
                unresolved.add(openmrsId);
                continue;
            }
            reference.setReference("Patient/" + nationalId.get());
            changed = true;
        }

        if (!unresolved.isEmpty()) {
            return handleMissing(cfg, resource, unresolved.iterator().next(), changed);
        }
        return new Result(changed ? Outcome.REWRITTEN : Outcome.UNCHANGED, resource, null);
    }

    private Result rewritePatient(Patient patient, NationalIdConfig cfg) {
        String openmrsId = patient.getIdElement() != null ? patient.getIdElement().getIdPart() : null;
        String nationalId = resolver.extractNationalId(patient);

        if (nationalId == null && openmrsId != null) {
            // fall back to a network lookup for parity with cross-referenced patients
            nationalId = resolver.resolve(openmrsId).orElse(null);
        }
        if (nationalId == null) {
            return handleMissing(cfg, patient, openmrsId, false);
        }

        // Preserve the original OpenMRS UUID as an additional identifier (idempotent).
        if (openmrsId != null && !openmrsId.isBlank()) {
            boolean alreadyPresent = patient.getIdentifier().stream()
                    .anyMatch(i -> OPENMRS_UUID_SYSTEM.equals(i.getSystem()) && openmrsId.equals(i.getValue()));
            if (!alreadyPresent) {
                Identifier marker = new Identifier()
                        .setSystem(OPENMRS_UUID_SYSTEM)
                        .setValue(openmrsId);
                marker.getType().addCoding(new Coding().setSystem(OPENMRS_UUID_SYSTEM).setCode("openmrs-uuid"));
                patient.addIdentifier(marker);
            }
        }
        patient.setId("Patient/" + nationalId);
        return new Result(Outcome.REWRITTEN, patient, null);
    }

    private Result handleMissing(NationalIdConfig cfg, IBaseResource resource, String openmrsId, boolean partiallyChanged) {
        String mode = cfg.getOnMissing() == null ? "skip" : cfg.getOnMissing().toLowerCase();
        return switch (mode) {
            case "forward-as-is" -> {
                log.warn("No national-id for Patient/{} — forwarding {} as-is per on-missing=forward-as-is",
                        openmrsId, resource.fhirType());
                yield new Result(partiallyChanged ? Outcome.REWRITTEN : Outcome.UNCHANGED, resource, openmrsId);
            }
            case "fail" -> {
                log.warn("No national-id for Patient/{} — failing forward per on-missing=fail", openmrsId);
                yield new Result(Outcome.FAIL_MISSING_NATIONAL_ID, resource, openmrsId);
            }
            default -> {
                log.warn("No national-id for Patient/{} — skipping {} per on-missing=skip",
                        openmrsId, resource.fhirType());
                yield new Result(Outcome.SKIP_MISSING_NATIONAL_ID, resource, openmrsId);
            }
        };
    }
}
