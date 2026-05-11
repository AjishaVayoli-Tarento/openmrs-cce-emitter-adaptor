package org.openphc.cce.emitter.config;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirConfig {

    /**
     * FhirContext is expensive to create and thread-safe. Share a single R4 instance.
     */
    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }
}
