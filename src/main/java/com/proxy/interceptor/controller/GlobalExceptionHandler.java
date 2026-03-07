package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleBadRequest(IllegalArgumentException e) {
        String message = e.getMessage();

        if (message != null && message.startsWith("No enum constant")) {
            message = "Invalid role";
        } else if ("Username already exists".equals(message)) {
            message = "Try a different username";
        } else {
            message = "Invalid";
        }

        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }
}