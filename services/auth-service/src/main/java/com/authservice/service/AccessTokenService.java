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
import java.util.UUID;

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
        return generateGlobalAccessToken(identity.globalUserId(), identity.email());
    }

    public IssuedAccessToken generateGlobalAccessToken(UUID globalUserId, String email) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(globalUserId.toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim("global_user_id", globalUserId.toString())
                .claim("email", email)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId())
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims))
                .getTokenValue();

        return new IssuedAccessToken(token, properties.accessTokenTtl());
    }
}
