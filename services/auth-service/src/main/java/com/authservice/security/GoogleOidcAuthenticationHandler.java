package com.authservice.security;

import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.service.AccessTokenService;
import com.authservice.service.ExternalIdentityNotLinkedException;
import com.authservice.service.ExternalIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class GoogleOidcAuthenticationHandler implements AuthenticationSuccessHandler {

    private final ExternalIdentityService externalIdentityService;
    private final AccessTokenService accessTokenService;

    public GoogleOidcAuthenticationHandler(
            ExternalIdentityService externalIdentityService,
            AccessTokenService accessTokenService) {
        this.externalIdentityService = externalIdentityService;
        this.accessTokenService = accessTokenService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        String subject = null;
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            subject = oidcUser.getSubject();
        } else if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
            subject = oAuth2User.getAttribute("sub");
        }

        if (subject == null || subject.isBlank()) {
            sendError(response, request.getRequestURI(), HttpStatus.BAD_REQUEST, "Missing provider subject from Google");
            return;
        }

        try {
            AuthenticatedIdentity identity = externalIdentityService.authenticateGoogleIdentity(subject);
            AccessTokenService.IssuedAccessToken issuedToken = accessTokenService.generateAccessToken(identity);

            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            String json = """
                    {"accessToken":"%s","tokenType":"Bearer","expiresIn":%d}"""
                    .formatted(issuedToken.token(), issuedToken.expiresIn().toSeconds());

            response.getWriter().write(json);
        } catch (ExternalIdentityNotLinkedException e) {
            sendError(response, request.getRequestURI(), HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (Exception e) {
            sendError(response, request.getRequestURI(), HttpStatus.UNAUTHORIZED, "Authentication failed: " + e.getMessage());
        }
    }

    private void sendError(HttpServletResponse response, String path, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String json = """
                {"timestamp":"%s","status":%d,"error":"%s","message":"%s","path":"%s"}"""
                .formatted(Instant.now(), status.value(), status.getReasonPhrase(), escapeJson(message), escapeJson(path));
        response.getWriter().write(json);
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\"", "\\\"");
    }
}
