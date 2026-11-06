package com.authservice.service;

import com.authservice.config.JwtProperties;
import com.authservice.domain.AuthenticatedIdentity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public record IssuedAccessToken(String token, Duration expiresIn) {}

    public AccessTokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedAccessToken generateAccessToken(AuthenticatedIdentity identity) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(identity.userId().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim("roles", identity.role().name())
                .claim("email", identity.email());

        if (identity.tenantId() != null) {
            claimsBuilder.claim("tid", identity.tenantId().toString());
        }

        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId())
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claimsBuilder.build()))
                .getTokenValue();

        return new IssuedAccessToken(token, properties.accessTokenTtl());
    }
}
