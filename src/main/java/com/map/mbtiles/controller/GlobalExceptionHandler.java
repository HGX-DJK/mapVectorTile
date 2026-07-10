package com.map.mbtiles.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.apache.coyote.CloseNowException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;

/**
 * Global exception handler — ensures ALL errors return a proper HTTP response
 * instead of an abrupt connection reset, which browsers report as "Failed to fetch".
 *
 * Returns plain-text error messages to avoid content-type negotiation issues
 * (e.g. when the original request was for .pbf, Spring would try to serialize
 * a Map as protobuf, which fails).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Path variable type mismatch (e.g. z/x/y is not a valid integer).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Invalid path parameter: {} = '{}' — expected {}",
                ex.getName(), ex.getValue(), ex.getRequiredType());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body("Invalid parameter: " + ex.getName());
    }

    /**
     * Database connectivity issues — log full stack, return 503 so the browser
     * retries instead of silently failing.
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<String> handleSqlException(SQLException ex) {
        log.error("Database error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Tile database temporarily unavailable");
    }

    /**
     * HTTP/2 RST_STREAM — client disconnected mid-response.
     * This is normal during rapid navigation/scrolling; no point returning an error.
     */
    @ExceptionHandler(CloseNowException.class)
    public void handleCloseNowException(CloseNowException ex) {
        log.debug("Client closed connection mid-response (RST_STREAM): {}", ex.getMessage());
    }

    /**
     * Catch-all for anything unexpected.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal server error");
    }
}
