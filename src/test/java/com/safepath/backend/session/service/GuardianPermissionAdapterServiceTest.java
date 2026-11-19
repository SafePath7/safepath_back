package com.safepath.backend.session.service;

import com.safepath.backend.guardian.dto.GuardianAccess;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.session.model.MonitoringPermissionType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GuardianPermissionAdapterServiceTest {
	private final GuardianRegistrationService registrations = mock(GuardianRegistrationService.class);
	private final GuardianPermissionService adapter = new GuardianPermissionAdapterService(registrations);

	@Test
	void delegatesAcceptedRelationshipWithOwnerFirst() {
		when(registrations.getGuardianAccess(42L, 43L)).thenReturn(new GuardianAccess(true, false, false));
		assertTrue(adapter.hasAcceptedRelationship(42L, 43L));
		verify(registrations).getGuardianAccess(42L, 43L);
		verifyNoMoreInteractions(registrations);
	}

	@Test
	void missingOrUnacceptedRelationshipIsNotAllowed() {
		when(registrations.getGuardianAccess(42L, 43L)).thenReturn(GuardianAccess.NONE);
		assertFalse(adapter.hasAcceptedRelationship(42L, 43L));
	}

	@Test
	void statusPermissionPreservesAllowedAndDeniedResults() {
		when(registrations.hasGuardianPermission(42L, 43L, PermissionType.LOCATION)).thenReturn(true, false);
		assertTrue(adapter.hasPermission(42L, 43L, MonitoringPermissionType.STATUS));
		assertFalse(adapter.hasPermission(42L, 43L, MonitoringPermissionType.STATUS));
		verify(registrations, org.mockito.Mockito.times(2)).hasGuardianPermission(42L, 43L, PermissionType.LOCATION);
		verifyNoMoreInteractions(registrations);
	}

	@Test
	void locationPermissionPreservesAllowedAndDeniedResults() {
		when(registrations.hasGuardianPermission(42L, 43L, PermissionType.LOCATION)).thenReturn(true, false);
		assertTrue(adapter.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION));
		assertFalse(adapter.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION));
		verify(registrations, org.mockito.Mockito.times(2)).hasGuardianPermission(42L, 43L, PermissionType.LOCATION);
		verifyNoMoreInteractions(registrations);
	}

	@Test
	void relationshipLookupExceptionIsPropagated() {
		var failure = new DataAccessResourceFailureException("Lookup unavailable");
		when(registrations.getGuardianAccess(42L, 43L)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> adapter.hasAcceptedRelationship(42L, 43L)));
	}

	@Test
	void permissionLookupExceptionIsPropagated() {
		var failure = new DataAccessResourceFailureException("Lookup unavailable");
		when(registrations.hasGuardianPermission(42L, 43L, PermissionType.LOCATION)).thenThrow(failure);
		assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
				() -> adapter.hasPermission(42L, 43L, MonitoringPermissionType.LOCATION)));
	}

	@Test
	void invalidInputsNeverReachRegistrationService() {
		for (Long invalidId : new Long[]{null, 0L, -1L}) {
			assertThrows(IllegalArgumentException.class, () -> adapter.hasAcceptedRelationship(invalidId, 43L));
			assertThrows(IllegalArgumentException.class, () -> adapter.hasAcceptedRelationship(42L, invalidId));
			assertThrows(IllegalArgumentException.class,
					() -> adapter.hasPermission(invalidId, 43L, MonitoringPermissionType.STATUS));
			assertThrows(IllegalArgumentException.class,
					() -> adapter.hasPermission(42L, invalidId, MonitoringPermissionType.LOCATION));
		}
		assertThrows(IllegalArgumentException.class, () -> adapter.hasPermission(42L, 43L, null));
		verifyNoInteractions(registrations);
	}
}
