package com.authservice.api.dto;

public record RegistrationAcceptedResponse(String message) {
    public static RegistrationAcceptedResponse generic() {
        return new RegistrationAcceptedResponse(
                "If this email is eligible, a verification code will be sent."
        );
    }
}
