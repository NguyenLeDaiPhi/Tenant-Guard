package com.authservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class VerificationCodeHasher {

    private VerificationCodeHasher() {}

    public static String hash(String code) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(code.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static boolean matches(String expectedHash, String code) {
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                hash(code).getBytes(StandardCharsets.US_ASCII)
        );
    }
}
