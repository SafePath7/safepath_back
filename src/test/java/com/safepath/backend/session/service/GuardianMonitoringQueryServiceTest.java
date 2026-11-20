package com.safepath.backend.session.service;

import com.safepath.backend.monitoring.service.GuardianMonitoringService;
import com.safepath.backend.monitoring.service.GuardianMonitoringQueryService;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GuardianMonitoringQueryServiceTest {
	private final FirebaseTokenService tokens = mock(FirebaseTokenService.class);
	private final UserService users = mock(UserService.class);
	private final GuardianMonitoringService monitoring = mock(GuardianMonitoringService.class);
	private final GuardianMonitoringQueryService queries = new GuardianMonitoringQueryService(users, monitoring);
	private final FirebaseAuthenticationFilter filter = new FirebaseAuthenticationFilter(tokens);
	private final UUID sessionId = UUID.randomUUID();

	@BeforeEach
	void clearBefore() {
		SecurityContextHolder.clearContext();
	}

	@AfterEach
	void clearAfter() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void channelLookupUsesFirebaseIdentityInsteadOfRequestGuardianId() throws Exception {
		var request = authenticatedRequest();
		request.setParameter("guardianId", "999");
		when(monitoring.getChannelId(sessionId, 43L)).thenReturn("test-channel");
		filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertEquals("test-channel", queries.getChannelId(sessionId,
					SecurityContextHolder.getContext().getAuthentication()));
		});
		verify(monitoring).getChannelId(sessionId, 43L);
		verifyNoMoreInteractions(monitoring);
	}

	@Test
	void guardianChannelLookupUsesAuthenticatedIdentityAndGuardianOnlyService() throws Exception {
		var request = authenticatedRequest();
		request.setParameter("guardianId", "999");
		when(monitoring.getGuardianChannelId(sessionId, 43L)).thenReturn("guardian-channel");
		filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertEquals("guardian-channel", queries.getGuardianChannelId(sessionId,
					SecurityContextHolder.getContext().getAuthentication()));
		});
		verify(monitoring).getGuardianChannelId(sessionId, 43L);
		verifyNoMoreInteractions(monitoring);
	}

	@Test
	void firebasePrincipalDeterminesGuardianIdDespiteForgedRequestIdentity() throws Exception {
		var request = authenticatedRequest();
		request.setParameter("guardianId", "999");
		request.addHeader("guardian-id", "999");
		request.addHeader("firebaseUid", "untrusted-uid");
		ActiveSession session = new ActiveSession(sessionId, 42L, Instant.now());
		when(monitoring.getMonitoringStatus(sessionId, 43L)).thenReturn(session);
		filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertSame(session, queries.getMonitoringStatus(sessionId,
					SecurityContextHolder.getContext().getAuthentication()));
		});
		verify(users).findUserIdByFirebaseUid("verified-uid");
		verify(monitoring).getMonitoringStatus(sessionId, 43L);
		verifyNoMoreInteractions(users, monitoring);
	}

	@Test
	void absentUnverifiedAnonymousOrNonFirebasePrincipalCannotReachLookup() {
		assertThrows(BadCredentialsException.class, () -> queries.getMonitoringStatus(sessionId, null));
		assertThrows(BadCredentialsException.class, () -> queries.getMonitoringStatus(sessionId,
				UsernamePasswordAuthenticationToken.unauthenticated("uid", null)));
		assertThrows(BadCredentialsException.class, () -> queries.getMonitoringStatus(sessionId,
				new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
		assertThrows(BadCredentialsException.class, () -> queries.getMonitoringStatus(sessionId,
				UsernamePasswordAuthenticationToken.authenticated(43L, null, List.of())));
		assertThrows(BadCredentialsException.class, () -> queries.getMonitoringStatus(sessionId,
				UsernamePasswordAuthenticationToken.authenticated(" ", null, List.of())));
		verifyNoInteractions(users, monitoring);
	}

	@Test
	void registeredFirebaseAccountIsRequired() throws Exception {
		var request = authenticatedRequest();
		when(users.findUserIdByFirebaseUid("verified-uid")).thenReturn(Optional.empty());
		filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertThrows(AccessDeniedException.class, () -> queries.getMonitoringStatus(sessionId,
					SecurityContextHolder.getContext().getAuthentication()));
		});
		verifyNoInteractions(monitoring);
	}

	@Test
	void userLookupFailureDoesNotReturnMonitoringData() throws Exception {
		var request = authenticatedRequest();
		var failure = new DataAccessResourceFailureException("Lookup unavailable");
		when(users.findUserIdByFirebaseUid("verified-uid")).thenThrow(failure);
		filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
					() -> queries.getMonitoringStatus(sessionId, SecurityContextHolder.getContext().getAuthentication())));
		});
		verifyNoInteractions(monitoring);
	}

	@Test
	void invalidFirebaseTokenStopsBeforeQueryBoundary() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer invalid-test-token");
		when(tokens.verifyIdToken("invalid-test-token")).thenThrow(new BadCredentialsException("Invalid"));
		var response = new MockHttpServletResponse();
		AtomicBoolean invoked = new AtomicBoolean();
		filter.doFilter(request, response, (incoming, outgoing) -> invoked.set(true));
		assertEquals(401, response.getStatus());
		assertFalse(invoked.get());
		verifyNoInteractions(users, monitoring);
	}

	@Test
	void missingTokenCannotQueryThroughAnonymousFilterChain() throws Exception {
		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (incoming, outgoing) -> {
			assertThrows(BadCredentialsException.class, () -> queries.getMonitoringStatus(sessionId,
					SecurityContextHolder.getContext().getAuthentication()));
		});
		verifyNoInteractions(tokens, users, monitoring);
	}

	private MockHttpServletRequest authenticatedRequest() {
		when(tokens.verifyIdToken("valid-test-token")).thenReturn("verified-uid");
		when(users.findUserIdByFirebaseUid("verified-uid")).thenReturn(Optional.of(43L));
		var request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer valid-test-token");
		return request;
	}
}
