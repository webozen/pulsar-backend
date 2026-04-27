package com.pulsar.host.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    // Defaults match the dev story: any localhost or pulsar.local subdomain
    // on any port. Operators MUST override this for any deployed env via the
    // pulsar.cors.allowed-origins property (comma-separated list of patterns).
    private static final String DEV_DEFAULTS =
        "http://localhost:*,http://*.localhost:*,http://pulsar.local:*,http://*.pulsar.local:*";

    @Bean
    public CorsFilter corsFilter(
        @Value("${pulsar.cors.allowed-origins:" + DEV_DEFAULTS + "}") String[] allowedOriginPatterns
    ) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowCredentials(false);
        for (String pattern : allowedOriginPatterns) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) c.addAllowedOriginPattern(trimmed);
        }
        // Explicit method + header lists. Wildcards were safe with
        // allowCredentials=false but enumerating makes the surface
        // auditable and rejects exotic verbs (TRACE, CONNECT, PROPFIND).
        c.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(java.util.List.of(
            "Authorization", "Content-Type", "Accept", "Accept-Language",
            "X-Requested-With", "Origin", "Cache-Control"
        ));

        boolean usingDevDefaults = String.join(",", allowedOriginPatterns).equals(DEV_DEFAULTS);
        if (usingDevDefaults) {
            log.warn("CORS using dev defaults ({}). Set PULSAR_CORS_ALLOWED_ORIGINS in any deployed env.",
                DEV_DEFAULTS);
        } else {
            log.info("CORS allowedOriginPatterns: {}", String.join(",", allowedOriginPatterns));
        }

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return new CorsFilter(src);
    }
}
