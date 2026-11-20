package com.tenant.service.membership.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

@Component
public class TenantInvitationEmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String acceptUrl;

    public TenantInvitationEmailSender(
            JavaMailSender mailSender,
            @Value("${tenant.email.from:no-reply@tenantguard.local}") String fromAddress,
            @Value("${tenant.invitation.accept-url:http://localhost:3000/invitations/accept}") String acceptUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.acceptUrl = acceptUrl;
    }

    public void send(InvitationEmail invitation) {
        String url = UriComponentsBuilder.fromUriString(acceptUrl)
                .queryParam("token", invitation.token())
                .build()
                .toUriString();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(invitation.recipient());
        message.setSubject("Invitation to join " + invitation.tenantName());
        message.setText("""
                You have been invited to join %s as a %s.

                Accept the invitation before %s:
                %s
                """.formatted(
                invitation.tenantName(),
                invitation.role(),
                invitation.expiresAt(),
                url));
        mailSender.send(message);
    }

    public record InvitationEmail(
            String recipient,
            String tenantName,
            String role,
            String token,
            Instant expiresAt) {
    }
}
