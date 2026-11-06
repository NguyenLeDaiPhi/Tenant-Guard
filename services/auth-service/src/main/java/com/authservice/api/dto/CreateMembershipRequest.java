package com.authservice.api.dto;

import com.authservice.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateMembershipRequest(
        @NotNull(message = "tenantId must not be null")
        UUID tenantId,
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Invalid email format")
        String email,
        @NotNull(message = "Role must not be null")
        UserRole role
) {}
