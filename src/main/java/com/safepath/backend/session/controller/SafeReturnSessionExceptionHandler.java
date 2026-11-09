package com.safepath.backend.session.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.safepath.backend.session.dto.SessionApiErrorResponse;

import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = SafeReturnSessionController.class)
public class SafeReturnSessionExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(SafeReturnSessionExceptionHandler.class);

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<SessionApiErrorResponse> handleAuthenticationFailure(AuthenticationException exception) {
		return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<SessionApiErrorResponse> handleInvalidPathParameter(
			MethodArgumentTypeMismatchException exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_SESSION_ID", "The session ID is invalid.");
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<SessionApiErrorResponse> handleInvalidRequest(IllegalArgumentException exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
	}

	@ExceptionHandler({NoSuchElementException.class, SecurityException.class})
	public ResponseEntity<SessionApiErrorResponse> handleUnavailableSession(RuntimeException exception) {
		return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "The active session was not found.");
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<SessionApiErrorResponse> handleSessionConflict(IllegalStateException exception) {
		return error(HttpStatus.CONFLICT, "ACTIVE_SESSION_CONFLICT", "The user already has an active session.");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<SessionApiErrorResponse> handleUnexpectedFailure(Exception exception) {
		LOGGER.error("Unexpected error while handling a safe-return session request", exception);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred.");
	}

	private ResponseEntity<SessionApiErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new SessionApiErrorResponse(code, message));
	}
}
