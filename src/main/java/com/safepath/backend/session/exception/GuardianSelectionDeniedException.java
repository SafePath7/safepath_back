package com.safepath.backend.session.exception;

import org.springframework.security.access.AccessDeniedException;

public class GuardianSelectionDeniedException extends AccessDeniedException {
	public GuardianSelectionDeniedException(String message) {
		super(message);
	}
}
