package com.safepath.backend.voice.exception;

import com.safepath.backend.common.exception.ApiException;
import com.safepath.backend.voice.VoiceProfileController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = VoiceProfileController.class)
public class VoiceProfileExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        if (exception.status().is5xxServerError()) {
            return handleServerError();
        }
        var detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    @ExceptionHandler(VoiceProfileException.class)
    ProblemDetail handleVoiceProfileException(VoiceProfileException exception) {
        return switch (exception.reason()) {
            case ALREADY_EXISTS -> handleApiException(ApiException.conflict("음성 프로필이 이미 존재합니다."));
            case INVALID_INPUT -> handleApiException(ApiException.badRequest("음성 프로필 입력값이 유효하지 않습니다."));
            case NOT_FOUND -> handleApiException(ApiException.notFound("음성 프로필"));
            case CONSTRAINT_VIOLATION, DATABASE_ERROR -> handleServerError();
        };
    }

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail handleServerError() {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "음성 프로필 조회 중 오류가 발생했습니다.");
        detail.setProperty("code", "INTERNAL_ERROR");
        return detail;
    }
}
