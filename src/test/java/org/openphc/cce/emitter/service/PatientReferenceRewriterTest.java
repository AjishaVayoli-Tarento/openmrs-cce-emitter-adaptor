package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PatientReferenceRewriter} — rewriting {@code Patient/{openmrsId}} references
 * to {@code Patient/{nationalId}}, and the {@code on-missing} policy (skip / forward-as-is / fail).
 */
@ExtendWith(MockitoExtension.class)
class PatientReferenceRewriterTest {

    @Mock
    private NationalIdResolver resolver;

    private final FhirContext fhirContext = FhirContext.forR4();
    private EmitterProperties properties;
    private PatientReferenceRewriter rewriter;

    @BeforeEach
    void setUp() {
        properties = new EmitterProperties();
        rewriter = new PatientReferenceRewriter(properties, fhirContext, resolver);
    }

    private ServiceRequest serviceRequestReferencing(String patientRef) {
        ServiceRequest sr = new ServiceRequest();
        sr.setSubject(new Reference(patientRef));
        return sr;
    }

    @Test
    void rewrite_whenDisabled_leavesResourceUnchanged() {
        properties.getPatient().getNationalId().setEnabled(false);
        ServiceRequest sr = serviceRequestReferencing("Patient/openmrs-1");

        PatientReferenceRewriter.Result result = rewriter.rewrite(sr);

        assertEquals(PatientReferenceRewriter.Outcome.UNCHANGED, result.outcome());
        assertEquals("Patient/openmrs-1", sr.getSubject().getReference());
    }

    @Test
    void rewrite_whenNullResource_isUnchanged() {
        PatientReferenceRewriter.Result result = rewriter.rewrite(null);

        assertEquals(PatientReferenceRewriter.Outcome.UNCHANGED, result.outcome());
    }

    @Test
    void rewrite_whenNationalIdResolves_rewritesReference() {
        when(resolver.resolve("openmrs-1")).thenReturn(Optional.of("nat-1"));
        ServiceRequest sr = serviceRequestReferencing("Patient/openmrs-1");

        PatientReferenceRewriter.Result result = rewriter.rewrite(sr);

        assertEquals(PatientReferenceRewriter.Outcome.REWRITTEN, result.outcome());
        assertEquals("Patient/nat-1", sr.getSubject().getReference());
    }

    @Test
    void rewrite_whenMissingAndOnMissingSkip_returnsSkip() {
        properties.getPatient().getNationalId().setOnMissing("skip");
        when(resolver.resolve("openmrs-1")).thenReturn(Optional.empty());
        ServiceRequest sr = serviceRequestReferencing("Patient/openmrs-1");

        PatientReferenceRewriter.Result result = rewriter.rewrite(sr);

        assertEquals(PatientReferenceRewriter.Outcome.SKIP_MISSING_NATIONAL_ID, result.outcome());
        assertEquals("openmrs-1", result.missingPatientId());
        assertEquals("Patient/openmrs-1", sr.getSubject().getReference());
    }

    @Test
    void rewrite_whenMissingAndOnMissingFail_returnsFail() {
        properties.getPatient().getNationalId().setOnMissing("fail");
        when(resolver.resolve("openmrs-1")).thenReturn(Optional.empty());
        ServiceRequest sr = serviceRequestReferencing("Patient/openmrs-1");

        PatientReferenceRewriter.Result result = rewriter.rewrite(sr);

        assertEquals(PatientReferenceRewriter.Outcome.FAIL_MISSING_NATIONAL_ID, result.outcome());
    }

    @Test
    void rewrite_whenMissingAndForwardAsIs_returnsUnchangedButRecordsMissingId() {
        properties.getPatient().getNationalId().setOnMissing("forward-as-is");
        when(resolver.resolve("openmrs-1")).thenReturn(Optional.empty());
        ServiceRequest sr = serviceRequestReferencing("Patient/openmrs-1");

        PatientReferenceRewriter.Result result = rewriter.rewrite(sr);

        assertEquals(PatientReferenceRewriter.Outcome.UNCHANGED, result.outcome());
        assertEquals("openmrs-1", result.missingPatientId());
    }

    @Test
    void rewrite_patientResource_rewritesIdAndPreservesOpenmrsUuidIdentifier() {
        Patient patient = new Patient();
        patient.setId("openmrs-1");
        when(resolver.extractNationalId(patient)).thenReturn("nat-1");
        // resolve() may be consulted as a fallback; keep it lenient.
        lenient().when(resolver.resolve("openmrs-1")).thenReturn(Optional.of("nat-1"));

        PatientReferenceRewriter.Result result = rewriter.rewrite(patient);

        assertEquals(PatientReferenceRewriter.Outcome.REWRITTEN, result.outcome());
        assertEquals("Patient/nat-1", patient.getIdElement().getValue());
        boolean hasOpenmrsUuid = patient.getIdentifier().stream()
                .anyMatch(i -> "urn:openmrs:patient-uuid".equals(i.getSystem()) && "openmrs-1".equals(i.getValue()));
        assertTrue(hasOpenmrsUuid, "original OpenMRS UUID should be preserved as an identifier");
    }
}
