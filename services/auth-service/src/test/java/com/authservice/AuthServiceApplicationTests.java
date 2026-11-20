package com.authservice;

import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationChallenge;
import com.authservice.persistence.EmailVerificationChallengeRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import com.authservice.service.EmailVerificationService;
import com.authservice.service.VerificationCodeHasher;
import com.authservice.service.VerificationEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthServiceApplicationTests {

    private static PostgreSQLContainer<?> postgres;
    private static String privateKeyBase64;
    private static String publicKeyBase64;

    static {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }

        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("tenantguard")
                .withUsername("auth_service")
                .withPassword("secret");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.private-key-base64", () -> privateKeyBase64);
        registry.add("auth.jwt.public-key-base64", () -> publicKeyBase64);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private EmailVerificationChallengeRepository verificationCodeRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private VerificationEmailSender emailSender;

    @BeforeEach
    void clearRateLimitBuckets() {
        jdbcTemplate.update("DELETE FROM public.auth_rate_limits");
    }

    @Test
    void registrationCreatesPendingGlobalIdentityAndChallengeWithoutTenantMembership() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, "SecurePassword123!")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message")
                        .value("If this email is eligible, a verification code will be sent."));

        assertThat(userIdentityRepository.findByNormalizedEmail(email))
                .hasValueSatisfying(identity -> assertThat(identity.getStatus())
                        .isEqualTo(IdentityStatus.PENDING_EMAIL));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.email_verification_challenges challenge "
                        + "JOIN public.user_identities identity ON identity.id = challenge.identity_id "
                        + "WHERE identity.normalized_email = ? AND challenge.consumed_at IS NULL",
                Integer.class,
                email)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tenant_memberships') IS NULL",
                Boolean.class)).isTrue();
        verify(emailSender).sendVerificationCode(eq(email), any());
    }

    @Test
    void verifiedGlobalIdentityCanLoginWithoutTenantMembership() throws Exception {
        String email = uniqueEmail();
        String password = "SecurePassword123!";
        registerAndVerify(email, password);

        UserIdentity identity = userIdentityRepository.findByNormalizedEmail(email).orElseThrow();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "WrongPassword123!")))
                .andExpect(status().isUnauthorized());
        assertThat(userIdentityRepository.findByNormalizedEmail(email).orElseThrow()
                .getFailedLoginCount()).isEqualTo(1);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        Matcher tokenMatcher = Pattern.compile("\"accessToken\":\"([^\"]+)\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(tokenMatcher.find()).isTrue();
        Jwt jwt = jwtDecoder.decode(tokenMatcher.group(1));
        assertThat(jwt.getSubject()).isEqualTo(identity.getId().toString());
        assertThat(jwt.getClaimAsString("global_user_id")).isEqualTo(identity.getId().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo(email);
        assertThat(jwt.getClaims()).doesNotContainKeys("tid", "roles");
        assertThat(userIdentityRepository.findByNormalizedEmail(email).orElseThrow()
                .getFailedLoginCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tenant_memberships') IS NULL",
                Boolean.class)).isTrue();
    }

    @Test
    void registrationAndVerificationOnlyCreateGlobalIdentity() throws Exception {
        String email = uniqueEmail();
        String password = "SecurePassword123!";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andExpect(status().isAccepted());

        UserIdentity pendingIdentity = userIdentityRepository.findByNormalizedEmail(email).orElseThrow();
        assertThat(pendingIdentity.getStatus()).isEqualTo(IdentityStatus.PENDING_EMAIL);
        assertThat(pendingIdentity.getEmailVerifiedAt()).isNull();
        assertThat(pendingIdentity.getPasswordHash()).isNotBlank();

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendVerificationCode(eq(email), codeCaptor.capture());
        String code = codeCaptor.getValue();
        String storedCodeHash = jdbcTemplate.queryForObject(
                "SELECT challenge.code_hash FROM public.email_verification_challenges challenge "
                        + "JOIN public.user_identities identity ON identity.id = challenge.identity_id "
                        + "WHERE identity.normalized_email = ? AND challenge.consumed_at IS NULL",
                String.class,
                email);
        assertThat(storedCodeHash).isEqualTo(VerificationCodeHasher.hash(code));
        assertThat(storedCodeHash).isNotEqualTo(code);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(email, code)))
                .andExpect(status().isNoContent());

        UserIdentity identity = userIdentityRepository.findByNormalizedEmail(email).orElseThrow();
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.VERIFIED);
        assertThat(identity.getEmailVerifiedAt()).isNotNull();
        assertThat(passwordEncoder.matches(password, identity.getPasswordHash())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.email_verification_challenges challenge "
                        + "JOIN public.user_identities identity ON identity.id = challenge.identity_id "
                        + "WHERE identity.normalized_email = ? AND challenge.consumed_at IS NULL",
                Integer.class,
                email)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tenant_memberships') IS NULL",
                Boolean.class)).isTrue();
    }

    @Test
    void duplicateRegistrationInvalidatesThePreviousChallenge() throws Exception {
        String email = uniqueEmail();
        String body = registerBody(email, "SecurePassword123!");

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(emailSender, times(2)).sendVerificationCode(eq(email), codes.capture());
        assertThat(userIdentityRepository.findByNormalizedEmail(email))
                .hasValueSatisfying(identity -> assertThat(identity.getStatus())
                        .isEqualTo(IdentityStatus.PENDING_EMAIL));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(email, codes.getAllValues().getFirst())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(email, codes.getAllValues().getLast())))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidEmailAndPasswordLengthsAreRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("not-an-email", "SecurePassword123!")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("valid@example.com", "short")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("valid@example.com", "x".repeat(73))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incorrectVerificationCodeDoesNotVerifyIdentity() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, "SecurePassword123!")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(email, "0".repeat(64))))
                .andExpect(status().isBadRequest());

        assertThat(userIdentityRepository.findByNormalizedEmail(email))
                .hasValueSatisfying(identity -> assertThat(identity.getStatus())
                        .isEqualTo(IdentityStatus.PENDING_EMAIL));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT challenge.failed_attempts FROM public.email_verification_challenges challenge "
                        + "JOIN public.user_identities identity ON identity.id = challenge.identity_id "
                        + "WHERE identity.normalized_email = ? AND challenge.consumed_at IS NULL",
                Integer.class,
                email)).isEqualTo(1);
    }

    @Test
    void expiredChallengeCleanupDoesNotDeleteVerifiedIdentityCredentials() {
        String email = uniqueEmail();
        String passwordHash = passwordEncoder.encode("SecurePassword123!");
        Instant now = Instant.now();
        UserIdentity identity = userIdentityRepository.saveAndFlush(UserIdentity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .normalizedEmail(email)
                .passwordHash(passwordHash)
                .status(IdentityStatus.VERIFIED)
                .createdAt(now.minusSeconds(3600))
                .updatedAt(now.minusSeconds(3600))
                .emailVerifiedAt(now.minusSeconds(1800))
                .build());
        EmailVerificationChallenge expired = verificationCodeRepository.saveAndFlush(
                verificationCode(identity, now.minusSeconds(1200), now.minusSeconds(300)));

        emailVerificationService.deleteExpiredChallenges();

        assertThat(verificationCodeRepository.findById(expired.getId())).isEmpty();
        UserIdentity retained = userIdentityRepository.findByNormalizedEmail(email).orElseThrow();
        assertThat(retained.isVerified()).isTrue();
        assertThat(retained.getPasswordHash()).isEqualTo(passwordHash);
    }

    @Test
    void expiredChallengeIsRejectedAndRemovedWithoutVerifyingIdentity() throws Exception {
        String email = uniqueEmail();
        String code = "b".repeat(64);
        UserIdentity pendingIdentity = pendingIdentity(email);
        userIdentityRepository.saveAndFlush(pendingIdentity);
        EmailVerificationChallenge expired = verificationCode(
                pendingIdentity, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(300), code);
        verificationCodeRepository.saveAndFlush(expired);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(email, code)))
                .andExpect(status().isBadRequest());

        assertThat(verificationCodeRepository.findById(expired.getId())).isEmpty();
        assertThat(userIdentityRepository.findByNormalizedEmail(email))
                .hasValueSatisfying(identity -> assertThat(identity.getStatus())
                        .isEqualTo(IdentityStatus.PENDING_EMAIL));
    }

    @Test
    void registrationIsRateLimitedByClientIp() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(uniqueEmail(), "SecurePassword123!")))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueEmail(), "SecurePassword123!")))
                .andExpect(status().isTooManyRequests());
    }

    private void registerAndVerify(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andExpect(status().isAccepted());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendVerificationCode(eq(email), codeCaptor.capture());
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(email, codeCaptor.getValue())))
                .andExpect(status().isNoContent());
    }

    private static EmailVerificationChallenge verificationCode(
            UserIdentity identity,
            Instant createdAt,
            Instant expiresAt) {
        return verificationCode(identity, createdAt, expiresAt, "a".repeat(64));
    }

    private static EmailVerificationChallenge verificationCode(
            UserIdentity identity,
            Instant createdAt,
            Instant expiresAt,
            String code) {
        return EmailVerificationChallenge.builder()
                .id(UUID.randomUUID())
                .identity(identity)
                .codeHash(VerificationCodeHasher.hash(code))
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void authSchemaRemovesLegacyAndTenantOwnedTables() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT to_regclass('public.users') IS NULL
                   AND to_regclass('public.tenant_memberships') IS NULL
                   AND to_regclass('public.registration_invitations') IS NULL
                   AND to_regclass('public.email_verification_codes') IS NULL
                   AND to_regclass('public.email_verification_challenges') IS NOT NULL
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'external_identities'
                  AND column_name = 'user_id'
                """, Integer.class)).isZero();
    }

    private UserIdentity pendingIdentity(String email) {
        Instant now = Instant.now();
        return UserIdentity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .normalizedEmail(email)
                .passwordHash(passwordEncoder.encode("SecurePassword123!"))
                .status(IdentityStatus.PENDING_EMAIL)
                .createdAt(now.minusSeconds(3600))
                .updatedAt(now.minusSeconds(3600))
                .build();
    }

    private static String uniqueEmail() {
        return "member-" + UUID.randomUUID() + "@example.com";
    }

    private static String registerBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private static String verifyBody(String email, String code) {
        return """
                {"email":"%s","code":"%s"}
                """.formatted(email, code);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }
}
