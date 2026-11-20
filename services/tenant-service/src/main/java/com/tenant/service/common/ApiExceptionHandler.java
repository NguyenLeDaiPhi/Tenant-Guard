package com.tenant.service.common;

import com.tenant.service.common.exception.DuplicateTenantSlugException;
import com.tenant.service.common.exception.ConflictException;
import com.tenant.service.common.exception.PlanUnavailableException;
import com.tenant.service.common.exception.ResourceNotFoundException;
import org.springframework.mail.MailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return response(HttpStatus.NOT_FOUND, "Resource not found.");
    }

    @ExceptionHandler(DuplicateTenantSlugException.class)
    ResponseEntity<ApiError> duplicateSlug() {
        return response(HttpStatus.CONFLICT, "Tenant slug is already in use.");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(PlanUnavailableException.class)
    ResponseEntity<ApiError> unavailablePlan(PlanUnavailableException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden() {
        return response(HttpStatus.FORBIDDEN, "Tenant OWNER membership is required.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidRequest() {
        return response(HttpStatus.BAD_REQUEST, "Request validation failed.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidArgument(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MailException.class)
    ResponseEntity<ApiError> invitationEmailFailure() {
        return response(HttpStatus.BAD_GATEWAY, "Invitation email could not be delivered.");
    }

    private static ResponseEntity<ApiError> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message, Instant.now()));
    }
}
