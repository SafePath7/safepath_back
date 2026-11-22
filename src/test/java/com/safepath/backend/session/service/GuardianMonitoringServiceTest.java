package com.safepath.backend.session.service;

import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.monitoring.service.GuardianMonitoringService;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GuardianMonitoringServiceTest {

	private final ActiveSessionStore activeSessionStore = new ActiveSessionStore();
	private final SelectedGuardianStore selectedGuardianStore = new SelectedGuardianStore(activeSessionStore);
	private final GuardianRegistrationService permissionService = mock(GuardianRegistrationService.class);
	private final GuardianRegistrationService selectionPermissionService = mock(GuardianRegistrationService.class);
	private final SafeReturnSessionService sessionService = new SafeReturnSessionService(activeSessionStore, selectedGuardianStore, new SessionChannelStore(), new InMemoryChannelRegistry(), selectionPermissionService, mock(com.safepath.backend.notification.NotificationService.class), org.mockito.Mockito.mock(com.safepath.backend.risk.service.RiskSessionBridge.class));
	private final GuardianMonitoringService monitoringService = new GuardianMonitoringService(
			activeSessionStore, permissionService, selectedGuardianStore, new SessionChannelStore());
	private ActiveSession session;
	private static final Long OWNER_ID = 42L;
	private static final Long GUARDIAN_ID = 43L;

	@BeforeEach
	void setUp() {

		when(selectionPermissionService.hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION)).thenReturn(true);
		session = sessionService.startSession(OWNER_ID, Set.of(GUARDIAN_ID));
	}

	@Test
	void findsOnlyAnActiveSessionThatSelectedTheAuthorizedGuardian() {
		allowStatus();
		assertSame(session, monitoringService.findActiveSession(OWNER_ID, GUARDIAN_ID).orElseThrow());
		selectedGuardianStore.remove(session.id());
		org.junit.jupiter.api.Assertions.assertTrue(monitoringService.findActiveSession(OWNER_ID, GUARDIAN_ID).isEmpty());
		selectedGuardianStore.save(session.id(), Set.of(GUARDIAN_ID));
		sessionService.endSession(session.id(), OWNER_ID);
		org.junit.jupiter.api.Assertions.assertTrue(monitoringService.findActiveSession(OWNER_ID, GUARDIAN_ID).isEmpty());
	}

	@Test
	void refusesActiveSessionLookupWithoutLocationPermission() {
		assertThrows(AccessDeniedException.class, () -> monitoringService.findActiveSession(OWNER_ID, GUARDIAN_ID));
		assertThrows(AccessDeniedException.class, () -> monitoringService.findActiveSession(OWNER_ID, OWNER_ID));
		assertThrows(IllegalArgumentException.class, () -> monitoringService.findActiveSession(0L, GUARDIAN_ID));
	}

	@Test
	void returnsExistingSessionForAcceptedSelectedGuardianWithStatusPermission() {
		allowStatus();
		assertSame(session, monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
		verify(permissionService).hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION);
		verifyNoMoreInteractions(permissionService);
	}

	@Test
	void rejectsUnacceptedRelationshipBeforeCheckingPermission() {
		assertThrows(AccessDeniedException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
		verify(permissionService).hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION);
		verifyNoMoreInteractions(permissionService);
	}

	@Test
	void rejectsMissingStatusPermission() {

		assertThrows(AccessDeniedException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
	}

	@Test
	void rejectsUnselectedGuardianEvenWhenRelationshipAndPermissionAreAllowed() {
		allowStatus();
		selectedGuardianStore.remove(session.id());
		assertThrows(AccessDeniedException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
		selectedGuardianStore.save(session.id(), Set.of());
		assertThrows(AccessDeniedException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
	}

	@Test
	void rejectsMissingAndEndedSessions() {
		assertThrows(NoSuchElementException.class,
				() -> monitoringService.getMonitoringStatus(UUID.randomUUID(), GUARDIAN_ID));
		sessionService.endSession(session.id(), OWNER_ID);
		assertThrows(NoSuchElementException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
		verifyNoInteractions(permissionService);
	}

	@Test
	void rejectsOwnerEvenWhenSelected() {
		selectedGuardianStore.save(session.id(), Set.of(OWNER_ID));
		assertThrows(AccessDeniedException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), OWNER_ID));
		verifyNoInteractions(permissionService);
	}

	@Test
	void propagatesRelationshipLookupFailureWithoutReturningSession() {
		IllegalStateException failure = new IllegalStateException("Lookup unavailable");
		when(permissionService.hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION)).thenThrow(failure);
		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID)));
	}

	@Test
	void propagatesPermissionLookupFailureWithoutReturningSession() {

		IllegalStateException failure = new IllegalStateException("Lookup unavailable");
		when(permissionService.hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION))
				.thenThrow(failure);
		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID)));
	}

	@Test
	void locationPermissionIsRequiredForMonitoring() {


		assertThrows(AccessDeniedException.class, () -> monitoringService.validateMonitoringPermission(
				session.id(), GUARDIAN_ID));
		when(permissionService.hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION))
				.thenReturn(true);
		assertSame(session, monitoringService.validateMonitoringPermission(
				session.id(), GUARDIAN_ID));
	}

	@Test
	void locationPermissionAuthorizesStatus() {

		when(permissionService.hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION))
				.thenReturn(true);
		assertSame(session, monitoringService.getMonitoringStatus(session.id(), GUARDIAN_ID));
	}

	@Test
	void rejectsInvalidInputsBeforePermissionLookup() {
		assertThrows(IllegalArgumentException.class,
				() -> monitoringService.getMonitoringStatus(null, GUARDIAN_ID));
		for (Long invalidId : new Long[]{null, 0L, -1L}) {
			assertThrows(IllegalArgumentException.class,
					() -> monitoringService.getMonitoringStatus(session.id(), invalidId));
		}
		verifyNoInteractions(permissionService);
	}

	private void allowStatus() {

		when(permissionService.hasGuardianPermission(OWNER_ID, GUARDIAN_ID, com.safepath.backend.guardian.entity.PermissionType.LOCATION))
				.thenReturn(true);
	}
}
