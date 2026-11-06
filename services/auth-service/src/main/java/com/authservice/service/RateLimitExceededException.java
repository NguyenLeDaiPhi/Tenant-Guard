package com.authservice.service;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Too many requests. Please try again later.");
    }
}
