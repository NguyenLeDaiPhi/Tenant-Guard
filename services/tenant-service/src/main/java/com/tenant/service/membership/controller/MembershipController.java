package com.tenant.service.membership.controller;

import com.tenant.service.membership.dto.MembershipResponse;
import com.tenant.service.membership.service.MembershipService;
import com.tenant.service.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/memberships")
public class MembershipController {

    private final MembershipService membershipService;
    private final CurrentUserProvider currentUserProvider;

    public MembershipController(
            MembershipService membershipService,
            CurrentUserProvider currentUserProvider) {
        this.membershipService = membershipService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<MembershipResponse> listMine() {
        return membershipService.listActiveMemberships(currentUserProvider.userId());
    }
}
