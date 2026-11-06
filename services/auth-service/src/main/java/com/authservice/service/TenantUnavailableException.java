package com.authservice.service;

public class TenantUnavailableException extends RuntimeException {
    public TenantUnavailableException(String message) {
        super(message);
    }
}

