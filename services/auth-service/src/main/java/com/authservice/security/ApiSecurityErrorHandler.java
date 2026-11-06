package com.authservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String json = """
                {"timestamp":"%s","status":401,"error":"Unauthorized","message":"Authentication required or token invalid","path":"%s"}"""
                .formatted(Instant.now(), escapeJson(request.getRequestURI()));

        response.getWriter().write(json);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String message = accessDeniedException.getMessage() != null
                ? accessDeniedException.getMessage()
                : "Access denied";

        String json = """
                {"timestamp":"%s","status":403,"error":"Forbidden","message":"%s","path":"%s"}"""
                .formatted(Instant.now(), escapeJson(message), escapeJson(request.getRequestURI()));

        response.getWriter().write(json);
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\"", "\\\"");
    }
}
