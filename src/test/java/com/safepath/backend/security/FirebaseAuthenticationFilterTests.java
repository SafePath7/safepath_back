package com.safepath.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirebaseAuthenticationFilterTests {
    @Mock ObjectProvider<FirebaseIdTokenVerifier> providers;
    @Mock FirebaseIdTokenVerifier verifier;

    @BeforeEach
    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void missingVerifierDoesNotTrustBearerContents() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer admin-uid");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new FirebaseAuthenticationFilter(providers).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void trustedVerifierSuppliesUidAndAdminAuthority() throws Exception {
        when(providers.getIfAvailable()).thenReturn(verifier);
        when(verifier.verify("opaque-token")).thenReturn(new FirebaseIdTokenVerifier.VerifiedIdentity("verified-uid", true));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer opaque-token");
        var chain = new MockFilterChain();

        new FirebaseAuthenticationFilter(providers).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getName()).isEqualTo("verified-uid");
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(authentication.getCredentials()).isNull();
    }

    @Test
    void verifierRejectionIs401WithoutCredentialLeak() throws Exception {
        when(providers.getIfAvailable()).thenReturn(verifier);
        when(verifier.verify("secret")).thenThrow(new IllegalArgumentException("secret"));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret");
        var response = new MockHttpServletResponse();

        new FirebaseAuthenticationFilter(providers).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
