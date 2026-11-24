package io.skala.backend.global.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** 필터 단계(인증·권한 실패)에서도 GlobalExceptionHandler와 같은 Problem 형식으로 응답한다. */
@Component
@RequiredArgsConstructor
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(code.getStatus(), code.getMessage());
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("code", code.code());
        detail.setProperty("requestId", RequestIdFilter.requestId(request));
        detail.setProperty("errors", List.of());
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), detail);
    }
}
