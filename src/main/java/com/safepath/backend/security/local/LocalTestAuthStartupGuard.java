package com.safepath.backend.security.local;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class LocalTestAuthStartupGuard implements BeanFactoryPostProcessor, EnvironmentAware {

	static final String LOCAL_PROFILE = "local";
	static final String TEST_AUTH_PROFILE = "local-test-auth";
	static final String USER_1_PASSWORD_ENV = "SAFEPATH_LOCAL_TEST_USER_1_PASSWORD";
	static final String USER_2_PASSWORD_ENV = "SAFEPATH_LOCAL_TEST_USER_2_PASSWORD";

	private static final Set<String> ALLOWED_PROFILES = Set.of(LOCAL_PROFILE, TEST_AUTH_PROFILE);
	private static final String JDBC_URL_PREFIX = "jdbc:postgresql://";
	private static final List<String> UNSUPPORTED_DATASOURCE_PROPERTIES = List.of(
			"spring.datasource.jndi-name",
			"spring.datasource.hikari.data-source-class-name",
			"spring.datasource.hikari.data-source-properties.host",
			"spring.datasource.hikari.data-source-properties.hostaddr",
			"spring.datasource.hikari.data-source-properties.socketfactory",
			"spring.datasource.hikari.data-source-properties.socketfactoryarg",
			"spring.datasource.type");

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		validate(environment, System.getenv());
	}

	static void validate(Environment environment, Map<String, String> environmentVariables) {
		Set<String> activeProfiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
		if (!activeProfiles.contains(TEST_AUTH_PROFILE)) {
			return;
		}

		if (!activeProfiles.contains(LOCAL_PROFILE) || !ALLOWED_PROFILES.containsAll(activeProfiles)) {
			throw new IllegalStateException(
					"Local test authentication requires only the 'local' and 'local-test-auth' profiles");
		}

		if (!"127.0.0.1".equals(environment.getProperty("server.address"))) {
			throw new IllegalStateException("Local test authentication must bind the server to 127.0.0.1");
		}

		List<String> jdbcUrls = new ArrayList<>();
		addIfPresent(jdbcUrls, environment.getProperty("spring.datasource.url"));
		addIfPresent(jdbcUrls, environment.getProperty("spring.datasource.hikari.jdbc-url"));
		if (jdbcUrls.isEmpty() || UNSUPPORTED_DATASOURCE_PROPERTIES.stream()
				.anyMatch(property -> hasProperty(environment, property))) {
			throw new IllegalStateException(
					"Local test authentication requires an explicit, supported local JDBC URL");
		}
		jdbcUrls.forEach(LocalTestAuthStartupGuard::validateLoopbackJdbcUrl);

		requireEnvironmentVariable(environmentVariables, USER_1_PASSWORD_ENV);
		requireEnvironmentVariable(environmentVariables, USER_2_PASSWORD_ENV);
	}

	private static void addIfPresent(List<String> values, String value) {
		if (value != null && !value.isBlank()) {
			values.add(value);
		}
	}

	private static boolean hasProperty(Environment environment, String name) {
		String value = environment.getProperty(name);
		return value != null && !value.isBlank();
	}

	private static void requireEnvironmentVariable(Map<String, String> environmentVariables, String name) {
		String value = environmentVariables.get(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Required local test credential environment variable is missing: " + name);
		}
	}

	static void validateLoopbackJdbcUrl(String jdbcUrl) {
		if (jdbcUrl == null || !jdbcUrl.startsWith(JDBC_URL_PREFIX) || jdbcUrl.indexOf('#') >= 0) {
			throw new IllegalStateException("Local test authentication requires a supported PostgreSQL JDBC URL");
		}

		String location = jdbcUrl.substring(JDBC_URL_PREFIX.length());
		int authorityEnd = firstIndexOf(location, '/', '?');
		String authority = authorityEnd < 0 ? location : location.substring(0, authorityEnd);
		if (authority.isBlank() || authority.indexOf('@') >= 0 || authority.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalStateException("Local test authentication requires a loopback database host");
		}

		int queryStart = location.indexOf('?');
		if (queryStart >= 0) {
			validateQueryParameters(location.substring(queryStart + 1));
		}

		for (String hostAndPort : authority.split(",", -1)) {
			validateLoopbackHost(parseHost(hostAndPort));
		}
	}

	private static int firstIndexOf(String value, char first, char second) {
		int firstIndex = value.indexOf(first);
		int secondIndex = value.indexOf(second);
		if (firstIndex < 0) {
			return secondIndex;
		}
		return secondIndex < 0 ? firstIndex : Math.min(firstIndex, secondIndex);
	}

	private static String parseHost(String hostAndPort) {
		String host;
		String port = null;
		if (hostAndPort.startsWith("[")) {
			int closingBracket = hostAndPort.indexOf(']');
			if (closingBracket < 0) {
				throw new IllegalStateException("Local test authentication requires a valid loopback database host");
			}
			host = hostAndPort.substring(1, closingBracket);
			String suffix = hostAndPort.substring(closingBracket + 1);
			if (!suffix.isEmpty()) {
				if (!suffix.startsWith(":")) {
					throw new IllegalStateException("Local test authentication requires a valid loopback database host");
				}
				port = suffix.substring(1);
			}
		}
		else {
			int colon = hostAndPort.indexOf(':');
			if (colon >= 0) {
				if (colon != hostAndPort.lastIndexOf(':')) {
					throw new IllegalStateException("IPv6 database hosts must use bracket notation");
				}
				host = hostAndPort.substring(0, colon);
				port = hostAndPort.substring(colon + 1);
			}
			else {
				host = hostAndPort;
			}
		}

		if (host.isBlank() || (port != null && !isValidPort(port))) {
			throw new IllegalStateException("Local test authentication requires a valid loopback database host");
		}
		return host;
	}

	private static boolean isValidPort(String port) {
		try {
			int value = Integer.parseInt(port);
			return value >= 1 && value <= 65535;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	private static void validateQueryParameters(String query) {
		for (String parameter : query.split("&")) {
			String name = parameter.split("=", 2)[0];
			String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
			if (Set.of("host", "hostaddr", "socketfactory", "socketfactoryarg").contains(
					decodedName.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException(
						"Local test authentication does not allow JDBC connection-host overrides");
			}
		}
	}

	private static void validateLoopbackHost(String host) {
		if (host.indexOf('%') >= 0) {
			throw new IllegalStateException("Local test authentication requires a loopback database host");
		}

		try {
			InetAddress[] addresses;
			if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("localhost.")) {
				addresses = InetAddress.getAllByName(host);
			}
			else if (host.indexOf(':') >= 0) {
				addresses = new InetAddress[] {InetAddress.getByName(host)};
			}
			else {
				addresses = new InetAddress[] {parseIpv4Address(host)};
			}

			if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !address.isLoopbackAddress())) {
				throw new IllegalStateException("Local test authentication requires a loopback database host");
			}
		}
		catch (UnknownHostException exception) {
			throw new IllegalStateException("Local test authentication requires a loopback database host");
		}
	}

	private static InetAddress parseIpv4Address(String host) throws UnknownHostException {
		String[] octets = host.split("\\.", -1);
		if (octets.length != 4) {
			throw new UnknownHostException();
		}
		byte[] address = new byte[4];
		for (int index = 0; index < octets.length; index++) {
			try {
				int value = Integer.parseInt(octets[index]);
				if (value < 0 || value > 255 || !Integer.toString(value).equals(octets[index])) {
					throw new UnknownHostException();
				}
				address[index] = (byte) value;
			}
			catch (NumberFormatException exception) {
				throw new UnknownHostException();
			}
		}
		return InetAddress.getByAddress(address);
	}
}
