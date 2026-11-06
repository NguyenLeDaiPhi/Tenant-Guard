package com.authservice.service;

import java.nio.charset.StandardCharsets;

final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_BCRYPT_BYTES = 72;

    private PasswordPolicy() {
    }

    static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new IllegalArgumentException(
                    "Password exceeds maximum allowed byte length for BCrypt (" + MAX_BCRYPT_BYTES + " bytes)"
            );
        }
    }
}
