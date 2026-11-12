package com.safepath.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

	private final FirebaseTokenService firebaseTokenService;

	public FirebaseAuthenticationFilter(FirebaseTokenService firebaseTokenService) {
		this.firebaseTokenService = firebaseTokenService;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		// Bearer 토큰이 없으면 인증하지 않고 다음 단계로 넘깁니다.
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			filterChain.doFilter(request, response);
			return;
		}

		String firebaseUid;
		try {
			// 토큰을 검증하고 Firebase UID를 인증 정보에 저장합니다.
			firebaseUid = firebaseTokenService.verifyIdToken(authorization.substring(7).trim());
		} catch (BadCredentialsException e) {
			// 유효하지 않은 토큰은 인증 정보를 지우고 401을 반환합니다.
			SecurityContextHolder.clearContext();
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		var authentication = new UsernamePasswordAuthenticationToken(firebaseUid, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
		filterChain.doFilter(request, response);
	}
}
