package com.tenant.service.membership.controller;

import com.tenant.service.membership.dto.MembershipResponse;
import com.tenant.service.membership.service.MembershipService;
import com.tenant.service.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/memberships")
public class TenantMembershipController {

    private final MembershipService membershipService;
    private final CurrentUserProvider currentUserProvider;

    public TenantMembershipController(
            MembershipService membershipService,
            CurrentUserProvider currentUserProvider) {
        this.membershipService = membershipService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<MembershipResponse> listTenantMemberships(@PathVariable UUID tenantId) {
        return membershipService.listTenantMemberships(tenantId, currentUserProvider.userId());
    }
}
