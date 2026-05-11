package org.openphc.cce.emitter;

import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EmitterProperties.class)
public class OpenmrsCceEmitterAdaptorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenmrsCceEmitterAdaptorApplication.class, args);
    }
}
