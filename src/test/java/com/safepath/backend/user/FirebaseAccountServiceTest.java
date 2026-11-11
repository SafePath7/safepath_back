package com.safepath.backend.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.Test;

class FirebaseAccountServiceTest {
    @Test
    void requiresVerifiedMatchingGoogleEmailAndLinkedPhone() {
        UserRecord user = mock(UserRecord.class);
        UserInfo google = mock(UserInfo.class);
        UserInfo phone = mock(UserInfo.class);
        when(google.getProviderId()).thenReturn("google.com");
        when(google.getEmail()).thenReturn("user@example.com");
        when(google.getDisplayName()).thenReturn("Google Name");
        when(phone.getProviderId()).thenReturn("phone");
        when(phone.getPhoneNumber()).thenReturn("+821012345678");
        when(user.getProviderData()).thenReturn(new UserInfo[]{google, phone});
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.isEmailVerified()).thenReturn(true);
        when(user.getPhoneNumber()).thenReturn("+821012345678");
        FirebaseAccountService service = new FirebaseAccountService();
        assertEquals(new FirebaseAccountService.Account("Google Name", "user@example.com", "+821012345678"), service.verifiedAccount(user));
        when(user.isEmailVerified()).thenReturn(false);
        assertNull(service.verifiedAccount(user));
        when(user.isEmailVerified()).thenReturn(true);
        when(user.getEmail()).thenReturn("different@example.com");
        assertNull(service.verifiedAccount(user));
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getProviderData()).thenReturn(new UserInfo[]{google});
        assertNull(service.verifiedAccount(user));
        when(user.getProviderData()).thenReturn(new UserInfo[]{phone});
        assertNull(service.verifiedAccount(user));
    }
}
