package com.authservice.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class PasswordAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private Object credentials;
    private final String email;

    // Unauthenticated token
    public PasswordAuthenticationToken(String email, Object credentials) {
        super((Collection<? extends GrantedAuthority>) null);
        this.email = email;
        this.credentials = credentials;
        this.principal = email;
        setAuthenticated(false);
    }

    // Authenticated token
    public PasswordAuthenticationToken(
            Object principal,
            Object credentials,
            String email,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.credentials = credentials;
        this.email = email;
        super.setAuthenticated(true);
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
