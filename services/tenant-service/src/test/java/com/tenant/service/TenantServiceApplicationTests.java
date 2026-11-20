package com.tenant.service;

import com.tenant.service.membership.service.TenantInvitationEmailSender;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantServiceApplicationTests.JwtTestConfiguration.class)
class TenantServiceApplicationTests {

    private static final String ISSUER = "http://localhost:8081";
    private static final String AUDIENCE = "tenantguard-api";
    private static final String KEY_ID = "tenantguard-local";
    private static final String DATABASE_PASSWORD = "postgres_test_password";
    private static final String TENANT_DATABASE_PASSWORD = "tenant_test_password";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final KeyPair UNTRUSTED_KEY = generateKeyPair();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tenantguard")
            .withUsername("postgres")
            .withPassword(DATABASE_PASSWORD);
    private static final HttpServer JWKS_SERVER = startJwksServer();

    static {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE tenant_service LOGIN PASSWORD '" + TENANT_DATABASE_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
            statement.execute("GRANT CONNECT ON DATABASE tenantguard TO tenant_service");
            statement.execute("GRANT CREATE ON SCHEMA public TO tenant_service");
            statement.execute("CREATE SCHEMA tenant_service AUTHORIZATION tenant_service");
        } catch (SQLException exception) {
            POSTGRES.stop();
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private TenantInvitationEmailSender invitationEmailSender;

    @Test
    void tenantDatabaseRoleCanMigrateWithoutDatabaseCreatePrivilege() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_database_privilege(current_user, current_database(), 'CREATE')",
                Boolean.class)).isFalse();
    }

    @BeforeEach
    void cleanTenantData() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_subscription_failure ON tenant_service.tenant_subscriptions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS tenant_service.test_subscription_failure()");
        jdbcTemplate.update("DELETE FROM tenant_service.subscription_entitlements");
        jdbcTemplate.update("DELETE FROM tenant_service.tenant_subscriptions");
        jdbcTemplate.update("DELETE FROM tenant_service.tenants");
        jdbcTemplate.update("UPDATE tenant_service.subscription_plans SET active = TRUE");
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "tenant_service");
        registry.add("spring.datasource.password", () -> TENANT_DATABASE_PASSWORD);
        registry.add("spring.flyway.user", () -> "tenant_service");
        registry.add("spring.flyway.password", () -> TENANT_DATABASE_PASSWORD);
        registry.add("auth.jwt.issuer", () -> ISSUER);
        registry.add("auth.jwt.audience", () -> AUDIENCE);
        registry.add("auth.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @Test
    void authServiceJwtContractIsAcceptedAndPlansAreDatabaseBacked() throws Exception {
        mockMvc.perform(get("/api/v1/subscription-plans/plans"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/subscription-plans/plans")
                        .header("Authorization", bearer(globalAuthToken(UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("FREE"))
                .andExpect(jsonPath("$[0].entitlements[0].key").value("members.limit"))
                .andExpect(jsonPath("$[0].entitlements[0].value").value(3));
    }

    @Test
    void dashboardTenantListSupportsNoMembershipsAndMultipleMemberships() throws Exception {
        UUID globalUserId = UUID.randomUUID();
        String token = bearer(globalAuthToken(globalUserId));

        mockMvc.perform(get("/api/v1/tenants").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String firstSlug = "first-" + UUID.randomUUID().toString().substring(0, 8);
        String secondSlug = "second-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(firstSlug, "FREE")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(secondSlug, "FREE")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/tenants").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void membershipListReturnsOnlyTheAuthenticatedUsersActiveMemberships() throws Exception {
        UUID globalUserId = UUID.randomUUID();
        String ownerToken = bearer(globalAuthToken(globalUserId));
        String slug = "member-list-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "FREE")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/memberships").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(globalUserId.toString()))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].owner").value(true));

        mockMvc.perform(get("/api/v1/memberships")
                        .header("Authorization", bearer(globalAuthToken(UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void ownerCanEmailAndAcceptInvitationOnlyForTheInvitedGlobalEmail() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ownerToken = bearer(globalAuthToken(ownerId));
        String slug = "invite-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "FREE")))
                .andExpect(status().isCreated());
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant_service.tenants WHERE slug = ?",
                UUID.class,
                slug);
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/invitations", tenantId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"MEMBER"}
                                """.formatted(inviteeEmail)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.token").doesNotExist());

        ArgumentCaptor<TenantInvitationEmailSender.InvitationEmail> emailCaptor =
                ArgumentCaptor.forClass(TenantInvitationEmailSender.InvitationEmail.class);
        verify(invitationEmailSender).send(emailCaptor.capture());
        TenantInvitationEmailSender.InvitationEmail email = emailCaptor.getValue();
        assertThat(email.recipient()).isEqualTo(inviteeEmail);
        assertThat(email.token()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM tenant_service.tenant_invitations "
                        + "WHERE tenant_id = ? AND normalized_email = ?",
                String.class,
                tenantId,
                inviteeEmail)).isNotEqualTo(email.token());

        String wrongEmailToken = bearer(globalAuthToken(UUID.randomUUID(), "other@example.com"));
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header("Authorization", wrongEmailToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(email.token())))
                .andExpect(status().isNotFound());

        UUID inviteeId = UUID.randomUUID();
        String inviteeToken = bearer(globalAuthToken(inviteeId, inviteeEmail));
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .header("Authorization", inviteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(email.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.userId").value(inviteeId.toString()))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/memberships", tenantId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tenant_service.tenant_invitations "
                        + "WHERE tenant_id = ? AND normalized_email = ?",
                String.class,
                tenantId,
                inviteeEmail)).isEqualTo("ACCEPTED");
    }

    @Test
    void emailDeliveryFailureLeavesInvitationPersistedForResend() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ownerToken = bearer(globalAuthToken(ownerId));
        String slug = "invite-failure-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "FREE")))
                .andExpect(status().isCreated());
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant_service.tenants WHERE slug = ?",
                UUID.class,
                slug);
        doThrow(new MailSendException("SMTP unavailable"))
                .when(invitationEmailSender).send(any());

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/invitations", tenantId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"retry@example.com","role":"MEMBER"}
                                """))
                .andExpect(status().isBadGateway());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_service.tenant_invitations "
                        + "WHERE tenant_id = ? AND normalized_email = ? AND status = 'PENDING'",
                Integer.class,
                tenantId,
                "retry@example.com")).isEqualTo(1);
    }

    @Test
    void createsTenantOwnerAndFreeSubscriptionWithImmutableEntitlementSnapshot() throws Exception {
        UUID globalUserId = UUID.randomUUID();
        String slug = "acme-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", bearer(globalAuthToken(globalUserId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Acme Corporation",
                                  "slug": "%s",
                                  "subscriptionPlanCode": "FREE",
                                  "ownerUserId": "%s",
                                  "role": "MEMBER"
                                }
                                """.formatted(slug, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(globalUserId.toString()))
                .andExpect(jsonPath("$.currentUserRole").value("OWNER"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.planCode").value("FREE"))
                .andExpect(jsonPath("$.subscription.entitlements.length()").value(4));

        assertThat(count("tenant_service.tenants")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM tenant_service.tenant_memberships WHERE role = 'OWNER'",
                UUID.class)).isEqualTo(globalUserId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_service.subscription_entitlements",
                Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tenants') IS NULL",
                Boolean.class)).isTrue();
    }

    @Test
    void returnsConflictForDuplicateSlugAndDoesNotCreateSecondTenant() throws Exception {
        UUID userId = UUID.randomUUID();
        String slug = "duplicate-" + UUID.randomUUID().toString().substring(0, 8);
        String body = createBody(slug, "FREE");
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", bearer(authToken(
                                userId, ISSUER, AUDIENCE, Duration.ofMinutes(5))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", bearer(authToken(
                                UUID.randomUUID(), ISSUER, AUDIENCE, Duration.ofMinutes(5))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
        assertThat(count("tenant_service.tenants")).isEqualTo(1);
    }

    @Test
    void rejectsUnknownAndInactivePlansAndLeavesPaidSubscriptionPending() throws Exception {
        String token = bearer(authToken(UUID.randomUUID(), ISSUER, AUDIENCE, Duration.ofMinutes(5)));
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("unknown-" + UUID.randomUUID().toString().substring(0, 8), "UNKNOWN")))
                .andExpect(status().isBadRequest());

        jdbcTemplate.update("UPDATE tenant_service.subscription_plans SET active = FALSE WHERE code = 'STARTER'");
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("inactive-" + UUID.randomUUID().toString().substring(0, 8), "STARTER")))
                .andExpect(status().isBadRequest());

        jdbcTemplate.update("UPDATE tenant_service.subscription_plans SET active = TRUE WHERE code = 'STARTER'");
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("paid-" + UUID.randomUUID().toString().substring(0, 8), "STARTER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscription.status").value("PENDING_PAYMENT"));
    }

    @Test
    void databaseHasTenantOwnedTablesSeedPlansAndNoCrossSchemaForeignKeys() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_service.subscription_plans",
                Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint constraint_row
                JOIN pg_class source_table ON source_table.oid = constraint_row.conrelid
                JOIN pg_namespace source_schema ON source_schema.oid = source_table.relnamespace
                JOIN pg_class target_table ON target_table.oid = constraint_row.confrelid
                JOIN pg_namespace target_schema ON target_schema.oid = target_table.relnamespace
                WHERE constraint_row.contype = 'f'
                  AND source_schema.nspname = 'tenant_service'
                  AND target_schema.nspname <> 'tenant_service'
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE tenant_service.plan_entitlements
                SET integer_value = 9
                WHERE entitlement_key = 'members.limit'
                  AND plan_version_id = '20000000-0000-4000-8000-000000000001'
                """)).isInstanceOf(Exception.class);
    }

    @Test
    void onlyActiveTenantMembersCanReadAndOnlyOwnerCanUpdate() throws Exception {
        UUID owner = UUID.randomUUID();
        String slug = "access-" + UUID.randomUUID().toString().substring(0, 8);
        String ownerToken = bearer(authToken(owner, ISSUER, AUDIENCE, Duration.ofMinutes(5)));
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "FREE")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant_service.tenants WHERE slug = ?", UUID.class, slug);

        UUID member = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO tenant_service.tenant_memberships
                    (id, tenant_id, user_id, role, status, is_owner, joined_at, created_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), tenantId, member);
        String memberToken = bearer(authToken(member, ISSUER, AUDIENCE, Duration.ofMinutes(5)));

        mockMvc.perform(get("/api/v1/tenants")
                        .header("Authorization", memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .header("Authorization", memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/tenants/{id}", tenantId)
                        .header("Authorization", bearer(authToken(
                                UUID.randomUUID(), ISSUER, AUDIENCE, Duration.ofMinutes(5)))))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Acme"));

        jdbcTemplate.update("UPDATE tenant_service.tenants SET status = 'SUSPENDED' WHERE id = ?", tenantId);
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentDuplicateSlugCreationCreatesOnlyOneTenant() throws Exception {
        String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> createWithStatus(
                    start,
                    slug,
                    UUID.randomUUID()));
            Future<Integer> second = executor.submit(() -> createWithStatus(
                    start,
                    slug,
                    UUID.randomUUID()));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
            assertThat(count("tenant_service.tenants")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMalformedExpiredWrongIssuerWrongAudienceAndBadSignatureTokens() throws Exception {
        UUID globalId = UUID.randomUUID();
        List<String> invalidTokens = List.of(
                "not-a-jwt",
                expiredToken(globalId),
                authToken(globalId, "https://wrong-issuer.example", AUDIENCE, Duration.ofMinutes(5)),
                authToken(globalId, ISSUER, "wrong-audience", Duration.ofMinutes(5)),
                signedToken(UNTRUSTED_KEY, globalId, ISSUER, AUDIENCE),
                tokenWithoutSubject(globalId));
        for (String token : invalidTokens) {
            mockMvc.perform(get("/api/v1/subscription-plans")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void failedSubscriptionInsertRollsBackTenantAndMembership() {
        jdbcTemplate.execute("""
                CREATE FUNCTION tenant_service.test_subscription_failure()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected subscription persistence failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_subscription_failure
                BEFORE INSERT ON tenant_service.tenant_subscriptions
                FOR EACH ROW EXECUTE FUNCTION tenant_service.test_subscription_failure()
                """);
        String body = createBody("rollback-" + UUID.randomUUID().toString().substring(0, 8), "FREE");

        assertThatThrownBy(() -> mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", bearer(authToken(
                                UUID.randomUUID(), ISSUER, AUDIENCE, Duration.ofMinutes(5))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .isInstanceOf(Exception.class);

        assertThat(count("tenant_service.tenants")).isZero();
        assertThat(count("tenant_service.tenant_memberships")).isZero();
        assertThat(count("tenant_service.tenant_subscriptions")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tenants') IS NULL",
                Boolean.class)).isTrue();
    }

    private long count(String qualifiedTable) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + qualifiedTable, Long.class);
    }

    private int createWithStatus(
            CountDownLatch start,
            String slug,
            UUID globalUserId) throws Exception {
        start.await();
        return mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", bearer(authToken(
                                globalUserId, ISSUER, AUDIENCE, Duration.ofMinutes(5))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slug, "FREE")))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static String createBody(String slug, String planCode) {
        return """
                {"name":"Test Tenant","slug":"%s","subscriptionPlanCode":"%s"}
                """.formatted(slug, planCode);
    }

    private String authToken(
            UUID globalUserId,
            String issuer,
            String audience,
            Duration ttl) {
        return globalAuthToken(globalUserId, "tenant-test@example.com", issuer, audience, ttl);
    }

    private String globalAuthToken(UUID globalUserId) {
        return globalAuthToken(globalUserId, "tenant-test@example.com");
    }

    private String globalAuthToken(UUID globalUserId, String email) {
        return globalAuthToken(globalUserId, email, ISSUER, AUDIENCE, Duration.ofMinutes(5));
    }

    private String globalAuthToken(
            UUID globalUserId,
            String email,
            String issuer,
            String audience,
            Duration ttl) {
        Instant now = Instant.now();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(),
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(globalUserId.toString())
                        .claim("global_user_id", globalUserId.toString())
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(now.plus(ttl))
                        .claim("email", email)
                        .build())).getTokenValue();
    }

    private static String signedToken(
            KeyPair keyPair,
            UUID globalUserId,
            String issuer,
            String audience) {
        RSAKey signingJwk = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                .keyID(KEY_ID)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(signingJwk));
        JwtEncoder encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(jwkSource);
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(),
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(globalUserId.toString())
                        .claim("global_user_id", globalUserId.toString())
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("email", "tenant-test@example.com")
                        .build())).getTokenValue();
    }

    private String tokenWithoutSubject(UUID globalUserId) {
        Instant now = Instant.now();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .audience(List.of(AUDIENCE))
                        .claim("global_user_id", globalUserId.toString())
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(now.plusSeconds(300))
                        .build())).getTokenValue();
    }

    private String expiredToken(UUID globalUserId) {
        Instant issuedAt = Instant.now().minusSeconds(600);
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .audience(List.of(AUDIENCE))
                        .subject(globalUserId.toString())
                        .claim("global_user_id", globalUserId.toString())
                        .issuedAt(issuedAt)
                        .notBefore(issuedAt)
                        .expiresAt(issuedAt.plusSeconds(60))
                        .claim("email", "tenant-test@example.com")
                        .build())).getTokenValue();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String jwks = new JWKSet(new RSAKey.Builder(
                    (java.security.interfaces.RSAPublicKey) SIGNING_KEY.getPublic())
                    .keyID(KEY_ID)
                    .build()).toString();
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @TestConfiguration
    static class JwtTestConfiguration {

        @Bean
        JwtEncoder jwtEncoder() {
            RSAKey signingJwk = new RSAKey.Builder(
                    (java.security.interfaces.RSAPublicKey) SIGNING_KEY.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) SIGNING_KEY.getPrivate())
                    .keyID(KEY_ID)
                    .build();
            JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(signingJwk));
            return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(jwkSource);
        }
    }
}
