package org.openphc.cce.emitter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "emitter.openmrs.base-url=http://localhost:8080/openmrs",
        "emitter.openhim.base-url=http://localhost:5001/fhir",
        "emitter.polling.fhir.enabled=false"
})
class OpenmrsCceEmitterAdaptorApplicationTests {

    @Test
    void contextLoads() {
    }
}
