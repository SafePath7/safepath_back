package com.safepath.backend.security.local;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalTestSecurityConfigurationTest {

	private AnnotationConfigWebApplicationContext context;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		((StandardEnvironment) context.getEnvironment()).setActiveProfiles("local", "local-test-auth");
		context.register(TestWebConfiguration.class);
		context.refresh();
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void localProfileAloneDoesNotRegisterTestAccountsOrTestSecurityChain() {
		try (AnnotationConfigApplicationContext localContext = new AnnotationConfigApplicationContext()) {
			((StandardEnvironment) localContext.getEnvironment()).setActiveProfiles("local");
			localContext.register(LocalTestSecurityConfiguration.class, LocalTestUserDetailsConfiguration.class);
			localContext.refresh();

			assertFalse(localContext.containsBean("localTestSecurityFilterChain"));
			assertFalse(localContext.containsBean("localTestUserDetailsService"));
		}
	}

	@Test
	void localTestChainRequiresAuthenticationAndReturns401() throws Exception {
		mockMvc.perform(get("/test-principal"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
	}

	@Test
	void rejectsInvalidBasicCredentialsWith401() throws Exception {
		mockMvc.perform(get("/test-principal")
						.header("Authorization", basic("postman-user-1", "wrong-password")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/test-principal")
						.header("Authorization", basic("unknown-user", "synthetic-test-password-one")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatesBothUsersAndExposesOnlyTheirConfiguredPrincipalIds() throws Exception {
		mockMvc.perform(get("/test-principal")
						.header("Authorization", basic("postman-user-1", "synthetic-test-password-one")))
				.andExpect(status().isOk())
				.andExpect(content().string("9000000001"));

		mockMvc.perform(get("/test-principal")
						.header("Authorization", basic("postman-user-2", "synthetic-test-password-two")))
				.andExpect(status().isOk())
				.andExpect(content().string("9000000002"));
	}

	private static String basic(String username, String password) {
		String credentials = username + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@Import(LocalTestSecurityConfiguration.class)
	static class TestWebConfiguration {

		@Bean
		UserDetailsService localTestUserDetailsService(PasswordEncoder passwordEncoder) {
			return LocalTestUserDetailsConfiguration.createUserDetailsService(
					Map.of(
							LocalTestAuthStartupGuard.USER_1_PASSWORD_ENV, "synthetic-test-password-one",
							LocalTestAuthStartupGuard.USER_2_PASSWORD_ENV, "synthetic-test-password-two"),
					passwordEncoder);
		}

		@Bean
		TestPrincipalController testPrincipalController() {
			return new TestPrincipalController();
		}
	}

	@RestController
	static class TestPrincipalController {

		@GetMapping("/test-principal")
		String currentUserId(Authentication authentication) {
			if (!(authentication.getPrincipal() instanceof LocalTestUserPrincipal principal)) {
				throw new IllegalStateException(
						"Unexpected test authentication principal type: "
								+ authentication.getPrincipal().getClass().getName());
			}
			return principal.getUserId().toString();
		}
	}
}
