package io.github.pavanprudhvi02.reach.api;

import io.github.pavanprudhvi02.reach.inference.NoWorkerAvailableException;
import io.github.pavanprudhvi02.reach.routing.Router;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Errors in the OpenAI shape ({"error": {...}}) so existing clients can read them. */
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(NoWorkerAvailableException.class)
    public ResponseEntity<Map<String, Object>> noWorker(NoWorkerAvailableException e) {
        boolean busy = e.reason() == Router.Unavailable.ALL_BUSY;
        HttpStatus status = busy ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header("Retry-After", busy ? "1" : "5")
                .body(error(e.getMessage(), busy ? "capacity_exceeded" : "no_worker_available", e.attempts()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(error(e.getReason(), "invalid_request_error", List.of()));
    }

    private static Map<String, Object> error(String message, String type, List<String> attempts) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        if (!attempts.isEmpty()) {
            err.put("attempts", attempts);
        }
        return Map.of("error", err);
    }
}
