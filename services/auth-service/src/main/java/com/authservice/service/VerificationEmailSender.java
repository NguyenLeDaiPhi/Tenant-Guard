package com.authservice.service;

public interface VerificationEmailSender {
    void sendVerificationCode(String email, String code);
}
