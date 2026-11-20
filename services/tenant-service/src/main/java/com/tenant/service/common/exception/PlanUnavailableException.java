package com.tenant.service.common.exception;

public class PlanUnavailableException extends RuntimeException {

    public PlanUnavailableException(String code) {
        super("Subscription plan is not available: " + code);
    }
}
