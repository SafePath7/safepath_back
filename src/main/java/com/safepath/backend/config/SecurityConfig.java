package com.safepath.backend.config;

import com.safepath.backend.auth.FirebaseAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfig {

    // 서블릿 컨테이너에는 등록하지 않고 Spring Security 체인에서만 실행합니다.
    @Bean
    FilterRegistrationBean<FirebaseAuthenticationFilter> firebaseFilterRegistration(FirebaseAuthenticationFilter filter) {
        FilterRegistrationBean<FirebaseAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // 로그인 요청, 웹소켓 요청은 인증 없이 접근 가능 / 그 외 요청은 인증 필요
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, FirebaseAuthenticationFilter firebaseAuthenticationFilter) throws Exception {
        RequestMatcher login = request -> HttpMethod.POST.matches(request.getMethod())
                && "/api/auth/login".equals(request.getServletPath());
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(login).permitAll()
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(firebaseAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
