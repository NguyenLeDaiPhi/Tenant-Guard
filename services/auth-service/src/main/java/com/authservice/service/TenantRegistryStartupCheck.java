package com.authservice.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TenantRegistryStartupCheck implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public TenantRegistryStartupCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.query(
                "SELECT id, slug, status FROM public.tenants WHERE false",
                resultSet -> {
                    if (!"uuid".equalsIgnoreCase(resultSet.getMetaData().getColumnTypeName(1))) {
                        throw new IllegalStateException(
                            "public.tenants.id must use PostgreSQL UUID"
                        );
                    }
                    return null;
                }
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                "Auth Service requires a readable public.tenants table with UUID id, slug, and status columns. "
                    + "Provision it through Tenant Service before starting Auth Service.",
                exception
            );
        }
    }
}
