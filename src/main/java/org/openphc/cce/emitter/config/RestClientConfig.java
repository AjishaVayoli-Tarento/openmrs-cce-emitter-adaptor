package org.openphc.cce.emitter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(defaultFactory());
    }

    @Bean(name = "trustAllRestTemplate")
    public RestTemplate trustAllRestTemplate() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new java.security.SecureRandom());
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                    if (connection instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(sslContext.getSocketFactory());
                        https.setHostnameVerifier((hostname, session) -> true);
                    }
                    super.prepareConnection(connection, httpMethod);
                }
            };
            applyTimeouts(factory);
            return new RestTemplate(factory);
        } catch (Exception e) {
            log.warn("Failed to build trust-all SSL context, falling back to standard RestTemplate: {}", e.getMessage());
            return new RestTemplate(defaultFactory());
        }
    }

    private static SimpleClientHttpRequestFactory defaultFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        applyTimeouts(factory);
        return factory;
    }

    private static void applyTimeouts(SimpleClientHttpRequestFactory factory) {
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
