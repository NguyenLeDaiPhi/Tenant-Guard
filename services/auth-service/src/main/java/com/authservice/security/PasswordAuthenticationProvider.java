package com.authservice.security;

import com.authservice.domain.EmailAddress;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private static final String GENERIC_ERROR_MESSAGE = "Invalid credentials or account unavailable";

    private final UserIdentityRepository userIdentityRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordAuthenticationProvider(
            UserIdentityRepository userIdentityRepository,
            PasswordEncoder passwordEncoder) {
        this.userIdentityRepository = userIdentityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof PasswordAuthenticationToken token)) {
            return null;
        }

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

        Optional<UserIdentity> userOpt = userIdentityRepository.findByNormalizedEmailForUpdate(normalizedEmail);

        if (userOpt.isEmpty()) {
            // Mitigate timing attacks by performing dummy password check
            passwordEncoder.matches(rawPassword, "$2a$10$w0uQo0UoV7cI/8LpQGkE7.4FpBqY2tqR9rL1Nq3XQJ4H5E9F8b9zW");
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        UserIdentity user = userOpt.get();

        if (!user.isAvailable(now)) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedLogin(now);
            userIdentityRepository.save(user);
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }

        user.recordSuccessfulLogin(now);
        userIdentityRepository.save(user);

        GlobalUserPrincipal principal = new GlobalUserPrincipal(user.getId(), user.getEmail());
        return new PasswordAuthenticationToken(
                principal,
                null,
                user.getEmail(),
                List.of()
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
