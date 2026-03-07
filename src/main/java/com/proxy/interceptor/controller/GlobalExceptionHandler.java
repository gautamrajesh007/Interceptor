package com.proxy.interceptor.controller;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<?> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
        String message = e.getMessage();

        // Map backend-specific messages to user-friendly UI messages
        if (message != null && message.startsWith("No enum constant")) {
            message = "Invalid role";
        } else if ("Username already exists".equals(message)) {
            message = "Try a different username";
        } else {
            message = "Invalid";
        }

        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}