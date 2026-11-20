package com.authservice.service;

import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.ExternalIdentity;
import com.authservice.persistence.ExternalIdentityRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalIdentityServiceTests {

    private ExternalIdentityRepository externalIdentityRepository;
    private UserIdentityRepository userIdentityRepository;
    private ExternalIdentityService externalIdentityService;

    @BeforeEach
    void setUp() {
        externalIdentityRepository = mock(ExternalIdentityRepository.class);
        userIdentityRepository = mock(UserIdentityRepository.class);
        externalIdentityService = new ExternalIdentityService(
                externalIdentityRepository,
                userIdentityRepository);
    }

    @Test
    void authenticatesFromProviderSubjectAndGlobalIdentityOnly() {
        UUID globalUserId = UUID.randomUUID();
        String subject = "google-subject-global";
        ExternalIdentity externalIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .globalUserId(globalUserId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject(subject)
                .build();
        UserIdentity globalIdentity = verifiedIdentity(globalUserId);
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, subject))
                .thenReturn(Optional.of(externalIdentity));
        when(userIdentityRepository.findById(globalUserId)).thenReturn(Optional.of(globalIdentity));

        var authenticated = externalIdentityService.authenticateGoogleIdentity(subject);

        assertThat(authenticated.globalUserId()).isEqualTo(globalUserId);
        assertThat(authenticated.email()).isEqualTo("user@example.com");
        verify(userIdentityRepository).findById(globalUserId);
    }

    @Test
    void rejectsUnmappedProviderSubjectWithoutEmailBasedLinking() {
        String subject = "unmapped-google-subject";
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, subject))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> externalIdentityService.authenticateGoogleIdentity(subject))
                .isInstanceOf(ExternalIdentityNotLinkedException.class)
                .hasMessageContaining("not linked");

        verify(userIdentityRepository, never()).findByNormalizedEmail(any());
        verify(externalIdentityRepository, never()).save(any());
    }

    @Test
    void rejectsLegacyProviderMappingWithoutGlobalIdentity() {
        ExternalIdentity legacyIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject("legacy-google-subject")
                .build();
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, "legacy-google-subject"))
                .thenReturn(Optional.of(legacyIdentity));

        assertThatThrownBy(() -> externalIdentityService.authenticateGoogleIdentity("legacy-google-subject"))
                .isInstanceOf(ExternalIdentityNotLinkedException.class)
                .hasMessageContaining("relinking");

        verify(userIdentityRepository, never()).findById(any());
    }

    @Test
    void refusesToAuthenticateUnavailableGlobalIdentity() {
        UUID globalUserId = UUID.randomUUID();
        ExternalIdentity externalIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .globalUserId(globalUserId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject("disabled-google-subject")
                .build();
        UserIdentity disabledIdentity = verifiedIdentity(globalUserId);
        disabledIdentity.setStatus(IdentityStatus.DISABLED);
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, externalIdentity.getProviderSubject()))
                .thenReturn(Optional.of(externalIdentity));
        when(userIdentityRepository.findById(globalUserId)).thenReturn(Optional.of(disabledIdentity));

        assertThatThrownBy(() -> externalIdentityService.authenticateGoogleIdentity(
                externalIdentity.getProviderSubject()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void linksOnlyVerifiedGlobalIdentities() {
        UUID globalUserId = UUID.randomUUID();
        when(userIdentityRepository.findById(globalUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> externalIdentityService.linkIdentity(
                globalUserId,
                ExternalIdentityService.PROVIDER_GOOGLE,
                "new-google-subject",
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");

        verify(externalIdentityRepository, never()).save(any());
    }

    @Test
    void refusesToLinkProviderSubjectAlreadyOwnedByAnotherGlobalIdentity() {
        UUID globalUserId = UUID.randomUUID();
        UUID existingUserId = UUID.randomUUID();
        UserIdentity identity = verifiedIdentity(globalUserId);
        ExternalIdentity existing = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .globalUserId(existingUserId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject("already-linked")
                .build();
        when(userIdentityRepository.findById(globalUserId)).thenReturn(Optional.of(identity));
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, "already-linked"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> externalIdentityService.linkIdentity(
                globalUserId, ExternalIdentityService.PROVIDER_GOOGLE, "already-linked", true))
                .isInstanceOf(ExternalIdentityConflictException.class)
                .hasMessageContaining("different global identity");

        verify(externalIdentityRepository, never()).save(any());
    }

    @Test
    void linkingSameProviderSubjectToSameGlobalIdentityIsIdempotent() {
        UUID globalUserId = UUID.randomUUID();
        UserIdentity identity = verifiedIdentity(globalUserId);
        ExternalIdentity existing = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .globalUserId(globalUserId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject("existing-link")
                .build();
        when(userIdentityRepository.findById(globalUserId)).thenReturn(Optional.of(identity));
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, "existing-link"))
                .thenReturn(Optional.of(existing));

        ExternalIdentity result = externalIdentityService.linkIdentity(
                globalUserId, ExternalIdentityService.PROVIDER_GOOGLE, "existing-link", true);

        assertThat(result).isSameAs(existing);
        verify(externalIdentityRepository, never()).save(any());
    }

    @Test
    void linksNewGoogleSubjectToVerifiedGlobalIdentity() {
        UUID globalUserId = UUID.randomUUID();
        UserIdentity identity = verifiedIdentity(globalUserId);
        when(userIdentityRepository.findById(globalUserId)).thenReturn(Optional.of(identity));
        when(externalIdentityRepository.findByProviderAndProviderSubject(
                ExternalIdentityService.PROVIDER_GOOGLE, "new-google-subject"))
                .thenReturn(Optional.empty());
        when(externalIdentityRepository.save(any(ExternalIdentity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExternalIdentity linked = externalIdentityService.linkIdentity(
                globalUserId,
                ExternalIdentityService.PROVIDER_GOOGLE,
                "new-google-subject",
                true);

        assertThat(linked.getGlobalUserId()).isEqualTo(globalUserId);
        assertThat(linked.getProviderSubject()).isEqualTo("new-google-subject");
        verify(externalIdentityRepository).save(any(ExternalIdentity.class));
    }

    private static UserIdentity verifiedIdentity(UUID id) {
        Instant now = Instant.now();
        return UserIdentity.builder()
                .id(id)
                .email("user@example.com")
                .normalizedEmail("user@example.com")
                .passwordHash("unused")
                .status(IdentityStatus.VERIFIED)
                .createdAt(now)
                .updatedAt(now)
                .emailVerifiedAt(now)
                .build();
    }
}
