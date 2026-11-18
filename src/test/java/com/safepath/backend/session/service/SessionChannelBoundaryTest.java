package com.safepath.backend.session.service;

import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;
import com.safepath.backend.session.exception.SessionChannelException;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.user.UserService;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionChannelBoundaryTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore selected = new SelectedGuardianStore(sessions);
	private final SessionChannelStore mappings = new SessionChannelStore();
	private final InMemoryChannelRegistry channels = spy(new InMemoryChannelRegistry());
	private final GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
	private final GuardianRegistrationService registrations = new GuardianRegistrationService(
			repository, mock(GuardianRequestNotifier.class));
	private final GuardianPermissionService permissions = new GuardianPermissionAdapterService(registrations);
	private final GuardianSelectionService selection = new GuardianSelectionService(sessions, selected, permissions);
	private final SafeReturnSessionService lifecycle = new SafeReturnSessionService(
			sessions, selected, mappings, channels, selection);
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
	void twoSelectedGuardiansReadSameChannel() {
		startWithTwoGuardians();
		assertEquals(channelId, monitoring.getChannelId(session.id(), 43L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 44L));
	}

	@Test
	void acceptedButUnselectedGuardianCannotReadChannel() {
		startWithTwoGuardians();
		acceptedRegistration(42L, 45L);
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 45L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 43L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 44L));
	}

	@Test
	void permissionAndRelationshipRevocationBlockOnlyAffectedGuardianReads() {
		GuardianRegistration secondGuardian = startWithTwoGuardians();
		secondGuardian.changePermissions(false, true, false);
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 44L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 43L));
		secondGuardian.changePermissions(true, true, false);
		assertEquals(channelId, monitoring.getChannelId(session.id(), 44L));
		secondGuardian.changeStatus(RegistrationStatus.REVOKED);
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 44L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 43L));
	}

	@Test
	void channelsRemainIsolatedAfterAnotherSessionEnds() {
		startWithTwoGuardians();
		acceptedRegistration(45L, 46L);
		var otherSession = lifecycle.startSession(45L, List.of(46L));
		String otherChannel = mappings.findBySessionId(otherSession.id()).orElseThrow();
		for (Long guardianId : List.of(43L, 44L)) {
			assertEquals(channelId, monitoring.getChannelId(session.id(), guardianId));
			assertThrows(AccessDeniedException.class,
					() -> monitoring.getChannelId(otherSession.id(), guardianId));
		}
		assertEquals(otherChannel, monitoring.getChannelId(otherSession.id(), 46L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 46L));
		lifecycle.endSession(session.id(), 42L);
		for (Long guardianId : List.of(43L, 44L)) {
			assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), guardianId));
		}
		assertEquals(otherChannel, monitoring.getChannelId(otherSession.id(), 46L));
		assertTrue(channels.exists(otherChannel));
	}

	private GuardianRegistration startWithTwoGuardians() {
		GuardianRegistration second = acceptedRegistration(42L, 44L);
		lifecycle.endSession(session.id(), 42L);
		session = lifecycle.startSession(42L, List.of(43L, 44L));
		channelId = mappings.findBySessionId(session.id()).orElseThrow();
		return second;
	}

	private GuardianRegistration acceptedRegistration(Long ownerId, Long guardianId) {
		GuardianRegistration accepted = BeanUtils.instantiateClass(GuardianRegistration.class);
		accepted.changeStatus(RegistrationStatus.ACCEPTED);
		accepted.changePermissions(true, false, false);
		when(repository.findByUserIdAndGuardianId(ownerId, guardianId)).thenReturn(Optional.of(accepted));
		return accepted;
	}

	@Test
	void ownerCanReadChannelWithoutGuardianLookup() {
		assertEquals(channelId, monitoring.getChannelId(session.id(), 42L));
		verifyNoInteractions(repository);
	}

	@Test
	void authenticatedSelectedGuardianCanReadChannelThroughRealAdapter() {
		UserService users = mock(UserService.class);
		when(users.findUserIdByFirebaseUid("guardian-uid")).thenReturn(Optional.of(43L));
		var query = new GuardianMonitoringQueryService(users, monitoring);
		var authentication = UsernamePasswordAuthenticationToken.authenticated("guardian-uid", null, List.of());
		assertEquals(channelId, query.getChannelId(session.id(), authentication));
	}

	@Test
	void unselectedGuardianIsDeniedEvenWithAcceptedLocationPermission() {
		when(repository.findByUserIdAndGuardianId(42L, 44L)).thenReturn(Optional.of(registration));
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 44L));
	}

	@Test
	void revokedRelationshipIsDenied() {
		registration.changeStatus(RegistrationStatus.REVOKED);
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 43L));
	}

	@Test
	void missingRelationshipIsDenied() {
		when(repository.findByUserIdAndGuardianId(42L, 43L)).thenReturn(Optional.empty());
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 43L));
	}

	@Test
	void statusOnlyPermissionCannotRevealLocationChannel() {
		registration.changePermissions(false, true, false);
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 43L));
	}

	@Test
	void permissionLookupFailureDoesNotReturnChannel() {
		var failure = new DataAccessResourceFailureException("Lookup unavailable");
		when(repository.findByUserIdAndGuardianId(42L, 43L)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> monitoring.getChannelId(session.id(), 43L)));
	}

	@Test
	void nonexistentAndEndedSessionsCannotRevealChannel() {
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(UUID.randomUUID(), 42L));
		lifecycle.endSession(session.id(), 42L);
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), 42L));
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), 43L));
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
	}

	@Test
	void otherSessionChannelSurvivesEnd() {
		acceptedRegistration(45L, 46L);
		var other = lifecycle.startSession(45L, List.of(46L));
		String otherChannel = mappings.findBySessionId(other.id()).orElseThrow();
		lifecycle.endSession(session.id(), 42L);
		assertEquals(otherChannel, monitoring.getChannelId(other.id(), 45L));
		assertTrue(channels.exists(otherChannel));
	}

	@Test
	void missingMappingRejectsChannelLookup() {
		mappings.remove(session.id());
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), 42L));
	}

	@Test
	void failedChannelCleanupPreservesMapping() {
		doThrow(new IllegalStateException("Unavailable")).when(channels).close(channelId);
		assertThrows(SessionChannelException.class, () -> lifecycle.endSession(session.id(), 42L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 42L));
	}
}
