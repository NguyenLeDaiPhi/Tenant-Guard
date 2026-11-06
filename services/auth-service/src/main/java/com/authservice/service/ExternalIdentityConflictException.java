package com.authservice.service;

public class ExternalIdentityConflictException extends RuntimeException {
    public ExternalIdentityConflictException(String message) {
        super(message);
    }
}

