package com.authservice.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmailAddress(String raw, String normalized) {

    private static final Pattern BASIC_EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public EmailAddress {
        Objects.requireNonNull(raw, "Email raw value must not be null");
        Objects.requireNonNull(normalized, "Email normalized value must not be null");
    }

    public static EmailAddress of(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email address cannot be blank");
        }
        String trimmed = email.trim();
        if (!BASIC_EMAIL_PATTERN.matcher(trimmed).matches() || trimmed.length() > 320) {
            throw new IllegalArgumentException("Invalid email address format: " + trimmed);
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return new EmailAddress(trimmed, normalized);
    }

    public static String normalize(String email) {
        return of(email).normalized();
    }
}

