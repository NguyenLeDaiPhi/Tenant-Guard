package com.tenant.service.membership.controller;

import com.tenant.service.membership.dto.CreateInvitationRequest;
import com.tenant.service.membership.dto.InvitationResponse;
import com.tenant.service.membership.service.TenantInvitationService;
import com.tenant.service.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/invitations")
public class TenantInvitationController {

    private final TenantInvitationService invitationService;
    private final CurrentUserProvider currentUserProvider;

    public TenantInvitationController(
            TenantInvitationService invitationService,
            CurrentUserProvider currentUserProvider) {
        this.invitationService = invitationService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<InvitationResponse> create(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreateInvitationRequest request,
            UriComponentsBuilder uriBuilder) {
        InvitationResponse created = invitationService.create(
                tenantId,
                currentUserProvider.userId(),
                request.email(),
                request.role());
        return ResponseEntity.created(uriBuilder
                        .path("/api/v1/tenants/{tenantId}/invitations/{id}")
                        .buildAndExpand(tenantId, created.id())
                        .toUri())
                .body(created);
    }

    @GetMapping
    public List<InvitationResponse> list(@PathVariable UUID tenantId) {
        return invitationService.list(tenantId, currentUserProvider.userId());
    }

    @DeleteMapping("/{invitationId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID invitationId) {
        invitationService.revoke(tenantId, invitationId, currentUserProvider.userId());
        return ResponseEntity.noContent().build();
    }
}
