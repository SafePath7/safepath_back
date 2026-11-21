package com.safepath.backend.voice.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VoiceProfileUploadExceptionResolver implements HandlerExceptionResolver {
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception exception) {
        if (!(exception instanceof MultipartException)
                || !isVoiceUpload(request)
                || response.isCommitted()) return null;

        boolean tooLarge = exception instanceof MaxUploadSizeExceededException;
        response.setStatus(tooLarge ? 413 : 400);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        try {
            response.getWriter().write(tooLarge
                    ? "{\"status\":413,\"code\":\"FILE_TOO_LARGE\",\"detail\":\"Upload exceeds the size limit.\"}"
                    : "{\"status\":400,\"code\":\"BAD_REQUEST\",\"detail\":\"Invalid multipart request.\"}");
        } catch (IOException disconnected) {
            // The response can no longer be delivered.
        }
        return new ModelAndView();
    }

    private static boolean isVoiceUpload(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "POST" -> "/api/voice-profiles".equals(request.getServletPath());
            case "PUT" -> "/api/voice-profiles/me".equals(request.getServletPath());
            default -> false;
        };
    }
}
