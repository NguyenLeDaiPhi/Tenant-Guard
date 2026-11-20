package com.authservice.config;

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

    @Test
    void issuesGlobalShortLivedRs256TokenAndRejectsTampering() throws Exception {
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

        UUID globalUserId = UUID.randomUUID();
        AccessTokenService.IssuedAccessToken issued =
                tokenService.generateGlobalAccessToken(globalUserId, "user@example.com");
        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(10));

        // Decode and verify claims
        Jwt jwt = decoder.decode(issued.token());
        assertThat(jwt.getSubject()).isEqualTo(globalUserId.toString());
        assertThat(jwt.getClaimAsString("global_user_id")).isEqualTo(globalUserId.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("user@example.com");
        assertThat(jwt.getClaims()).doesNotContainKeys("roles", "tid");
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
