package com.safepath.backend.user.dto;

// 내 회원 정보 응답
public record UserResponse(Long userId, String name, String email, String phoneNumber) {}
