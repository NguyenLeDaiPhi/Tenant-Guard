package com.authservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Invalid email format")
        @Size(max = 320, message = "Email must not exceed 320 characters")
        String email,
        @NotBlank(message = "Password must not be blank")
        @Size(min = 12, message = "Password must be at least 12 characters")
        String password
) {}
