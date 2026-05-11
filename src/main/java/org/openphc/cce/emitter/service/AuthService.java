package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.config.EmitterProperties.OAuth2Config;
import org.openphc.cce.emitter.config.EmitterProperties.OpenhimAuthConfig;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsAuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration TOKEN_EXPIRY_BUFFER = Duration.ofSeconds(30);
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public String resolveOpenmrsAuthHeader(OpenmrsAuthConfig auth) {
        String type = auth.getType() == null ? "basic" : auth.getType().toLowerCase();
        return switch (type) {
            case "basic" -> basicHeader(auth.getUsername(), auth.getPassword());
            case "bearer" -> "Bearer " + auth.getToken();
            case "oauth2" -> "Bearer " + getOAuth2Token(auth.getOauth2());
            default -> throw new IllegalArgumentException("Unknown OpenMRS auth type: " + auth.getType());
        };
    }

    public String resolveOpenhimAuthHeader(OpenhimAuthConfig auth) {
        String type = auth.getType() == null ? "none" : auth.getType().toLowerCase();
        return switch (type) {
            case "none" -> null;
            case "basic" -> basicHeader(auth.getUsername(), auth.getPassword());
            case "jwt" -> "Bearer " + auth.getToken();
            case "custom-token" -> auth.getToken();
            default -> throw new IllegalArgumentException("Unknown OpenHIM auth type: " + auth.getType());
        };
    }

    public String getOAuth2Token(OAuth2Config oauth2Config) {
        String key = oauth2Config.getTokenUrl();
        CachedToken cached = tokenCache.get(key);
        Instant cutoff = Instant.now().plus(TOKEN_EXPIRY_BUFFER);
        if (cached != null && cached.expiresAt.isAfter(cutoff)) {
            return cached.token;
        }
        CachedToken fresh = fetchOAuth2Token(oauth2Config);
        tokenCache.put(key, fresh);
        return fresh.token;
    }

    public String refreshOAuth2Token(OAuth2Config oauth2Config) {
        tokenCache.remove(oauth2Config.getTokenUrl());
        return getOAuth2Token(oauth2Config);
    }

    private CachedToken fetchOAuth2Token(OAuth2Config c) {
        if (c == null || c.getTokenUrl() == null || c.getTokenUrl().isBlank()) {
            throw new IllegalStateException("OAuth2 token-url is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", c.getClientId());
        form.add("client_secret", c.getClientSecret());
        if (c.getScope() != null && !c.getScope().isBlank()) {
            form.add("scope", c.getScope());
        }

        try {
            String body = restTemplate.postForObject(c.getTokenUrl(), new HttpEntity<>(form, headers), String.class);
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("OAuth2 token endpoint did not return access_token");
            }
            long expiresIn = node.path("expires_in").asLong(DEFAULT_EXPIRES_IN_SECONDS);
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);
            log.debug("OAuth2 token fetched, expires in {}s", expiresIn);
            return new CachedToken(accessToken, expiresAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch OAuth2 token from " + c.getTokenUrl() + ": " + e.getMessage(), e);
        }
    }

    private static String basicHeader(String user, String pass) {
        String credentials = (user == null ? "" : user) + ":" + (pass == null ? "" : pass);
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private record CachedToken(String token, Instant expiresAt) { }
}
