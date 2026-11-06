package com.authservice.config;

import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.domain.UserRole;
import com.authservice.service.AccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenTests {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void issuesShortLivedRs256TokenAndRejectsTampering() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        String privBase64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        JwtProperties props = new JwtProperties(
                "http://localhost:8081",
                "tenantguard-api",
                Duration.ofMinutes(10),
                "tenantguard-test-kid",
                privBase64,
                pubBase64
        );

        JwtConfiguration config = new JwtConfiguration(props);
        AccessTokenService tokenService = new AccessTokenService(config.jwtEncoder(), props);
        JwtDecoder decoder = config.jwtDecoder();

        UUID userId = UUID.randomUUID();
        AuthenticatedIdentity identity = new AuthenticatedIdentity(userId, TENANT_ID, UserRole.ORDER_MANAGER, "user@example.com");

        AccessTokenService.IssuedAccessToken issued = tokenService.generateAccessToken(identity);
        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(10));

        // Decode and verify claims
        Jwt jwt = decoder.decode(issued.token());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("roles")).isEqualTo("ORDER_MANAGER");
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(TENANT_ID.toString());
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:8081");
        assertThat(jwt.getAudience()).contains("tenantguard-api");

        // Verify tampering is rejected
        String[] parts = issued.token().split("\\.");
        assertThat(parts).hasSize(3);

        // Tamper signature
        String tamperedSignature = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "AAAA";
        assertThatThrownBy(() -> decoder.decode(tamperedSignature))
                .isInstanceOf(BadJwtException.class);
    }
}
