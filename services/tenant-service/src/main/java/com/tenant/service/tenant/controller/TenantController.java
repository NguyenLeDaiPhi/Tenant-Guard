package com.tenant.service.tenant.controller;

import com.tenant.service.security.CurrentUserProvider;
import com.tenant.service.tenant.dto.CreateTenantRequest;
import com.tenant.service.tenant.dto.TenantResponse;
import com.tenant.service.tenant.dto.UpdateTenantRequest;
import com.tenant.service.tenant.service.TenantApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantApplicationService tenantService;
    private final CurrentUserProvider currentUserProvider;

    public TenantController(
            TenantApplicationService tenantService,
            CurrentUserProvider currentUserProvider) {
        this.tenantService = tenantService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(
            @Valid @RequestBody CreateTenantRequest request,
            UriComponentsBuilder uriBuilder) {
        TenantResponse created = tenantService.create(currentUserProvider.userId(), request);
        return ResponseEntity.created(uriBuilder.path("/api/v1/tenants/{id}")
                        .buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @GetMapping
    public List<TenantResponse> list() {
        return tenantService.list(currentUserProvider.userId());
    }

    @GetMapping("/{tenantId}")
    public TenantResponse get(@PathVariable UUID tenantId) {
        return tenantService.get(tenantId, currentUserProvider.userId());
    }

    @PatchMapping("/{tenantId}")
    public TenantResponse update(
            @PathVariable UUID tenantId,
            @Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.updateName(tenantId, currentUserProvider.userId(), request.name());
    }
}
