package org.openphc.cce.emitter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "emitter")
public class EmitterProperties {

    @Valid
    @NotNull
    private OpenmrsConfig openmrs = new OpenmrsConfig();

    @Valid
    @NotNull
    private OpenhimConfig openhim = new OpenhimConfig();

    @Valid
    private PollingConfig polling = new PollingConfig();

    @Valid
    private CheckpointConfig checkpoint = new CheckpointConfig();

    @Valid
    private PatientConfig patient = new PatientConfig();

    @Valid
    private ReferralConfig referral = new ReferralConfig();

    @Data
    public static class OpenmrsConfig {
        @NotBlank
        private String name = "openmrs";
        @NotBlank
        private String baseUrl;
        private String fhirPath = "/ws/fhir2/R4";
        @Valid
        private OpenmrsAuthConfig auth = new OpenmrsAuthConfig();
    }

    @Data
    public static class OpenmrsAuthConfig {
        /** basic | bearer | oauth2 */
        private String type = "basic";
        private String username;
        private String password;
        private String token;
        @Valid
        private OAuth2Config oauth2 = new OAuth2Config();
    }

    @Data
    public static class OAuth2Config {
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;
    }

    @Data
    public static class OpenhimConfig {
        @NotBlank
        private String name = "openhim";
        @NotBlank
        private String baseUrl;
        @Valid
        private OpenhimAuthConfig auth = new OpenhimAuthConfig();
        private boolean sslTrustAll = false;
        private boolean appendResourceType = true;
        @Valid
        private RetryConfig retry = new RetryConfig();
    }

    @Data
    public static class OpenhimAuthConfig {
        /** none | basic | jwt | custom-token */
        private String type = "basic";
        private String username;
        private String password;
        private String token;
    }

    @Data
    public static class RetryConfig {
        private int maxAttempts = 3;
        private long backoffMs = 2000;
    }

    @Data
    public static class PollingConfig {
        @Valid
        private FhirPollingConfig fhir = new FhirPollingConfig();
    }

    @Data
    public static class FhirPollingConfig {
        private boolean enabled = true;
        private int intervalSeconds = 30;
        private int pageSize = 50;
        private int overlapSeconds = 5;
        /**
         * When a polled ServiceRequest/MedicationRequest carries a basedOn /
         * priorPrescription reference (i.e. it is a revision row), refetch the
         * prior order so its terminal status (revoked/completed) is observed
         * downstream. When the new revision itself is a discontinue tombstone
         * (status revoked/cancelled/stopped), the new resource is suppressed
         * since it carries no independent clinical content.
         */
        private boolean followPriorOrder = true;
        private List<String> resourceTypes = List.of(
                "Patient", "Encounter", "Observation", "Condition", "Immunization",
                "DiagnosticReport", "AllergyIntolerance", "Procedure", "MedicationRequest",
                "MedicationDispense", "MedicationAdministration", "ServiceRequest",
                "Location", "Practitioner"
        );
    }

    @Data
    public static class CheckpointConfig {
        /** file (database planned) */
        private String storeType = "file";
        private String filePath = "./data/checkpoints.json";
    }

    @Data
    public static class PatientConfig {
        @Valid
        private NationalIdConfig nationalId = new NationalIdConfig();
    }

    @Data
    public static class NationalIdConfig {
        private boolean enabled = true;
        private String system = "http://moh.gov.rw/fhir/identifier/national-id";
        private String code = "national-id";
        private long cacheTtlSeconds = 3600;
        private int cacheMaxSize = 10000;
        /** skip | forward-as-is | fail */
        private String onMissing = "skip";
    }

    @Data
    public static class ReferralConfig {
        @Valid
        private ReferralResponseConfig response = new ReferralResponseConfig();
    }

    @Data
    public static class ReferralResponseConfig {
        /**
         * When enabled, ServiceRequest/MedicationRequest resources whose OpenMRS
         * order type display name matches {@link #orderTypeName} and whose status
         * is {@code completed} or {@code revoked} are classified as a referral
         * response: a category coding is added, {@code basedOn} is populated with
         * a logical reference to the placer's ServiceRequest id (sourced from the
         * OpenMRS {@code accessionNumber}), and {@code intent} is flipped to
         * {@code filler-order}. This lets the downstream Compliance Service match
         * the event via standard PlanDefinition action.trigger / action.condition
         * expressions.
         */
        private boolean enabled = true;
        /** OpenMRS order type display name to treat as a referral. */
        private String orderTypeName = "Referral";
        /** CodeSystem URL for the response classification coding added to category. */
        private String categorySystem = "http://cce.openphc.org/CodeSystem/event-type";
        private String categoryCode = "referral-response";
        private String categoryDisplay = "Referral Response";
        /**
         * Identifier system used in {@code basedOn[].identifier.system} when
         * referencing the placer's ServiceRequest id. Must match what the
         * placer system (e.g. SPICE) advertises so the Compliance Service can
         * resolve back to the original placer order.
         */
        private String basedOnIdentifierSystem = "http://mdtlabs.com/service-request-id";
        /** Flip {@code intent} from {@code order} to {@code filler-order}. */
        private boolean flipIntent = false;
    }
}
