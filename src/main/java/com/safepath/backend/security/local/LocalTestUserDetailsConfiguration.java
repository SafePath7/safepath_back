package com.safepath.backend.security.local;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Profile("local & local-test-auth")
public class LocalTestUserDetailsConfiguration {

	static final String USERNAME_1 = "postman-user-1";
	static final String USERNAME_2 = "postman-user-2";
	static final Long USER_ID_1 = 9_000_000_001L;
	static final Long USER_ID_2 = 9_000_000_002L;

	@Bean
	UserDetailsService localTestUserDetailsService(PasswordEncoder passwordEncoder) {
		return createUserDetailsService(System.getenv(), passwordEncoder);
	}

	static UserDetailsService createUserDetailsService(
			Map<String, String> environmentVariables,
			PasswordEncoder passwordEncoder) {
		String password1 = requiredPassword(environmentVariables, LocalTestAuthStartupGuard.USER_1_PASSWORD_ENV);
		String password2 = requiredPassword(environmentVariables, LocalTestAuthStartupGuard.USER_2_PASSWORD_ENV);

		Map<String, LocalTestUserPrincipal> users = new HashMap<>();
		users.put(USERNAME_1, new LocalTestUserPrincipal(USERNAME_1, passwordEncoder.encode(password1), USER_ID_1));
		users.put(USERNAME_2, new LocalTestUserPrincipal(USERNAME_2, passwordEncoder.encode(password2), USER_ID_2));
		return username -> {
			LocalTestUserPrincipal user = users.get(username);
			if (user == null) {
				throw new UsernameNotFoundException("Invalid local test credentials");
			}
			return user;
		};
	}

	private static String requiredPassword(Map<String, String> environmentVariables, String name) {
		String password = environmentVariables.get(name);
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("Required local test credential environment variable is missing: " + name);
		}
		return password;
	}
}
