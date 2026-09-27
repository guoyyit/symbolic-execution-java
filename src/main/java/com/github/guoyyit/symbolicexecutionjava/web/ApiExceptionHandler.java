package com.github.guoyyit.symbolicexecutionjava.web;

import com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult;
import com.github.guoyyit.symbolicexecutionjava.service.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Translates positioned parse/validation rejections and unknown-job references
 * into structured HTTP error responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AnalysisService.PositionedRejection.class)
    public ResponseEntity<Map<String, Object>> positioned(AnalysisService.PositionedRejection e) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "REJECTED");
        body.put("error", e.getMessage());
        if (e.position() != null) {
            body.put("position", Map.of(
                    "line", e.position().line(),
                    "column", e.position().column()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(AnalysisService.JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> missingJob(AnalysisService.JobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", "NOT_FOUND", "error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "REJECTED", "error", String.valueOf(e.getMessage())));
    }
}
