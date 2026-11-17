package com.safepath.backend.guardian;

import java.util.Optional;

import com.safepath.backend.common.auth.CurrentUser.Actor;
import com.safepath.backend.guardian.dto.GuardianAccess;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.PermissionType;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.guardian.service.GuardianRegistrationService;
import com.safepath.backend.guardian.service.GuardianRequestNotifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardianAccessTests {
    private static final long USER = 10;
    private static final long GUARDIAN = 20;
    private final GuardianRegistrationRepository repository = mock(GuardianRegistrationRepository.class);
    private final GuardianRegistrationService service =
            new GuardianRegistrationService(repository, mock(GuardianRequestNotifier.class));

    @Test
    void acceptedRegistrationReportsItsOwnPermissions() {
        registration(RegistrationStatus.ACCEPTED, true, false);

        assertThat(service.getGuardianAccess(USER, GUARDIAN)).isEqualTo(new GuardianAccess(true, true, false));
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.LOCATION)).isTrue();
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.STATUS)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = RegistrationStatus.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void registrationThatIsNotAcceptedGrantsNothingEvenWithPermissionFlags(RegistrationStatus status) {
        registration(status, true, true);

        assertThat(service.getGuardianAccess(USER, GUARDIAN)).isEqualTo(GuardianAccess.NONE);
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.LOCATION)).isFalse();
        assertThat(service.hasGuardianPermission(USER, GUARDIAN, PermissionType.STATUS)).isFalse();
    }

    @Test
    void missingOrReversedRelationGrantsNothing() {
        registration(RegistrationStatus.ACCEPTED, true, true);
        when(repository.findByUserIdAndGuardianId(GUARDIAN, USER)).thenReturn(Optional.empty());

        assertThat(service.getGuardianAccess(GUARDIAN, USER)).isEqualTo(GuardianAccess.NONE);
        assertThat(service.hasGuardianPermission(GUARDIAN, USER, PermissionType.LOCATION)).isFalse();
    }

    @Test
    void acceptingTurnsOnEveryViewPermission() {
        GuardianRegistration pending = registration(RegistrationStatus.PENDING, false, false);
        when(repository.findForUpdateById(1L)).thenReturn(Optional.of(pending));
        when(repository.saveAndFlush(pending)).thenReturn(pending);

        service.acceptRegistration(new Actor(GUARDIAN, false), 1);

        assertThat(pending.getStatus()).isEqualTo(RegistrationStatus.ACCEPTED);
        assertThat(pending.getAcceptedAt()).isNotNull();
        assertThat(pending.isCanViewLocation()).isTrue();
        assertThat(pending.isCanViewStatus()).isTrue();
        assertThat(pending.isCanViewPersonalInfo()).isTrue();
        assertThat(service.getGuardianAccess(USER, GUARDIAN)).isEqualTo(new GuardianAccess(true, true, true));
    }

    @Test
    void rejectingTurnsOnNothing() {
        GuardianRegistration pending = registration(RegistrationStatus.PENDING, false, false);
        when(repository.findForUpdateById(1L)).thenReturn(Optional.of(pending));
        when(repository.saveAndFlush(pending)).thenReturn(pending);

        service.rejectRegistration(new Actor(GUARDIAN, false), 1);

        assertThat(pending.isCanViewLocation()).isFalse();
        assertThat(pending.isCanViewStatus()).isFalse();
        assertThat(pending.isCanViewPersonalInfo()).isFalse();
        assertThat(service.getGuardianAccess(USER, GUARDIAN)).isEqualTo(GuardianAccess.NONE);
    }

    private GuardianRegistration registration(RegistrationStatus status, boolean location, boolean viewStatus) {
        GuardianRegistration registration = GuardianRegistrations.of(1, USER, GUARDIAN, status, location, viewStatus);
        when(repository.findByUserIdAndGuardianId(USER, GUARDIAN)).thenReturn(Optional.of(registration));
        return registration;
    }
}
