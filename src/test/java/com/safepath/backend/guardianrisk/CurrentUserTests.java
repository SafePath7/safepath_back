package com.safepath.backend.guardianrisk;

import java.util.List;
import java.util.Optional;

import com.safepath.backend.user.UserService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CurrentUserTests {
    private final UserService users = mock(UserService.class);
    private final FirebaseAdminRoleLookup roles = mock(FirebaseAdminRoleLookup.class);
    private final CurrentUser currentUser = new CurrentUser(users, roles);
    private final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken("uid-1", null, List.of());

    @Test
    void ownerChecksNeverContactFirebase() {
        when(users.findUserIdByFirebaseUid("uid-1")).thenReturn(Optional.of(7L));

        var actor = currentUser.resolve(auth);
        actor.requireOwner(7L);

        assertThat(actor.id()).isEqualTo(7L);
        verifyNoInteractions(roles);
    }

    @Test
    void adminClaimIsLookedUpOnceWhenNeeded() {
        when(users.findUserIdByFirebaseUid("uid-1")).thenReturn(Optional.of(7L));
        when(roles.isAdmin("uid-1")).thenReturn(true);

        var actor = currentUser.resolve(auth);
        actor.requireAdmin();

        assertThat(actor.admin()).isTrue();
        verify(roles, times(1)).isAdmin("uid-1");
    }

    @Test
    void nonAdminIsForbiddenAndUnavailableLookupStillFailsClosed() {
        when(users.findUserIdByFirebaseUid("uid-1")).thenReturn(Optional.of(7L));
        when(roles.isAdmin("uid-1")).thenReturn(false);
        assertThatThrownBy(() -> currentUser.resolve(auth).requireAdmin())
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));

        var unavailable = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_ROLE_LOOKUP_UNAVAILABLE", "down");
        when(roles.isAdmin("uid-1")).thenThrow(unavailable);
        assertThatThrownBy(() -> currentUser.resolve(auth).requireAdmin()).isSameAs(unavailable);
    }
}
