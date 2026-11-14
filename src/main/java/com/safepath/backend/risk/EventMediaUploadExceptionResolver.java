package com.safepath.backend.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** Handles this upload route's parser errors before a controller can be selected. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EventMediaUploadExceptionResolver implements HandlerExceptionResolver {
    private static final Logger log = LoggerFactory.getLogger(EventMediaUploadExceptionResolver.class);
    private static final String PROBLEM = """
            {"type":"about:blank","title":"Content Too Large","status":413,
             "detail":"업로드 허용 용량을 초과했습니다.","code":"FILE_TOO_LARGE"}
            """;

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception exception) {
        if (!(exception instanceof MaxUploadSizeExceededException)
                || !"POST".equals(request.getMethod())
                || !request.getServletPath().matches("/api/risk-events/[0-9]+/medias")
                || response.isCommitted()) {
            return null;
        }
        // sendError would dispatch to the shared /error route, where authentication
        // can replace the 413 response with 401. Finish only our upload error here.
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(PROBLEM);
        } catch (IOException writeFailure) {
            log.debug("Client disconnected before the media upload error response was written", writeFailure);
        }
        return new ModelAndView();
    }
}
