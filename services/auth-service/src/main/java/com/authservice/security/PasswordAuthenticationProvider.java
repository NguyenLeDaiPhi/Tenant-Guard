package com.authservice.security;

import com.authservice.domain.EmailAddress;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import com.authservice.service.TenantStatusService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private static final String GENERIC_ERROR_MESSAGE = "Invalid credentials or account unavailable";

    private final UserAccountRepository userAccountRepository;
    private final TenantStatusService tenantStatusService;
    private final PasswordEncoder passwordEncoder;

    public PasswordAuthenticationProvider(
            UserAccountRepository userAccountRepository,
            TenantStatusService tenantStatusService,
            PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.tenantStatusService = tenantStatusService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof PasswordAuthenticationToken token)) {
            return null;
        }

        UUID tenantId = token.getTenantId();
        String rawEmail = token.getEmail();
        String rawPassword = token.getCredentials() != null ? token.getCredentials().toString() : "";

        // Normalize email
        String normalizedEmail;
        try {
            normalizedEmail = EmailAddress.normalize(rawEmail);
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        Instant now = Instant.now();

        // 1. Verify tenant status if tenant-scoped
        if (tenantId != null && !tenantStatusService.isTenantActive(tenantId)) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        // 2. Lookup user account
        Optional<UserAccount> userOpt = (tenantId == null)
                ? userAccountRepository.findByNormalizedEmailAndTenantIdIsNull(normalizedEmail)
                : userAccountRepository.findByTenantIdAndNormalizedEmail(tenantId, normalizedEmail);

        if (userOpt.isEmpty()) {
            // Mitigate timing attacks by performing dummy password check
            passwordEncoder.matches(rawPassword, "$2a$10$w0uQo0UoV7cI/8LpQGkE7.4FpBqY2tqR9rL1Nq3XQJ4H5E9F8b9zW");
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        UserAccount user = userOpt.get();

        // 3. Check account availability (status and lock)
        if (!user.isAvailable(now)) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        // 4. Verify password
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedLogin(now);
            userAccountRepository.save(user);
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        // 5. Success: reset counters and update login timestamp
        user.recordSuccessfulLogin(now);
        userAccountRepository.save(user);

        UserAccountPrincipal principal = new UserAccountPrincipal(user);
        return new PasswordAuthenticationToken(
                principal,
                null,
                tenantId,
                user.getEmail(),
                principal.getAuthorities()
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
