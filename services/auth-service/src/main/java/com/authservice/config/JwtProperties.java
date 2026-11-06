package com.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        String keyId,
        String privateKeyBase64,
        String publicKeyBase64
) {
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "http://localhost:8081";
        }
        if (audience == null || audience.isBlank()) {
            audience = "tenantguard-api";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(10);
        }
        if (keyId == null || keyId.isBlank()) {
            keyId = "tenantguard-local";
        }
    }
}

