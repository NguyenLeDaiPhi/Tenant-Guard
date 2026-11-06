package com.authservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record LoginRequest(
        UUID tenantId,
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Invalid email format")
        String email,
        @NotBlank(message = "Password must not be blank")
        String password
) {}
