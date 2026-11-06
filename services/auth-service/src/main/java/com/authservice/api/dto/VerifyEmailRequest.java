package com.authservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Invalid email format")
        String email,
        @NotBlank(message = "Verification code must not be blank")
        @Pattern(regexp = "[0-9a-fA-F]{64}", message = "Invalid verification code format")
        String code
) {}
