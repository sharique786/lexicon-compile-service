package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.model.InvalidTermIdException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts exceptions into JSON error bodies: {@code status}, {@code error}, optional {@code details},
 * {@code timestamp}.
 * <ul>
 *   <li>Bean-validation failure → 400, {@code details} listing {@code "field: message"} entries;</li>
 *   <li>oversized upload → 413;</li>
 *   <li>an invalid {@code termId} set on {@code /compile/bundle} → 400;</li>
 *   <li>anything else → 500 {@code "Internal server error"}. This catch-all also receives the framework's
 *       own request-parsing failures — malformed JSON, an unsupported content type, an unknown
 *       {@code requestType} value — so those currently surface as 500 rather than 400/415.</li>
 * </ul>
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles bean validation failures (missing/blank required fields).
     *
     * @param ex the validation exception
     * @return HTTP 400 with field-level error details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.toList());
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(400, "Validation failed", errors));
    }

    /**
     * Handles file size exceeded errors.
     *
     * @return HTTP 413 Payload Too Large
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleFileTooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(errorBody(413, "Uploaded file exceeds maximum allowed size", null));
    }

    /**
     * Handles {@code /compile/bundle} requests whose term ids don't satisfy
     * the {@code ::<n>} term-number convention that endpoint's Hyperscan
     * expression id scheme depends on — see {@link InvalidTermIdException}.
     *
     * @return HTTP 400 Bad Request
     */
    @ExceptionHandler(InvalidTermIdException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTermId(InvalidTermIdException ex) {
        log.warn("Invalid termId(s) in bundle request: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorBody(400, ex.getMessage(), null));
    }

    /**
     * Handles any other unhandled exception.
     *
     * @param ex the exception
     * @return HTTP 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(500, "Internal server error", null));
    }

    private Map<String, Object> errorBody(int status, String message, List<String> details) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", status);
        body.put("error", message);
        if (details != null) {
            body.put("details", details);
        }
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
