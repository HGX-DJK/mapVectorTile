package com.map.mbtiles.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.coyote.CloseNowException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Global exception handler — ensures ALL errors return a proper HTTP response
 * instead of an abrupt connection reset, which browsers report as "Failed to fetch".
 *
 * Silences expected client disconnects (rapid zooming/panning cancels in-flight tiles).
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
     * Invalid argument (e.g. malicious or malformed dataset name).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(ex.getMessage());
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
     * Client cancelled connection mid-response during rapid map panning/zooming.
     * Both HTTP/2 (CloseNowException) and HTTP/1.1 (ClientAbortException / Broken pipe)
     * are completely normal; silently discard without polluting logs.
     */
    @ExceptionHandler({CloseNowException.class, ClientAbortException.class})
    public void handleClientCancellation(Exception ex) {
        log.debug("Client closed connection mid-response (navigation cancel): {}", ex.getMessage());
    }

    /**
     * General I/O exception — checks for broken pipes or connection resets.
     */
    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("closed")) {
            log.debug("Client disconnected: {}", ex.getMessage());
            return;
        }
        log.warn("I/O error during tile transmission: {}", ex.getMessage());
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
