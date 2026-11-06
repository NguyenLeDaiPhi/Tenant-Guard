package com.authservice.api.dto;

public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {}

