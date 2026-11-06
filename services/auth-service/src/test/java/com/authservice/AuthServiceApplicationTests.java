package com.authservice;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.domain.IdentityStatus;
import com.authservice.domain.MembershipStatus;
import com.authservice.domain.UserRole;
import com.authservice.persistence.TenantMembershipRepository;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import com.authservice.persistence.UserIdentityRepository;
import com.authservice.service.AccessTokenService;
import com.authservice.service.VerificationEmailSender;
import com.authservice.service.VerificationCodeHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthServiceApplicationTests {

        private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
        private static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
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
                                .withPassword("secret")
                                .withInitScript("db/test-init.sql");
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
        private AccessTokenService accessTokenService;

        @Autowired
        private TenantMembershipRepository membershipRepository;

        @Autowired
        private UserAccountRepository userAccountRepository;

        @Autowired
        private UserIdentityRepository userIdentityRepository;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @MockitoBean
        private VerificationEmailSender emailSender;

        @BeforeEach
        void clearRateLimitBuckets() {
                jdbcTemplate.update("DELETE FROM public.auth_rate_limits");
        }

        @Test
        void registrationWithoutMembershipReturnsGenericResponseWithoutCreatingAccount() throws Exception {
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody("unknown@example.com", "SecurePassword123!")))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.message")
                                                .value("If this email is eligible, a verification code will be sent."));

                assertThat(userAccountRepository.findByTenantIdAndNormalizedEmail(
                                TENANT_ID, "unknown@example.com")).isEmpty();
                assertThat(userIdentityRepository.findByNormalizedEmail("unknown@example.com"))
                                .get()
                                .extracting(identity -> identity.getStatus())
                                .isEqualTo(IdentityStatus.PENDING_EMAIL);
                verify(emailSender).sendVerificationCode(
                                org.mockito.ArgumentMatchers.eq("unknown@example.com"),
                                org.mockito.ArgumentMatchers.any());
        }

        @Test
        void registrationAndVerificationArePublicAndTenantRoleComesFromMembership() throws Exception {
                String email = uniqueEmail();
                createPendingMembership(TENANT_ID, email, UserRole.ORDER_MANAGER);

                String body = """
                                {
                                  "email": "%s",
                                  "password": "SecurePassword123!",
                                  "tenantId": "%s",
                                  "role": "PLATFORM_ADMIN"
                                }
                                """.formatted(email, OTHER_TENANT_ID);

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isAccepted());

                var pendingIdentity = userIdentityRepository.findByNormalizedEmail(email)
                                .orElseThrow();
                assertThat(pendingIdentity.getStatus()).isEqualTo(IdentityStatus.PENDING_EMAIL);
                assertThat(passwordEncoder.matches("SecurePassword123!", pendingIdentity.getPasswordHash())).isTrue();
                assertThat(userAccountRepository.findByTenantIdAndNormalizedEmail(TENANT_ID, email)).isEmpty();
                assertThat(countMembership(TENANT_ID, email, UserRole.ORDER_MANAGER, MembershipStatus.PENDING))
                                .isEqualTo(1);
                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(TENANT_ID, email, "SecurePassword123!")))
                                .andExpect(status().isUnauthorized());

                ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
                verify(emailSender).sendVerificationCode(org.mockito.ArgumentMatchers.eq(email), codeCaptor.capture());
                String code = codeCaptor.getValue();
                String storedCodeHash = jdbcTemplate.queryForObject(
                                "SELECT code_hash FROM public.email_verification_codes WHERE normalized_email = ? AND consumed_at IS NULL",
                                String.class,
                                email);
                assertThat(storedCodeHash).isEqualTo(VerificationCodeHasher.hash(code));
                assertThat(storedCodeHash).isNotEqualTo(code);

                mockMvc.perform(post("/api/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verifyBody(email, code, "SecurePassword123!")))
                                .andExpect(status().isNoContent());

                assertThat(userIdentityRepository.findByNormalizedEmail(email).orElseThrow().getStatus())
                                .isEqualTo(IdentityStatus.VERIFIED);
                assertThat(countMembership(TENANT_ID, email, UserRole.ORDER_MANAGER, MembershipStatus.PENDING))
                                .isEqualTo(1);
                assertThat(userAccountRepository.findByTenantIdAndNormalizedEmail(TENANT_ID, email)).isEmpty();

                mockMvc.perform(post("/api/auth/memberships")
                                .header("Authorization", "Bearer " + createTenantAdminToken(TENANT_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(membershipBody(TENANT_ID, email, UserRole.ORDER_MANAGER)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"));

                UserAccount activated = userAccountRepository.findByTenantIdAndNormalizedEmail(TENANT_ID, email)
                                .orElseThrow();
                assertThat(activated.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(activated.getRole()).isEqualTo(UserRole.ORDER_MANAGER);
                assertThat(activated.getTenantId()).isEqualTo(TENANT_ID);
                assertThat(passwordEncoder.matches("SecurePassword123!", activated.getPasswordHash())).isTrue();
                assertThat(countMembership(TENANT_ID, email, UserRole.ORDER_MANAGER, MembershipStatus.ACTIVE))
                                .isEqualTo(1);

                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(TENANT_ID, email, "SecurePassword123!")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.accessToken").isNotEmpty());

                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(OTHER_TENANT_ID, email, "SecurePassword123!")))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void duplicateRegistrationDoesNotCreateAnotherAccountAndInvalidatesPreviousCode() throws Exception {
                String email = uniqueEmail();
                createPendingMembership(TENANT_ID, email, UserRole.ORDER_VIEWER);
                String body = registerBody(email, "SecurePassword123!");

                mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isAccepted());
                mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isAccepted());

                ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
                verify(emailSender, org.mockito.Mockito.times(2))
                                .sendVerificationCode(org.mockito.ArgumentMatchers.eq(email), codes.capture());
                assertThat(userIdentityRepository.findByNormalizedEmail(email).orElseThrow().getStatus())
                                .isEqualTo(IdentityStatus.PENDING_EMAIL);

                mockMvc.perform(post("/api/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verifyBody(
                                                email, codes.getAllValues().getFirst(), "VerifiedPassword456!")))
                                .andExpect(status().isBadRequest());
                mockMvc.perform(post("/api/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verifyBody(
                                                email, codes.getAllValues().getLast(), "VerifiedPassword456!")))
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
        void membershipProvisioningRequiresTenantAdminJwtAndTenantMatch() throws Exception {
                String email = uniqueEmail();
                String request = membershipBody(TENANT_ID, email, UserRole.ORDER_VIEWER);

                mockMvc.perform(post("/api/auth/memberships")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                                .andExpect(status().isUnauthorized());

                String tenantAdminToken = createTenantAdminToken(TENANT_ID);
                mockMvc.perform(post("/api/auth/memberships")
                                .header("Authorization", "Bearer " + tenantAdminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(membershipBody(OTHER_TENANT_ID, email, UserRole.ORDER_VIEWER)))
                                .andExpect(status().isForbidden());

                mockMvc.perform(post("/api/auth/memberships")
                                .header("Authorization", "Bearer " + tenantAdminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                                .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void incorrectVerificationCodeDoesNotActivateAccount() throws Exception {
                String email = uniqueEmail();
                createPendingMembership(TENANT_ID, email, UserRole.ORDER_VIEWER);
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody(email, "SecurePassword123!")))
                                .andExpect(status().isAccepted());

                mockMvc.perform(post("/api/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verifyBody(email, "0".repeat(64), "SecurePassword123!")))
                                .andExpect(status().isBadRequest());

                assertThat(userAccountRepository.findByTenantIdAndNormalizedEmail(TENANT_ID, email)).isEmpty();
                assertThat(userIdentityRepository.findByNormalizedEmail(email).orElseThrow().getStatus())
                                .isEqualTo(IdentityStatus.PENDING_EMAIL);
                assertThat(countMembership(TENANT_ID, email, UserRole.ORDER_VIEWER, MembershipStatus.PENDING))
                                .isEqualTo(1);
        }

        private int countMembership(UUID tenantId, String email, UserRole role, MembershipStatus status) {
                return jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM public.tenant_memberships
                                                WHERE tenant_id = ?
                                                  AND normalized_email = ?
                                                  AND role = ?
                                                  AND status = ?
                                                """,
                                Integer.class,
                                tenantId,
                                email,
                                role.name(),
                                status.name());
        }

        private void createPendingMembership(UUID tenantId, String email, UserRole role) throws Exception {
                String token = createTenantAdminToken(tenantId);
                mockMvc.perform(post("/api/auth/memberships")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(membershipBody(tenantId, email, role)))
                                .andExpect(status().isCreated());
        }

        private String createTenantAdminToken(UUID tenantId) {
                UserAccount admin = UserAccount.builder()
                                .id(UUID.randomUUID())
                                .tenantId(tenantId)
                                .email("admin-" + UUID.randomUUID() + "@example.com")
                                .passwordHash("not-used")
                                .role(UserRole.TENANT_ADMIN)
                                .status(AccountStatus.ACTIVE)
                                .build();
                admin.setNormalizedEmail(admin.getEmail());
                userAccountRepository.save(admin);
                return accessTokenService.generateAccessToken(new AuthenticatedIdentity(
                                admin.getId(), tenantId, UserRole.TENANT_ADMIN, admin.getEmail())).token();
        }

        private static String uniqueEmail() {
                return "member-" + UUID.randomUUID() + "@example.com";
        }

        private static String registerBody(String email, String password) {
                return """
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password);
        }

        private static String verifyBody(String email, String code, String password) {
                return """
                                {"email":"%s","code":"%s","password":"%s"}
                                """.formatted(email, code, password);
        }

        private static String membershipBody(UUID tenantId, String email, UserRole role) {
                return """
                                {"tenantId":"%s","email":"%s","role":"%s"}
                                """.formatted(tenantId, email, role);
        }

        private static String loginBody(UUID tenantId, String email, String password) {
                return """
                                {"tenantId":"%s","email":"%s","password":"%s"}
                                """.formatted(tenantId, email, password);
        }
}
