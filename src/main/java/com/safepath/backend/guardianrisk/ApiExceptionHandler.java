package com.safepath.backend.guardianrisk;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(basePackages = {
        "com.safepath.backend.guardian", "com.safepath.backend.risk"
})
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        var detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail detail) {
            detail.setProperty("code", status.value() == 400 ? "INVALID_REQUEST" : "HTTP_ERROR");
            if (status.value() == 400) {
                detail.setDetail("필수값, JSON 형식과 파라미터의 허용 범위를 확인해 주세요.");
            }
        }
        return super.createResponseEntity(body, headers, status, request);
    }
}
