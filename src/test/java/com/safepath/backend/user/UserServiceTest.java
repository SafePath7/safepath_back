package com.safepath.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.safepath.backend.user.entity.User;
import com.safepath.backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final UserService users = new UserService(repository);

    @Test
    void returnsMappedLongForExistingUid() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(repository.findByFirebaseUid("firebase-uid")).thenReturn(Optional.of(user));

        assertEquals(Optional.of(42L), users.findUserIdByFirebaseUid("firebase-uid"));
        verify(repository).findByFirebaseUid("firebase-uid");
    }

    @Test
    void returnsEmptyForUnknownUid() {
        when(repository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());
        assertTrue(users.findUserIdByFirebaseUid("unknown").isEmpty());
    }
}
