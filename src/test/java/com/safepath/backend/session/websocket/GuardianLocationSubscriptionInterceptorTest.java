package com.safepath.backend.session.websocket;

import com.safepath.backend.session.service.GuardianMonitoringService;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.StompAuthenticationChannelInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GuardianLocationSubscriptionInterceptorTest {
	private static final String IDENTITY_KEY = StompAuthenticationChannelInterceptor.class.getName() + ".authenticatedUserId";
	private final GuardianMonitoringService monitoring = mock(GuardianMonitoringService.class);
	private final InMemoryChannelRegistry registry = mock(InMemoryChannelRegistry.class);
	private final GuardianLocationSubscriptionInterceptor interceptor =
			new GuardianLocationSubscriptionInterceptor(monitoring, registry);

	@Test
	void connectSendAndConnectionManagementAreUnchanged() {
		for (StompCommand command : new StompCommand[]{StompCommand.CONNECT, StompCommand.SEND,
				StompCommand.DISCONNECT, StompCommand.UNSUBSCRIBE}) {
			var accessor = StompHeaderAccessor.create(command);
			accessor.setDestination("/topic/connections/channel-1/locations");
			Message<?> message = message(accessor);
			assertSame(message, interceptor.preSend(message, null));
		}
		var heartbeat = StompHeaderAccessor.createForHeartbeat();
		Message<?> message = message(heartbeat);
		assertSame(message, interceptor.preSend(message, null));
		verifyNoInteractions(monitoring, registry);
	}

	@Test
	void unrelatedAndMalformedDestinationsKeepExistingPolicy() {
		for (String destination : new String[]{"/topic/other", "/topic/**",
				"/topic/connections/channel-1/extra/locations", "/topic/connections//locations"}) {
			var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
			accessor.setDestination(destination);
			Message<?> message = message(accessor);
			assertSame(message, interceptor.preSend(message, null));
		}
		verifyNoInteractions(monitoring, registry);
	}

	@Test
	void validatesGuardianUsingOnlyServerSessionIdentityWithoutJoining() {
		var accessor = subscription();
		accessor.setNativeHeader("guardianId", "999");
		accessor.setNativeHeader("userId", "999");
		Message<?> message = message(accessor);
		assertSame(message, interceptor.preSend(message, null));
		verify(registry).isLocationPublisher("channel-1", 43L);
		verify(monitoring).validateGuardianLocationSubscription("channel-1", 43L);
		verifyNoMoreInteractions(registry, monitoring);
	}

	@Test
	void ownerKeepsExistingSubscriptionPathWithoutGuardianValidationOrDuplicateJoin() {
		when(registry.isLocationPublisher("channel-1", 43L)).thenReturn(true);
		Message<?> message = message(subscription());
		assertSame(message, interceptor.preSend(message, null));
		verify(registry).isLocationPublisher("channel-1", 43L);
		verifyNoMoreInteractions(registry);
		verifyNoInteractions(monitoring);
	}

	@Test
	void nativeHeadersAndPrincipalCannotReplaceVerifiedSessionAttribute() {
		var accessor = subscription();
		accessor.setSessionAttributes(Map.of());
		accessor.setNativeHeader(IDENTITY_KEY, "43");
		assertThrows(BadCredentialsException.class, () -> interceptor.preSend(message(accessor), null));
		verifyNoInteractions(monitoring, registry);
	}

	@Test
	void mismatchedOrUnauthenticatedPrincipalIsDenied() {
		var mismatched = subscription();
		mismatched.setUser(UsernamePasswordAuthenticationToken.authenticated(999L, null, null));
		assertThrows(BadCredentialsException.class, () -> interceptor.preSend(message(mismatched), null));
		var unauthenticated = subscription();
		unauthenticated.setUser(UsernamePasswordAuthenticationToken.unauthenticated(43L, null));
		assertThrows(BadCredentialsException.class, () -> interceptor.preSend(message(unauthenticated), null));
		verifyNoInteractions(monitoring, registry);
	}

	@Test
	void absentSessionOrInvalidServerIdentityIsDenied() {
		var missingSession = subscription();
		missingSession.setSessionId(null);
		assertThrows(BadCredentialsException.class, () -> interceptor.preSend(message(missingSession), null));
		for (Object invalid : new Object[]{"43", 0L, -1L}) {
			var accessor = subscription();
			accessor.setSessionAttributes(Map.of(IDENTITY_KEY, invalid));
			assertThrows(BadCredentialsException.class, () -> interceptor.preSend(message(accessor), null));
		}
		verifyNoInteractions(monitoring, registry);
	}

	@Test
	void lookupFailureIsPropagatedBeforeAnyJoin() {
		var failure = new IllegalStateException("Permission lookup unavailable");
		doThrow(failure).when(monitoring).validateGuardianLocationSubscription("channel-1", 43L);
		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> interceptor.preSend(message(subscription()), null)));
		verify(registry).isLocationPublisher("channel-1", 43L);
		verifyNoMoreInteractions(registry);
	}

	private StompHeaderAccessor subscription() {
		var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setDestination("/topic/connections/channel-1/locations");
		accessor.setSessionId("verified-session");
		accessor.setSessionAttributes(Map.of(IDENTITY_KEY, 43L));
		accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(43L, null, null));
		return accessor;
	}

	private Message<byte[]> message(StompHeaderAccessor accessor) {
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}
}
