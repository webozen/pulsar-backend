package com.pulsar.host.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowCredentials(false);
        c.addAllowedOriginPattern("http://*.pulsar.local:*");
        c.addAllowedOriginPattern("http://pulsar.local:*");
        c.addAllowedOriginPattern("http://*.localhost:*");
        c.addAllowedOriginPattern("http://localhost:*");
        c.addAllowedHeader("*");
        c.addAllowedMethod("*");

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return new CorsFilter(src);
    }
}
