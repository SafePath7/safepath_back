package com.safepath.backend.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Each test class migrates its own schema, so V1-only and V2 branches never change public data. */
public final class IsolatedTestDatabase {
    private final String schema = "api_test_" + UUID.randomUUID().toString().replace("-", "");

    public void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.flyway.create-schemas", () -> true);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + schema);
    }

    public void assertIsolated(JdbcTemplate jdbc) {
        if (!schema.equals(jdbc.queryForObject("SELECT current_schema()", String.class))) {
            throw new IllegalStateException("Test connection is outside its isolated schema");
        }
    }

    public void cleanup(JdbcTemplate jdbc) {
        if (!schema.matches("api_test_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
