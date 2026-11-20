package com.authservice.service;

import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.persistence.ExternalIdentity;
import com.authservice.persistence.ExternalIdentityRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ExternalIdentityService {

    public static final String PROVIDER_GOOGLE = "GOOGLE";

    private final ExternalIdentityRepository externalIdentityRepository;
    private final UserIdentityRepository userIdentityRepository;

    public ExternalIdentityService(
            ExternalIdentityRepository externalIdentityRepository,
            UserIdentityRepository userIdentityRepository) {
        this.externalIdentityRepository = externalIdentityRepository;
        this.userIdentityRepository = userIdentityRepository;
    }

    @Transactional(readOnly = true)
    public AuthenticatedIdentity authenticateGoogleIdentity(String providerSubject) {
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("Provider subject must not be blank");
        }

        ExternalIdentity identity = externalIdentityRepository
                .findByProviderAndProviderSubject(PROVIDER_GOOGLE, providerSubject)
                .orElseThrow(() -> new ExternalIdentityNotLinkedException(
                        "Google identity is not linked to any global user identity"));
        if (identity.getGlobalUserId() == null) {
            throw new ExternalIdentityNotLinkedException(
                    "Google identity requires relinking to a global user identity");
        }

        Instant now = Instant.now();
        UserIdentity globalIdentity = userIdentityRepository.findById(identity.getGlobalUserId())
                .orElseThrow(() -> new ExternalIdentityNotLinkedException(
                        "Associated global identity no longer exists"));
        if (!globalIdentity.isAvailable(now)) {
            throw new IllegalStateException("Associated global identity is unavailable");
        }
        return new AuthenticatedIdentity(globalIdentity.getId(), globalIdentity.getEmail());
    }

    @Transactional
    public ExternalIdentity linkIdentity(UUID userId, String provider, String providerSubject, boolean emailVerified) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider must not be blank");
        }
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("Provider subject must not be blank");
        }

        UserIdentity globalIdentity = userIdentityRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Global identity does not exist: " + userId));
        if (!globalIdentity.isVerified()) {
            throw new IllegalArgumentException("Global identity must be verified before linking");
        }

        var existingOpt = externalIdentityRepository
                .findByProviderAndProviderSubject(provider, providerSubject);

        if (existingOpt.isPresent()) {
            ExternalIdentity existing = existingOpt.get();
            if (!userId.equals(existing.getGlobalUserId())) {
                throw new ExternalIdentityConflictException(
                        "External identity is already linked to a different global identity");
            }
            return existing;
        }

        ExternalIdentity newIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .globalUserId(globalIdentity.getId())
                .provider(provider)
                .providerSubject(providerSubject)
                .emailVerified(emailVerified)
                .linkedAt(Instant.now())
                .build();

        return externalIdentityRepository.save(newIdentity);
    }
}

