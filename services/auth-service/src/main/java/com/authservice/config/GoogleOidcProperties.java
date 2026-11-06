package com.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.google")
public record GoogleOidcProperties(
        String clientId,
        String clientSecret
) {
    public GoogleOidcProperties {
        if (clientId == null) {
            clientId = "";
        }
        if (clientSecret == null) {
            clientSecret = "";
        }
    }
}

