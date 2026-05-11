package org.openphc.cce.emitter.health;

import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component("openhim")
public class OpenhimHealthIndicator implements HealthIndicator {

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final RestTemplate trustAllRestTemplate;

    public OpenhimHealthIndicator(EmitterProperties properties,
                                  RestTemplate restTemplate,
                                  @Qualifier("trustAllRestTemplate") RestTemplate trustAllRestTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.trustAllRestTemplate = trustAllRestTemplate;
    }

    @Override
    public Health health() {
        EmitterProperties.OpenhimConfig openhim = properties.getOpenhim();
        String url = openhim.getBaseUrl();
        RestTemplate client = openhim.isSslTrustAll() ? trustAllRestTemplate : restTemplate;
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Void> response = client.exchange(url, HttpMethod.HEAD,
                    new HttpEntity<>(new HttpHeaders()), Void.class);
            long elapsed = System.currentTimeMillis() - start;
            int code = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful() || response.getStatusCode().is3xxRedirection()) {
                return Health.up()
                        .withDetail("openhimName", openhim.getName())
                        .withDetail("url", url)
                        .withDetail("responseTimeMs", elapsed)
                        .build();
            }
            return Health.unknown().withDetail("status", code).withDetail("url", url).build();
        } catch (RestClientResponseException e) {
            return Health.unknown().withDetail("status", e.getStatusCode().value()).withDetail("url", url).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("url", url).build();
        }
    }
}
