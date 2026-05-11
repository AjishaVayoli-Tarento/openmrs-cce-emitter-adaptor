package org.openphc.cce.emitter.health;

import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.AuthService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component("openmrs")
@RequiredArgsConstructor
public class OpenmrsHealthIndicator implements HealthIndicator {

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;

    @Override
    public Health health() {
        EmitterProperties.OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = strip(openmrs.getBaseUrl()) + lead(openmrs.getFhirPath()) + "/metadata";
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
            if (authHeader != null) headers.set(HttpHeaders.AUTHORIZATION, authHeader);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            long elapsed = System.currentTimeMillis() - start;
            if (response.getStatusCode().is2xxSuccessful()) {
                return Health.up()
                        .withDetail("serverName", openmrs.getName())
                        .withDetail("url", url)
                        .withDetail("responseTimeMs", elapsed)
                        .build();
            }
            return Health.unknown()
                    .withDetail("status", response.getStatusCode().value())
                    .withDetail("url", url)
                    .build();
        } catch (RestClientResponseException e) {
            return Health.unknown()
                    .withDetail("status", e.getStatusCode().value())
                    .withDetail("url", url)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("url", url)
                    .build();
        }
    }

    private static String strip(String s) { return s == null ? "" : (s.endsWith("/") ? s.substring(0, s.length() - 1) : s); }
    private static String lead(String s) { return s == null || s.isEmpty() ? "" : (s.startsWith("/") ? s : "/" + s); }
}
