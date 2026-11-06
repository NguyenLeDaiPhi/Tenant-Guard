package com.authservice.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class RateLimitService {

    private static final String UPSERT_BUCKET_SQL = """
        INSERT INTO public.auth_rate_limits (bucket_hash, window_started_at, request_count)
        VALUES (?, to_timestamp(floor(extract(epoch FROM clock_timestamp()) / ?) * ?), 1)
        ON CONFLICT (bucket_hash) DO UPDATE
        SET window_started_at = CASE
                WHEN auth_rate_limits.window_started_at < EXCLUDED.window_started_at
                THEN EXCLUDED.window_started_at
                ELSE auth_rate_limits.window_started_at
            END,
            request_count = CASE
                WHEN auth_rate_limits.window_started_at < EXCLUDED.window_started_at
                THEN 1
                ELSE auth_rate_limits.request_count + 1
            END
        RETURNING request_count
        """;

    private final JdbcTemplate jdbcTemplate;

    public RateLimitService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int consume(String scope, String subject, Duration window) {
        int seconds = Math.toIntExact(window.toSeconds());
        return jdbcTemplate.queryForObject(
                UPSERT_BUCKET_SQL,
                Integer.class,
                hash(scope + "\u0000" + subject),
                seconds,
                seconds
        );
    }

    @Scheduled(fixedDelay = 21_600_000)
    @Transactional
    public void deleteExpiredBuckets() {
        jdbcTemplate.update("""
                DELETE FROM public.auth_rate_limits
                WHERE window_started_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
                """);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
