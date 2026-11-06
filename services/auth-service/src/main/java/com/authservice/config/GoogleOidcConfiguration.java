package com.authservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GoogleOidcProperties.class)
public class GoogleOidcConfiguration {

    private final GoogleOidcProperties properties;

    public GoogleOidcConfiguration(GoogleOidcProperties properties) {
        this.properties = properties;
    }

    public GoogleOidcProperties getProperties() {
        return properties;
    }
}

