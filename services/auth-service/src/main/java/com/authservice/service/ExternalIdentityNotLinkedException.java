package com.authservice.service;

public class ExternalIdentityNotLinkedException extends RuntimeException {
    public ExternalIdentityNotLinkedException(String message) {
        super(message);
    }
}

