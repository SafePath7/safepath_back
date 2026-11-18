package com.safepath.backend.session.service;

import com.safepath.backend.session.exception.SessionChannelException;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.model.MonitoringPermissionType;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.LatestLocationStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitoredLocationTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore selected = new SelectedGuardianStore(sessions);
	private final LatestLocationStore locations = spy(new LatestLocationStore(sessions));
	private final InMemoryChannelRegistry channels = spy(new InMemoryChannelRegistry());
	private final GuardianPermissionService selectionPermissions = mock(GuardianPermissionService.class);
	private final SafeReturnSessionService lifecycle = new SafeReturnSessionService(sessions, selected,
			new SessionChannelStore(), channels, locations, new GuardianSelectionService(sessions, selected, selectionPermissions));
	private final GuardianPermissionService permissions = mock(GuardianPermissionService.class);
	private final GuardianMonitoringService monitoring = new GuardianMonitoringService(sessions, permissions, selected, locations, new SessionChannelStore());
	private final ActiveSession session = startSession();
	private final LocationUpdateRequest measurement = new LocationUpdateRequest(37.5, 127, null, Instant.now());

	@Test
	void acceptedSelectedGuardianWithLocationPermissionCanReadLatestLocation() {
		allowLocation();
		assertTrue(monitoring.getMonitoredLocation(session.id(), 43L).isEmpty());
		locations.updateLocation(session.id(), 42L, measurement);
		assertEquals(measurement, monitoring.getMonitoredLocation(session.id(), 43L).orElseThrow());
	}

	@Test
	void statusPermissionAloneCannotReadLocation() {
		selected.save(session.id(), Set.of(43L));
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(permissions.hasPermission(42L, 43L, MonitoringPermissionType.STATUS)).thenReturn(true);
		locations.updateLocation(session.id(), 42L, measurement);
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 43L));
	}

	@Test
	void unacceptedOrUnselectedGuardianCannotReadLocation() {
		locations.updateLocation(session.id(), 42L, measurement);
		selected.save(session.id(), Set.of(43L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 43L));
		allowLocation();
		selected.remove(session.id());
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 43L));
	}

	@Test
	void lookupFailureAndOwnerMonitoringCannotReturnLocation() {
		allowLocation();
		locations.updateLocation(session.id(), 42L, measurement);
		when(permissions.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION))
				.thenThrow(new IllegalStateException("Unavailable"));
		assertThrows(IllegalStateException.class, () -> monitoring.getMonitoredLocation(session.id(), 43L));
		assertThrows(AccessDeniedException.class, () -> monitoring.getMonitoredLocation(session.id(), 42L));
	}

	@Test
	void endDeletesMeasurementAndRejectsFurtherAccess() {
		allowLocation();
		locations.updateLocation(session.id(), 42L, measurement);
		lifecycle.endSession(session.id(), 42L);
		verify(locations).remove(session.id());
		assertThrows(NoSuchElementException.class, () -> monitoring.getMonitoredLocation(session.id(), 43L));
		assertThrows(NoSuchElementException.class, () -> locations.updateLocation(session.id(), 42L, measurement));
	}

	@Test
	void failedEndPreservesMeasurementForRetry() {
		locations.updateLocation(session.id(), 42L, measurement);
		assertThrows(SecurityException.class, () -> lifecycle.endSession(session.id(), 43L));
		assertEquals(measurement, locations.findBySessionId(session.id()).orElseThrow());
		doThrow(new IllegalStateException("Unavailable")).when(channels).close(any());
		assertThrows(SessionChannelException.class, () -> lifecycle.endSession(session.id(), 42L));
		assertEquals(measurement, locations.findBySessionId(session.id()).orElseThrow());
	}

	@Test
	void concurrentUpdateAndEndCannotRestoreEndedSessionLocation() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var update = executor.submit(() -> {
				start.await();
				try {
					locations.updateLocation(session.id(), 42L, measurement);
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
		verify(locations).remove(session.id());
		assertThrows(NoSuchElementException.class, () -> locations.findBySessionId(session.id()));
	}

	private void allowLocation() {
		selected.save(session.id(), Set.of(43L));
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(permissions.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION)).thenReturn(true);
	}

	private ActiveSession startSession() {
		when(selectionPermissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(selectionPermissions.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION)).thenReturn(true);
		return lifecycle.startSession(42L, Set.of(43L));
	}
}
