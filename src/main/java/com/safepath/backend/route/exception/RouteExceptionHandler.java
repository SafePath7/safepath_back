package com.safepath.backend.route.exception;

import com.safepath.backend.common.exception.ApiException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(basePackages = "com.safepath.backend.route")
public class RouteExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        detail.setProperty("code", e.code());
        return detail;
    }
}
