package com.safepath.backend;

import com.safepath.backend.support.IsolatedTestDatabase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendApplicationTests {
    private static final IsolatedTestDatabase DATABASE = new IsolatedTestDatabase();

    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DATABASE.configure(registry);
    }

    @Test
    void contextLoads() {
        DATABASE.assertIsolated(jdbc);
    }

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) {
        DATABASE.cleanup(jdbc);
    }
}
