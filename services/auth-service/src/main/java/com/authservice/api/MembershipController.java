package com.authservice.api;

import com.authservice.api.dto.CreateMembershipRequest;
import com.authservice.api.dto.MembershipResponse;
import com.authservice.domain.UserRole;
import com.authservice.persistence.TenantMembership;
import com.authservice.service.MembershipService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth/memberships")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping
    public ResponseEntity<MembershipResponse> createMembership(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateMembershipRequest request) {
        UUID creatorId = UUID.fromString(jwt.getSubject());
        UserRole creatorRole = UserRole.valueOf(jwt.getClaimAsString("roles"));
        UUID creatorTenantId = jwt.getClaimAsString("tid") == null
                ? null
                : UUID.fromString(jwt.getClaimAsString("tid"));

        TenantMembership membership = membershipService.createPendingMembership(
                creatorId,
                creatorRole,
                creatorTenantId,
                request.tenantId(),
                request.email(),
                request.role()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(new MembershipResponse(
                membership.getId(),
                membership.getTenantId(),
                membership.getNormalizedEmail(),
                membership.getRole(),
                membership.getStatus()
        ));
    }
}
