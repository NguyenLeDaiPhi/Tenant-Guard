package com.authservice.api;

import com.authservice.persistence.ExternalIdentity;
import com.authservice.service.ExternalIdentityService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/external-identities")
public class GoogleIdentityController {

    private final ExternalIdentityService externalIdentityService;

    public record LinkGoogleRequest(
            @NotBlank(message = "Provider subject must not be blank")
            String providerSubject,
            Boolean emailVerified
    ) {}

    public record ExternalIdentityResponse(
            UUID id,
            UUID userId,
            String provider,
            String providerSubject,
            boolean emailVerified,
            Instant linkedAt
    ) {}

    public GoogleIdentityController(ExternalIdentityService externalIdentityService) {
        this.externalIdentityService = externalIdentityService;
    }

    @PostMapping("/google")
    public ResponseEntity<ExternalIdentityResponse> linkGoogleIdentity(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LinkGoogleRequest request) {

        if (jwt == null) {
            throw new AccessDeniedException("Authentication required");
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        boolean emailVerified = request.emailVerified() != null && request.emailVerified();

        ExternalIdentity identity = externalIdentityService.linkIdentity(
                userId,
                ExternalIdentityService.PROVIDER_GOOGLE,
                request.providerSubject(),
                emailVerified
        );

        ExternalIdentityResponse response = new ExternalIdentityResponse(
                identity.getId(),
                identity.getUserId(),
                identity.getProvider(),
                identity.getProviderSubject(),
                identity.isEmailVerified(),
                identity.getLinkedAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

