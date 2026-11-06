package com.authservice.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TenantStatusRepository {
    private static final String FIND_BY_ID_SQL = """
        SELECT id, slug, status
        FROM public.tenants
        WHERE id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public TenantStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TenantStatus> findById(UUID tenantId) {
        List<TenantStatus> tenants = jdbcTemplate.query(
            FIND_BY_ID_SQL,
            (resultSet, rowNumber) -> new TenantStatus(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("slug"),
                resultSet.getString("status")
            ),
            tenantId
        );
        return tenants.stream().findFirst();
    }

    public boolean existsByIdAndStatus(UUID id, String status) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM public.tenants WHERE id = ? AND status = ?)",
            Boolean.class,
            id,
            status
        );
        return Boolean.TRUE.equals(exists);
    }
}
