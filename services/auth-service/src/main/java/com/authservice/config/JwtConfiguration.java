package com.authservice.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    private final JwtProperties properties;
    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final RSAKey rsaKey;

    public JwtConfiguration(JwtProperties properties) {
        this.properties = properties;

        String privBase64 = properties.privateKeyBase64();
        String pubBase64 = properties.publicKeyBase64();

        if (privBase64 == null || privBase64.isBlank() || pubBase64 == null || pubBase64.isBlank()) {
            throw new IllegalStateException("RSA private and public keys must be configured via auth.jwt.private-key-base64 and auth.jwt.public-key-base64");
        }

        try {
            byte[] privBytes = Base64.getDecoder().decode(privBase64.trim());
            byte[] pubBytes = Base64.getDecoder().decode(pubBase64.trim());

            KeyFactory kf = KeyFactory.getInstance("RSA");
            this.privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            this.publicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(pubBytes));
        } catch (Exception e) {
            throw new IllegalStateException("Malformed RSA key configuration", e);
        }

        if (this.privateKey.getModulus().bitLength() < 2048 || this.publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("RSA key pair must be at least 2048 bits");
        }

        if (!this.privateKey.getModulus().equals(this.publicKey.getModulus())) {
            throw new IllegalStateException("RSA private and public key pair mismatch");
        }

        this.rsaKey = new RSAKey.Builder(this.publicKey)
                .privateKey(this.privateKey)
                .keyID(properties.keyId())
                .build();
    }

    @Bean
    public RSAPrivateKey rsaPrivateKey() {
        return this.privateKey;
    }

    @Bean
    public RSAPublicKey rsaPublicKey() {
        return this.publicKey;
    }

    @Bean
    public RSAKey rsaKey() {
        return this.rsaKey;
    }

    @Bean
    public JWKSet jwkSet() {
        return new JWKSet(this.rsaKey.toPublicJWK());
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(this.rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(this.publicKey).build();
    }
}

