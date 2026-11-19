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

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail handleServerError() {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "음성 프로필 조회 중 오류가 발생했습니다.");
        detail.setProperty("code", "INTERNAL_ERROR");
        return detail;
    }
}
