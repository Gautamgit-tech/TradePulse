package com.tradepulse.tradepulse.controller;

import com.tradepulse.tradepulse.service.InsufficientFundsException;
import com.tradepulse.tradepulse.service.InsufficientSharesException;
import com.tradepulse.tradepulse.service.OrderCancellationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientFunds(InsufficientFundsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(InsufficientSharesException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientShares(InsufficientSharesException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(OrderCancellationException.class)
    public ResponseEntity<Map<String, String>> handleOrderCancellation(OrderCancellationException exception) {
        HttpStatus status = exception.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request validation failed.");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid value for " + exception.getName() + "."));
    }
}
