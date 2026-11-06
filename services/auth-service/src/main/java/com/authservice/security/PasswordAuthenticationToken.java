package com.authservice.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

public class PasswordAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private Object credentials;
    private final UUID tenantId;
    private final String email;

    // Unauthenticated token
    public PasswordAuthenticationToken(UUID tenantId, String email, Object credentials) {
        super((Collection<? extends GrantedAuthority>) null);
        this.tenantId = tenantId;
        this.email = email;
        this.credentials = credentials;
        this.principal = email;
        setAuthenticated(false);
    }

    // Authenticated token
    public PasswordAuthenticationToken(Object principal, Object credentials, UUID tenantId, String email,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.credentials = credentials;
        this.tenantId = tenantId;
        this.email = email;
        super.setAuthenticated(true);
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        this.credentials = null;
    }
}
