package org.openphc.cce.emitter.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name:openmrs-cce-emitter-adaptor}") String appName) {
        return registry -> registry.config().meterFilter(MeterFilter.commonTags(
                io.micrometer.core.instrument.Tags.of("application", appName)));
    }
}
