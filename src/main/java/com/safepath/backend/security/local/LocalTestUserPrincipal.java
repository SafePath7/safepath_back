package com.safepath.backend.security.local;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class LocalTestUserPrincipal implements UserDetails {

	private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_LOCAL_TEST"));

	private final String username;
	private final String encodedPassword;
	private final Long userId;

	LocalTestUserPrincipal(String username, String encodedPassword, Long userId) {
		this.username = Objects.requireNonNull(username, "username must not be null");
		this.encodedPassword = Objects.requireNonNull(encodedPassword, "encodedPassword must not be null");
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
	}

	public Long getUserId() {
		return userId;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return AUTHORITIES;
	}

	@Override
	public String getPassword() {
		return encodedPassword;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}
}
