package com.safepath.backend.session.service;

import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;
import com.safepath.backend.session.exception.SessionChannelException;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.LatestLocationStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.user.UserService;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionChannelBoundaryTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore selected = new SelectedGuardianStore(sessions);
	private final SessionChannelStore mappings = new SessionChannelStore();
	private final LatestLocationStore locations = spy(new LatestLocationStore(sessions));
	private final InMemoryChannelRegistry channels = spy(new InMemoryChannelRegistry());
	private final GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
	private final GuardianRegistrationService registrations = new GuardianRegistrationService(
			repository, mock(GuardianRequestNotifier.class));
	private final GuardianPermissionService permissions = new GuardianPermissionAdapterService(registrations);
	private final GuardianSelectionService selection = new GuardianSelectionService(sessions, selected, permissions);
	private final SafeReturnSessionService lifecycle = new SafeReturnSessionService(
			sessions, selected, mappings, channels, locations, selection);
	private final GuardianMonitoringService monitoring = new GuardianMonitoringService(
			sessions, permissions, selected, locations, mappings);
	private final GuardianRegistration registration = BeanUtils.instantiateClass(GuardianRegistration.class);
	private final Instant measuredAt = Instant.parse("2026-11-15T00:00:00Z");
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
	void twoSelectedGuardiansReadSameStoredLocationAndChannel() {
		startWithTwoGuardians();
		var snapshot = measurement(measuredAt);
		lifecycle.updateLocation(channelId, 42L, snapshot);
		assertSame(snapshot, locations.findBySessionId(session.id()).orElseThrow());
		assertSame(snapshot, monitoring.getMonitoredLocation(session.id(), 43L).orElseThrow());
		assertSame(snapshot, monitoring.getMonitoredLocation(session.id(), 44L).orElseThrow());
		assertEquals(channelId, monitoring.getChannelId(session.id(), 43L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 44L));
	}

	@Test
	void acceptedButUnselectedGuardianCannotReadSharedLocationOrChannel() {
		startWithTwoGuardians();
		acceptedRegistration(42L, 45L);
		lifecycle.updateLocation(channelId, 42L, measurement(measuredAt));
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 45L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 45L));
		assertTrue(monitoring.getMonitoredLocation(session.id(), 43L).isPresent());
		assertTrue(monitoring.getMonitoredLocation(session.id(), 44L).isPresent());
	}

	@Test
	void permissionAndRelationshipRevocationBlockOnlyAffectedGuardianReads() {
		GuardianRegistration secondGuardian = startWithTwoGuardians();
		var snapshot = measurement(measuredAt);
		lifecycle.updateLocation(channelId, 42L, snapshot);
		secondGuardian.changePermissions(false, true, false);
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 44L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 44L));
		assertSame(snapshot, monitoring.getMonitoredLocation(session.id(), 43L).orElseThrow());
		secondGuardian.changePermissions(true, true, false);
		assertSame(snapshot, monitoring.getMonitoredLocation(session.id(), 44L).orElseThrow());
		secondGuardian.changeStatus(RegistrationStatus.REVOKED);
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 44L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 44L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 43L));
	}

	@Test
	void sharedSnapshotsRespectMeasurementOrderEndAndSessionIsolation() {
		startWithTwoGuardians();
		acceptedRegistration(45L, 46L);
		var otherSession = lifecycle.startSession(45L, List.of(46L));
		String otherChannel = mappings.findBySessionId(otherSession.id()).orElseThrow();
		var otherSnapshot = new LocationUpdateRequest(35.1, 129.0, 10.0, measuredAt);
		lifecycle.updateLocation(otherChannel, 45L, otherSnapshot);
		var latest = measurement(measuredAt.plusSeconds(1));
		lifecycle.updateLocation(channelId, 42L, latest);
		lifecycle.updateLocation(channelId, 42L, measurement(measuredAt));
		for (Long guardianId : List.of(43L, 44L)) {
			assertSame(latest, monitoring.getMonitoredLocation(session.id(), guardianId).orElseThrow());
			assertThrows(AccessDeniedException.class,
					() -> monitoring.getMonitoredLocation(otherSession.id(), guardianId));
		}
		assertSame(otherSnapshot, monitoring.getMonitoredLocation(otherSession.id(), 46L).orElseThrow());
		assertThrows(AccessDeniedException.class, () -> monitoring.getChannelId(session.id(), 46L));
		lifecycle.endSession(session.id(), 42L);
		for (Long guardianId : List.of(43L, 44L)) {
			assertThrows(NoSuchElementException.class, () -> monitoring.getMonitoredLocation(session.id(), guardianId));
			assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), guardianId));
		}
		assertSame(otherSnapshot, monitoring.getMonitoredLocation(otherSession.id(), 46L).orElseThrow());
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
	void nonexistentAndEndedSessionsCannotRevealChannelOrStoreLocation() {
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(UUID.randomUUID(), 42L));
		lifecycle.updateLocation(channelId, 42L, measurement(measuredAt));
		lifecycle.endSession(session.id(), 42L);
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), 42L));
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), 43L));
		assertThrows(NoSuchElementException.class, () -> lifecycle.updateLocation(channelId, 42L, measurement(measuredAt)));
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
		verify(locations).remove(session.id());
	}

	@Test
	void mappedOwnerCanStoreLocationAndOlderMeasurementsAreIgnored() {
		var latest = measurement(measuredAt);
		assertSame(latest, lifecycle.updateLocation(channelId, 42L, latest));
		assertSame(latest, lifecycle.updateLocation(session.id(), channelId, 42L, measurement(measuredAt.minusSeconds(1))));
		assertEquals(latest, monitoring.getMonitoredLocation(session.id(), 43L).orElseThrow());
	}

	@Test
	void guardianCannotWriteEvenIfTheyOwnAnotherActiveSession() {
		acceptedRegistration(43L, 44L);
		lifecycle.startSession(43L, List.of(44L));
		assertThrows(SecurityException.class,
				() -> lifecycle.updateLocation(session.id(), channelId, 43L, measurement(measuredAt)));
		assertThrows(SecurityException.class, () -> lifecycle.updateLocation(channelId, 43L, measurement(measuredAt)));
		assertTrue(locations.findBySessionId(session.id()).isEmpty());
	}

	@Test
	void mismatchedSessionChannelIsDeniedAndOtherSessionSurvivesEnd() {
		acceptedRegistration(45L, 46L);
		var other = lifecycle.startSession(45L, List.of(46L));
		String otherChannel = mappings.findBySessionId(other.id()).orElseThrow();
		var otherLocation = measurement(measuredAt);
		lifecycle.updateLocation(otherChannel, 45L, otherLocation);
		assertThrows(SecurityException.class,
				() -> lifecycle.updateLocation(session.id(), otherChannel, 42L, measurement(measuredAt)));
		lifecycle.endSession(session.id(), 42L);
		assertEquals(otherLocation, locations.findBySessionId(other.id()).orElseThrow());
		assertEquals(otherChannel, monitoring.getChannelId(other.id(), 45L));
		assertTrue(channels.exists(otherChannel));
	}

	@Test
	void missingMappingAndClosedChannelRejectWrites() {
		mappings.remove(session.id());
		assertThrows(NoSuchElementException.class, () -> monitoring.getChannelId(session.id(), 42L));
		assertThrows(NoSuchElementException.class, () -> lifecycle.updateLocation(channelId, 42L, measurement(measuredAt)));
		mappings.save(session.id(), channelId);
		channels.close(channelId);
		assertThrows(SecurityException.class, () -> lifecycle.updateLocation(channelId, 42L, measurement(measuredAt)));
	}

	@Test
	void failedChannelCleanupPreservesLocationAndMapping() {
		var location = measurement(measuredAt);
		lifecycle.updateLocation(channelId, 42L, location);
		doThrow(new IllegalStateException("Unavailable")).when(channels).close(channelId);
		assertThrows(SessionChannelException.class, () -> lifecycle.endSession(session.id(), 42L));
		assertEquals(channelId, monitoring.getChannelId(session.id(), 42L));
		assertEquals(location, locations.findBySessionId(session.id()).orElseThrow());
	}

	@Test
	void endAndLocationWriteCannotRestoreEndedSessionData() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var update = executor.submit(() -> {
				start.await();
				try {
					lifecycle.updateLocation(channelId, 42L, measurement(measuredAt));
				} catch (NoSuchElementException ended) {
					return false;
				}
				return true;
			});
			var end = executor.submit(() -> {
				start.await();
				lifecycle.endSession(session.id(), 42L);
				return true;
			});
			start.countDown();
			update.get(5, TimeUnit.SECONDS);
			end.get(5, TimeUnit.SECONDS);
		}
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
		assertThrows(NoSuchElementException.class, () -> locations.findBySessionId(session.id()));
		verify(locations).remove(session.id());
	}

	private LocationUpdateRequest measurement(Instant capturedAt) {
		return new LocationUpdateRequest(37.5, 127, 5.0, capturedAt);
	}
}
