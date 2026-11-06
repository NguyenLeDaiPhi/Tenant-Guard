package com.authservice.security;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.domain.UserRole;
import com.authservice.persistence.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class UserAccountPrincipal implements UserDetails {

    private final UUID id;
    private final UUID tenantId;
    private final String email;
    private final String passwordHash;
    private final UserRole role;
    private final AccountStatus status;
    private final Instant lockedUntil;

    public UserAccountPrincipal(UserAccount account) {
        this.id = account.getId();
        this.tenantId = account.getTenantId();
        this.email = account.getEmail();
        this.passwordHash = account.getPasswordHash();
        this.role = account.getRole();
        this.status = account.getStatus();
        this.lockedUntil = account.getLockedUntil();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UserRole getRole() {
        return role;
    }

    public AuthenticatedIdentity toAuthenticatedIdentity() {
        return new AuthenticatedIdentity(id, tenantId, role, email);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        if (status == AccountStatus.LOCKED) {
            return lockedUntil != null && Instant.now().isAfter(lockedUntil);
        }
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status != AccountStatus.DISABLED && status != AccountStatus.PENDING_EMAIL;
    }
}
