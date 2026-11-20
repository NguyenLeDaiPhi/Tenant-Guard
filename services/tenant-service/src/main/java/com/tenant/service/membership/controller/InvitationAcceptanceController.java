package com.tenant.service.membership.controller;

import com.tenant.service.membership.dto.AcceptInvitationRequest;
import com.tenant.service.membership.dto.MembershipResponse;
import com.tenant.service.membership.service.TenantInvitationService;
import com.tenant.service.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationAcceptanceController {

    private final TenantInvitationService invitationService;
    private final CurrentUserProvider currentUserProvider;

    public InvitationAcceptanceController(
            TenantInvitationService invitationService,
            CurrentUserProvider currentUserProvider) {
        this.invitationService = invitationService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/accept")
    public MembershipResponse accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return invitationService.accept(
                request.token(),
                currentUserProvider.userId(),
                currentUserProvider.email());
    }
}
