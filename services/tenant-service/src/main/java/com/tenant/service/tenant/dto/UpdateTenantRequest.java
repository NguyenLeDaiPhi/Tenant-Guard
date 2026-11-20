package com.tenant.service.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTenantRequest(
        @NotBlank
        @Size(max = 200)
        String name) {
}
