package com.safepath.backend.session.service;

import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardianLocationSubscriptionTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore selected = new SelectedGuardianStore(sessions);
	private final SessionChannelStore mappings = spy(new SessionChannelStore());
	private final InMemoryChannelRegistry channels = new InMemoryChannelRegistry();
	private final GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
	private final GuardianPermissionService permissions = new GuardianPermissionAdapterService(
			new GuardianRegistrationService(repository, mock(GuardianRequestNotifier.class)));
	private final SafeReturnSessionService lifecycle = new SafeReturnSessionService(
			sessions, selected, mappings, channels, new GuardianSelectionService(sessions, selected, permissions), mock(com.safepath.backend.notification.NotificationService.class));
	private final GuardianMonitoringService monitoring = new GuardianMonitoringService(
			sessions, permissions, selected, mappings);
	private final GuardianRegistration registration = BeanUtils.instantiateClass(GuardianRegistration.class);
	private ActiveSession session;
	private String channelId;

	@BeforeEach
	void setUp() {
		registration.changeStatus(RegistrationStatus.ACCEPTED);
		registration.changePermissions(true, false, false);
		when(repository.findByUserIdAndGuardianId(42L, 43L)).thenReturn(Optional.of(registration));
		session = lifecycle.startSession(42L, List.of(43L));
		channelId = mappings.findBySessionId(session.id()).orElseThrow();
		clearInvocations(repository);
	}

	@Test
	void validatesSelectedGuardianThroughRealAdapterAndRegistrationService() {
		assertSame(session, monitoring.validateGuardianLocationSubscription(channelId, 43L));
	}

	@Test
	void guardianChannelLookupReturnsValidatedMappingAndRejectsOwner() {
		assertEquals(channelId, monitoring.getGuardianChannelId(session.id(), 43L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getGuardianChannelId(session.id(), 42L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 42L));
		assertThrows(IllegalArgumentException.class, () -> monitoring.getGuardianChannelId(null, 43L));
		for (Long invalidGuardian : new Long[]{null, 0L, -1L}) {
			assertThrows(IllegalArgumentException.class,
					() -> monitoring.getGuardianChannelId(session.id(), invalidGuardian));
		}
	}

	@Test
	void guardianChannelLookupRejectsAnotherAuthorizedSessionsChannel() {
		when(repository.findByUserIdAndGuardianId(45L, 43L)).thenReturn(Optional.of(registration));
		ActiveSession other = lifecycle.startSession(45L, List.of(43L));
		String otherChannel = mappings.findBySessionId(other.id()).orElseThrow();
		when(mappings.findBySessionId(session.id())).thenReturn(Optional.of(otherChannel));
		assertThrows(AccessDeniedException.class, () -> monitoring.getGuardianChannelId(session.id(), 43L));
		assertEquals(otherChannel, monitoring.getGuardianChannelId(other.id(), 43L));
	}

	@Test
	void rejectsStatusOnlyPermissionAndSubsequentLocationRevocation() {
		assertSame(session, monitoring.validateGuardianLocationSubscription(channelId, 43L));
		registration.changePermissions(false, true, false);
		assertThrows(AccessDeniedException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
	}

	@Test
	void rejectsAllUnacceptedRelationshipStates() {
		for (RegistrationStatus status : RegistrationStatus.values()) {
			if (status == RegistrationStatus.ACCEPTED) continue;
			registration.changeStatus(status);
			assertThrows(AccessDeniedException.class,
					() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
		}
	}

	@Test
	void rejectsMissingRelationshipAndUnselectedGuardian() {
		assertThrows(AccessDeniedException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 44L));
		selected.save(session.id(), Set.of());
		assertThrows(AccessDeniedException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
	}

	@Test
	void rejectsUnknownRemovedAndInactiveSessionMappings() {
		assertThrows(NoSuchElementException.class,
				() -> monitoring.validateGuardianLocationSubscription("unknown-channel", 43L));
		String staleChannel = "stale-channel";
		mappings.save(UUID.randomUUID(), staleChannel);
		assertThrows(NoSuchElementException.class,
				() -> monitoring.validateGuardianLocationSubscription(staleChannel, 43L));
		lifecycle.endSession(session.id(), 42L);
		assertTrue(mappings.findSessionIdByChannelId(channelId).isEmpty());
		assertThrows(NoSuchElementException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
		verifyNoInteractions(repository);
	}

	@Test
	void guardianBoundaryDoesNotChangeExistingOwnerPublicationFlow() {
		assertThrows(AccessDeniedException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 42L));
		channels.join(channelId, 42L, "owner-websocket-session");
		assertEquals(42L, channels.participantIdForSession(channelId, "owner-websocket-session").orElseThrow());
		assertTrue(channels.isLocationPublisher(channelId, 42L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 42L));
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsInvalidInputsBeforePermissionLookup() {
		for (String invalidChannel : new String[]{null, "", " "}) {
			assertThrows(IllegalArgumentException.class,
					() -> monitoring.validateGuardianLocationSubscription(invalidChannel, 43L));
		}
		for (Long invalidGuardian : new Long[]{null, 0L, -1L}) {
			assertThrows(IllegalArgumentException.class,
					() -> monitoring.validateGuardianLocationSubscription(channelId, invalidGuardian));
		}
		verifyNoInteractions(repository);
	}

	@Test
	void lookupFailurePropagatesWithoutGrantingAccess() {
		var failure = new DataAccessResourceFailureException("Permission lookup unavailable");
		when(repository.findByUserIdAndGuardianId(42L, 43L)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 43L)));
		doReturn(Optional.of(registration)).doThrow(failure)
				.when(repository).findByUserIdAndGuardianId(42L, 43L);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 43L)));
	}

	@Test
	void rejectsAmbiguousAndMismatchedMappingsBeforePermissionLookup() {
		UUID otherSession = UUID.randomUUID();
		mappings.save(otherSession, channelId);
		assertThrows(IllegalStateException.class,
				() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
		mappings.remove(otherSession);
		when(mappings.findSessionIdByChannelId("mismatched-channel")).thenReturn(Optional.of(session.id()));
		assertThrows(AccessDeniedException.class,
				() -> monitoring.validateGuardianLocationSubscription("mismatched-channel", 43L));
		verifyNoInteractions(repository);
	}

	@Test
	void anotherActiveSessionDoesNotAuthorizeAccessToItsChannel() {
		when(repository.findByUserIdAndGuardianId(45L, 46L)).thenReturn(Optional.of(registration));
		ActiveSession other = lifecycle.startSession(45L, List.of(46L));
		String otherChannel = mappings.findBySessionId(other.id()).orElseThrow();
		assertThrows(AccessDeniedException.class,
				() -> monitoring.validateGuardianLocationSubscription(otherChannel, 43L));
		lifecycle.endSession(session.id(), 42L);
		assertEquals(other.id(), mappings.findSessionIdByChannelId(otherChannel).orElseThrow());
		assertTrue(channels.exists(otherChannel));
	}

	@Test
	void sessionEndWaitsForValidationAndSubsequentValidationIsDenied() throws Exception {
		CountDownLatch lookupEntered = new CountDownLatch(1);
		CountDownLatch releaseLookup = new CountDownLatch(1);
		CountDownLatch endRequested = new CountDownLatch(1);
		when(repository.findByUserIdAndGuardianId(42L, 43L)).thenAnswer(invocation -> {
			lookupEntered.countDown();
			if (!releaseLookup.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Lookup timed out");
			return Optional.of(registration);
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			try {
				var validation = executor.submit(() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
				assertTrue(lookupEntered.await(5, TimeUnit.SECONDS));
				var ending = executor.submit(() -> {
					endRequested.countDown();
					lifecycle.endSession(session.id(), 42L);
				});
				assertTrue(endRequested.await(5, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> ending.get(100, TimeUnit.MILLISECONDS));
				releaseLookup.countDown();
				assertSame(session, validation.get(5, TimeUnit.SECONDS));
				ending.get(5, TimeUnit.SECONDS);
				assertTrue(mappings.findSessionIdByChannelId(channelId).isEmpty());
				assertThrows(NoSuchElementException.class,
						() -> monitoring.validateGuardianLocationSubscription(channelId, 43L));
			} finally {
				releaseLookup.countDown();
			}
		}
	}
}
