package com.authservice.api;

import com.authservice.api.dto.AccessTokenResponse;
import com.authservice.api.dto.LoginRequest;
import com.authservice.api.dto.RegisterRequest;
import com.authservice.api.dto.RegistrationAcceptedResponse;
import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.security.PasswordAuthenticationToken;
import com.authservice.security.UserAccountPrincipal;
import com.authservice.service.AccessTokenService;
import com.authservice.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AccessTokenService accessTokenService;
    private final RegistrationService registrationService;

    public AuthController(
            @Lazy AuthenticationManager authenticationManager,
            AccessTokenService accessTokenService,
            RegistrationService registrationService) {
        this.authenticationManager = authenticationManager;
        this.accessTokenService = accessTokenService;
        this.registrationService = registrationService;
    }

    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        PasswordAuthenticationToken unauthenticated = new PasswordAuthenticationToken(
                request.tenantId(),
                request.email(),
                request.password()
        );
        Authentication authenticated = authenticationManager.authenticate(unauthenticated);

        UserAccountPrincipal principal = (UserAccountPrincipal) authenticated.getPrincipal();
        AuthenticatedIdentity identity = principal.toAuthenticatedIdentity();
        AccessTokenService.IssuedAccessToken issuedToken = accessTokenService.generateAccessToken(identity);

        return ResponseEntity.ok(new AccessTokenResponse(
                issuedToken.token(),
                "Bearer",
                issuedToken.expiresIn().toSeconds()
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationAcceptedResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        registrationService.register(
                request.email(),
                request.password(),
                httpRequest.getRemoteAddr()
        );

        return ResponseEntity.accepted().body(RegistrationAcceptedResponse.generic());
    }
}
