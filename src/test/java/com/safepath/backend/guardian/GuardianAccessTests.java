package com.safepath.backend.guardian;

import java.time.Instant;
import java.util.Optional;

import com.safepath.backend.guardian.GuardianModels.GuardianAccess;
import com.safepath.backend.guardian.GuardianModels.PermissionType;
import com.safepath.backend.guardian.GuardianModels.Registration;
import com.safepath.backend.guardian.GuardianModels.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardianAccessTests {
    private static final long USER = 10;
    private static final long GUARDIAN = 20;
    private final GuardianRepository repository = mock(GuardianRepository.class);
    private final GuardianRegistrationService service =
            new GuardianRegistrationService(repository, mock(GuardianRequestNotifier.class));

    @Test
    void acceptedRegistrationReportsItsOwnPermissions() {
        registration(Status.ACCEPTED, true, false);

        assertThat(service.getGuardianAccess(USER, GUARDIAN)).isEqualTo(new GuardianAccess(true, true, false));
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.LOCATION)).isTrue();
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.STATUS)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void registrationThatIsNotAcceptedGrantsNothingEvenWithPermissionFlags(Status status) {
        registration(status, true, true);

        assertThat(service.getGuardianAccess(USER, GUARDIAN)).isEqualTo(GuardianAccess.NONE);
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.LOCATION)).isFalse();
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.STATUS)).isFalse();
    }

    @Test
    void missingOrReversedRelationGrantsNothing() {
        registration(Status.ACCEPTED, true, true);
        when(repository.findRegistration(GUARDIAN, USER)).thenReturn(Optional.empty());

        assertThat(service.getGuardianAccess(GUARDIAN, USER)).isEqualTo(GuardianAccess.NONE);
        assertThat(service.hasGuardianPermission(GUARDIAN, USER, PermissionType.LOCATION)).isFalse();
    }

    private void registration(Status status, boolean location, boolean viewStatus) {
        Instant now = Instant.parse("2026-11-15T00:00:00Z");
        when(repository.findRegistration(USER, GUARDIAN)).thenReturn(Optional.of(new Registration(
                1, USER, GUARDIAN, status, now, null, location, viewStatus, false, now, now)));
    }
}
