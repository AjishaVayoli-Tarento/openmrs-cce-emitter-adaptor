package org.openphc.cce.emitter.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.OpenhimConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Service
public class ForwardingEngine {

    private static final Logger log = LoggerFactory.getLogger(ForwardingEngine.class);
    private static final String METRIC_FORWARD_SUCCESS = "openmrs.emitter.forward.success";
    private static final String METRIC_FORWARD_FAILURE = "openmrs.emitter.forward.failure";
    private static final String METRIC_FORWARD_SKIPPED = "openmrs.emitter.forward.skipped";

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final RestTemplate trustAllRestTemplate;
    private final AuthService authService;
    private final MeterRegistry meterRegistry;

    public ForwardingEngine(EmitterProperties properties,
                            RestTemplate restTemplate,
                            @Qualifier("trustAllRestTemplate") RestTemplate trustAllRestTemplate,
                            AuthService authService,
                            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.trustAllRestTemplate = trustAllRestTemplate;
        this.authService = authService;
        this.meterRegistry = meterRegistry;
    }

    public ForwardResult forward(String resourceJson, String resourceType, String resourceId) {
        OpenhimConfig openhim = properties.getOpenhim();
        String url = buildUrl(openhim, resourceType);
        HttpHeaders headers = buildHeaders(openhim);
        HttpEntity<String> entity = new HttpEntity<>(resourceJson, headers);
        RestTemplate client = openhim.isSslTrustAll() ? trustAllRestTemplate : restTemplate;

        if (log.isDebugEnabled()) {
            log.debug("Forwarding {}/{} body: {}", resourceType, resourceId, resourceJson);
        }

        int maxAttempts = Math.max(1, openhim.getRetry().getMaxAttempts());
        long backoffMs = Math.max(0, openhim.getRetry().getBackoffMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> response = client.exchange(url, HttpMethod.POST, entity, String.class);
                int code = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Forwarded {}/{} -> {} (HTTP {})", resourceType, resourceId, url, code);
                    counter(METRIC_FORWARD_SUCCESS, "resourceType", resourceType).increment();
                    return ForwardResult.success(code);
                }
                log.warn("Forward {}/{} attempt {}/{} returned HTTP {}", resourceType, resourceId, attempt, maxAttempts, code);
                if (isNonRetriableClientError(code)) {
                    log.warn("Forward {}/{} aborting retries — non-retriable HTTP {}",
                            resourceType, resourceId, code);
                    counter(METRIC_FORWARD_FAILURE, "resourceType", resourceType).increment();
                    return ForwardResult.failure(code, response.getBody());
                }
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                log.warn("Forward {}/{} attempt {}/{} failed: HTTP {} {}",
                        resourceType, resourceId, attempt, maxAttempts, code, e.getStatusText());
                if (isNonRetriableClientError(code)) {
                    log.warn("Forward {}/{} aborting retries — non-retriable HTTP {}",
                            resourceType, resourceId, code);
                    counter(METRIC_FORWARD_FAILURE, "resourceType", resourceType).increment();
                    return ForwardResult.failure(code, e.getResponseBodyAsString());
                }
                if (attempt == maxAttempts) {
                    counter(METRIC_FORWARD_FAILURE, "resourceType", resourceType).increment();
                    return ForwardResult.failure(code, e.getResponseBodyAsString());
                }
            } catch (ResourceAccessException e) {
                log.warn("Forward {}/{} attempt {}/{} unreachable: {}",
                        resourceType, resourceId, attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    counter(METRIC_FORWARD_FAILURE, "resourceType", resourceType).increment();
                    return ForwardResult.unreachable(attempt);
                }
            } catch (Exception e) {
                log.error("Forward {}/{} attempt {}/{} error: {}",
                        resourceType, resourceId, attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    counter(METRIC_FORWARD_FAILURE, "resourceType", resourceType).increment();
                    return ForwardResult.failure(0, e.getMessage());
                }
            }
            sleep(backoffMs * attempt);
        }
        counter(METRIC_FORWARD_FAILURE, "resourceType", resourceType).increment();
        return ForwardResult.unreachable(maxAttempts);
    }

    public void recordSkipped(String resourceType, String reason) {
        meterRegistry.counter(METRIC_FORWARD_SKIPPED,
                Tags.of("resourceType", resourceType, "reason", reason)).increment();
    }

    private String buildUrl(OpenhimConfig openhim, String resourceType) {
        String base = stripTrailingSlash(openhim.getBaseUrl());
        if (openhim.isAppendResourceType() && resourceType != null && !resourceType.isBlank()) {
            return base + "/" + resourceType;
        }
        return base;
    }

    private HttpHeaders buildHeaders(OpenhimConfig openhim) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String authHeader = authService.resolveOpenhimAuthHeader(openhim.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return headers;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return meterRegistry.counter(name, Tags.of(tagKey, tagValue));
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 4xx responses indicate a client-side problem (bad payload, auth failure, missing route)
     * that will not be fixed by retrying. Two exceptions are kept retriable because they are
     * transient by definition: 408 (Request Timeout) and 429 (Too Many Requests).
     */
    private static boolean isNonRetriableClientError(int code) {
        return code >= 400 && code < 500 && code != 408 && code != 429;
    }
}
