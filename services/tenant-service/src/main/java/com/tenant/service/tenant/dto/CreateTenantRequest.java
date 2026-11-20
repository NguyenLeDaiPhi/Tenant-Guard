package com.tenant.service.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank
        @Size(max = 200)
        String name,
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
        String slug,
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,31}$")
        String subscriptionPlanCode) {
}
