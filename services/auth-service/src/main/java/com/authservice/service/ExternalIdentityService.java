package com.authservice.service;

import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.persistence.ExternalIdentity;
import com.authservice.persistence.ExternalIdentityRepository;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExternalIdentityService {

    public static final String PROVIDER_GOOGLE = "GOOGLE";

    private final ExternalIdentityRepository externalIdentityRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantStatusService tenantStatusService;

    public ExternalIdentityService(
            ExternalIdentityRepository externalIdentityRepository,
            UserAccountRepository userAccountRepository,
            TenantStatusService tenantStatusService) {
        this.externalIdentityRepository = externalIdentityRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantStatusService = tenantStatusService;
    }

    @Transactional(readOnly = true)
    public AuthenticatedIdentity authenticateGoogleIdentity(String providerSubject) {
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("Provider subject must not be blank");
        }

        ExternalIdentity identity = externalIdentityRepository
                .findByProviderAndProviderSubject(PROVIDER_GOOGLE, providerSubject)
                .orElseThrow(() -> new ExternalIdentityNotLinkedException(
                        "Google identity is not linked to any user account"));

        UserAccount user = userAccountRepository.findById(identity.getUserId())
                .orElseThrow(() -> new ExternalIdentityNotLinkedException(
                        "Associated user account no longer exists"));

        Instant now = Instant.now();
        if (!user.isAvailable(now)) {
            throw new IllegalStateException("Associated user account is unavailable");
        }

        tenantStatusService.validateTenantActive(user.getTenantId());

        return new AuthenticatedIdentity(user.getId(), user.getTenantId(), user.getRole(), user.getEmail());
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

        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User does not exist: " + userId));

        Optional<ExternalIdentity> existingOpt = externalIdentityRepository
                .findByProviderAndProviderSubject(provider, providerSubject);

        if (existingOpt.isPresent()) {
            ExternalIdentity existing = existingOpt.get();
            if (!existing.getUserId().equals(userId)) {
                throw new ExternalIdentityConflictException(
                        "External identity is already linked to a different account");
            }
            // Idempotent: same identity linked to the same account
            return existing;
        }

        ExternalIdentity newIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .provider(provider)
                .providerSubject(providerSubject)
                .emailVerified(emailVerified)
                .linkedAt(Instant.now())
                .build();

        return externalIdentityRepository.save(newIdentity);
    }
}

