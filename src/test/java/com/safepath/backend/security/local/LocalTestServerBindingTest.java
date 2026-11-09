package com.safepath.backend.security.local;

import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
		classes = LocalTestServerBindingTest.TestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "server.port=0")
@ActiveProfiles({"local", "local-test-auth"})
class LocalTestServerBindingTest {

	@Autowired
	private ServletWebServerApplicationContext applicationContext;

	@Test
	void embeddedServerBindsOnlyToIpv4Loopback() throws Exception {
		TomcatWebServer webServer = (TomcatWebServer) applicationContext.getWebServer();
		AbstractProtocol<?> protocol =
				(AbstractProtocol<?>) webServer.getTomcat().getConnector().getProtocolHandler();
		assertEquals(
				InetAddress.getByName("127.0.0.1"),
				protocol.getAddress());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {
			DataSourceAutoConfiguration.class,
			HibernateJpaAutoConfiguration.class,
			FlywayAutoConfiguration.class
	})
	@Import({LocalTestSecurityConfiguration.class, TestUserConfiguration.class})
	static class TestApplication {
	}

	@Configuration(proxyBeanMethods = false)
	static class TestUserConfiguration {

		@Bean
		UserDetailsService localTestUserDetailsService(PasswordEncoder passwordEncoder) {
			return LocalTestUserDetailsConfiguration.createUserDetailsService(
					Map.of(
							LocalTestAuthStartupGuard.USER_1_PASSWORD_ENV, "synthetic-test-password-one",
							LocalTestAuthStartupGuard.USER_2_PASSWORD_ENV, "synthetic-test-password-two"),
					passwordEncoder);
		}
	}
}
