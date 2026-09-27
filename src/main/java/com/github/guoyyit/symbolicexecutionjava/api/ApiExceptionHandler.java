package com.github.guoyyit.symbolicexecutionjava.api;

import com.github.guoyyit.symbolicexecutionjava.api.dto.ErrorResponse;
import com.github.guoyyit.symbolicexecutionjava.error.AnalysisException;
import com.github.guoyyit.symbolicexecutionjava.error.OverloadedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps analysis errors to structured HTTP responses with location data. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AnalysisException.class)
    public ResponseEntity<ErrorResponse> handleAnalysis(AnalysisException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.getMessage(), ex.location()));
    }

    @ExceptionHandler(OverloadedException.class)
    public ResponseEntity<ErrorResponse> handleOverloaded(OverloadedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("malformed request body", "$"));
    }
}
