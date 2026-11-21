package com.safepath.backend.session.service;

import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardianLocationPermissionTest {
    private final GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
    private final GuardianRegistrationService service = new GuardianRegistrationService(repository, mock(GuardianRequestNotifier.class));

    @Test
    void locationPermissionRequiresAcceptedRelationshipAndPermission() {
        var registration = BeanUtils.instantiateClass(GuardianRegistration.class);
        when(repository.findByUserIdAndGuardianId(42L, 43L)).thenReturn(Optional.of(registration));
        registration.changeStatus(RegistrationStatus.PENDING);
        registration.changePermissions(true, false, false);
        assertFalse(service.hasGuardianPermission(42L, 43L, PermissionType.LOCATION));
        registration.changeStatus(RegistrationStatus.ACCEPTED);
        assertTrue(service.hasGuardianPermission(42L, 43L, PermissionType.LOCATION));
        registration.changePermissions(false, false, false);
        assertFalse(service.hasGuardianPermission(42L, 43L, PermissionType.LOCATION));
        registration.changeStatus(RegistrationStatus.REVOKED);
        registration.changePermissions(true, false, false);
        assertFalse(service.hasGuardianPermission(42L, 43L, PermissionType.LOCATION));
    }

    @Test
    void missingRelationshipIsDeniedAndLookupFailurePropagates() {
        when(repository.findByUserIdAndGuardianId(42L, 43L)).thenReturn(Optional.empty());
        assertFalse(service.hasGuardianPermission(42L, 43L, PermissionType.LOCATION));
        var failure = new IllegalStateException("Lookup unavailable");
        when(repository.findByUserIdAndGuardianId(42L, 43L)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.hasGuardianPermission(42L, 43L, PermissionType.LOCATION)));
    }
}
