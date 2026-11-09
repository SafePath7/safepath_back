package com.safepath.backend.security.local;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalTestAuthStartupGuardTest {

	private static final String LOCAL_JDBC_URL = "jdbc:postgresql://localhost:5433/safepath";
	private static final Map<String, String> PASSWORDS = Map.of(
			LocalTestAuthStartupGuard.USER_1_PASSWORD_ENV, "synthetic-test-password-one",
			LocalTestAuthStartupGuard.USER_2_PASSWORD_ENV, "synthetic-test-password-two");

	@Test
	void localProfileAloneDoesNotActivateTestAuthenticationGuard() {
		assertDoesNotThrow(() -> LocalTestAuthStartupGuard.validate(environment("local", Map.of()), Map.of()));
	}

	@Test
	void acceptsOnlyLocalProfilesAndLoopbackDataSource() {
		StandardEnvironment environment = environment(
				"local",
				LocalTestAuthStartupGuard.TEST_AUTH_PROFILE,
				properties(LOCAL_JDBC_URL, "127.0.0.1"));

		assertDoesNotThrow(() -> LocalTestAuthStartupGuard.validate(environment, PASSWORDS));
	}

	@Test
	void rejectsTestAuthenticationWithoutLocalProfile() {
		assertThrows(IllegalStateException.class, () -> LocalTestAuthStartupGuard.validate(
				environment(LocalTestAuthStartupGuard.TEST_AUTH_PROFILE, properties(LOCAL_JDBC_URL, "127.0.0.1")),
				PASSWORDS));
	}

	@Test
	void rejectsDevAndOtherNonLocalProfiles() {
		for (String extraProfile : new String[] {"dev", "test", "prod"}) {
			assertThrows(IllegalStateException.class, () -> LocalTestAuthStartupGuard.validate(
					environment(
							"local",
							LocalTestAuthStartupGuard.TEST_AUTH_PROFILE,
							extraProfile,
							properties(LOCAL_JDBC_URL, "127.0.0.1")),
					PASSWORDS));
		}
	}

	@Test
	void rejectsRemoteDatabaseHostsAndMixedHostLists() {
		for (String jdbcUrl : new String[] {
				"jdbc:postgresql://192.0.2.10:5432/safepath",
				"jdbc:postgresql://database.example.invalid:5432/safepath",
				"jdbc:postgresql://localhost:5432,192.0.2.10:5432/safepath"
		}) {
			assertThrows(IllegalStateException.class,
					() -> LocalTestAuthStartupGuard.validateLoopbackJdbcUrl(jdbcUrl));
		}
	}

	@Test
	void rejectsJdbcHostOverridesAndUnsupportedDatasourceConfigurations() {
		assertThrows(IllegalStateException.class,
				() -> LocalTestAuthStartupGuard.validateLoopbackJdbcUrl(
						"jdbc:postgresql://localhost:5432/safepath?host=192.0.2.10"));

		for (String property : new String[] {
				"spring.datasource.jndi-name",
				"spring.datasource.hikari.data-source-class-name",
				"spring.datasource.hikari.data-source-properties.host",
				"spring.datasource.hikari.data-source-properties.hostaddr",
				"spring.datasource.hikari.data-source-properties.socketfactory",
				"spring.datasource.type"
		}) {
			StandardEnvironment environment = environment(
					"local",
					LocalTestAuthStartupGuard.TEST_AUTH_PROFILE,
					Map.of(
							"spring.datasource.url", LOCAL_JDBC_URL,
							property, "remote-override",
							"server.address", "127.0.0.1"));
			assertThrows(IllegalStateException.class,
					() -> LocalTestAuthStartupGuard.validate(environment, PASSWORDS));
		}
	}

	@Test
	void rejectsNonLoopbackServerBinding() {
		assertThrows(IllegalStateException.class, () -> LocalTestAuthStartupGuard.validate(
				environment(
						"local",
						LocalTestAuthStartupGuard.TEST_AUTH_PROFILE,
						properties(LOCAL_JDBC_URL, "0.0.0.0")),
				PASSWORDS));
	}

	@Test
	void rejectsMissingOrBlankPasswordEnvironmentVariables() {
		for (String variable : new String[] {
				LocalTestAuthStartupGuard.USER_1_PASSWORD_ENV,
				LocalTestAuthStartupGuard.USER_2_PASSWORD_ENV
		}) {
			Map<String, String> passwords = new HashMap<>(PASSWORDS);
			passwords.remove(variable);
			assertThrows(IllegalStateException.class, () -> LocalTestAuthStartupGuard.validate(
					environment(
							"local",
							LocalTestAuthStartupGuard.TEST_AUTH_PROFILE,
							properties(LOCAL_JDBC_URL, "127.0.0.1")),
					passwords));

			passwords.put(variable, " ");
			assertThrows(IllegalStateException.class, () -> LocalTestAuthStartupGuard.validate(
					environment(
							"local",
							LocalTestAuthStartupGuard.TEST_AUTH_PROFILE,
							properties(LOCAL_JDBC_URL, "127.0.0.1")),
					passwords));
		}
	}

	@Test
	void acceptsIpv4Ipv6AndLocalhostLoopbackJdbcHosts() {
		for (String jdbcUrl : new String[] {
				"jdbc:postgresql://127.0.0.1:5432/safepath",
				"jdbc:postgresql://[::1]:5432/safepath",
				"jdbc:postgresql://localhost:5432/safepath"
		}) {
			assertDoesNotThrow(() -> LocalTestAuthStartupGuard.validateLoopbackJdbcUrl(jdbcUrl));
		}
	}

	private static StandardEnvironment environment(String firstProfile, Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles(firstProfile);
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

	private static StandardEnvironment environment(
			String firstProfile,
			String secondProfile,
			Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles(firstProfile, secondProfile);
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

	private static StandardEnvironment environment(
			String firstProfile,
			String secondProfile,
			String thirdProfile,
			Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles(firstProfile, secondProfile, thirdProfile);
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

	private static Map<String, Object> properties(String jdbcUrl, String serverAddress) {
		return Map.of("spring.datasource.url", jdbcUrl, "server.address", serverAddress);
	}
}
