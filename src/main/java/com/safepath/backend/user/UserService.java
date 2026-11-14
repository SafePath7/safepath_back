package com.safepath.backend.user;

import com.safepath.backend.user.repository.UserRepository;
import com.safepath.backend.user.service.UserOperations;
import org.springframework.stereotype.Service;

// 기존 호출 경로를 유지하며 회원 처리 기능을 연결합니다.
@Service
public class UserService extends UserOperations {
    public UserService(UserRepository users) {
        super(users);
    }
}
