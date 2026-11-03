package com.tradepulse.tradepulse.service;

public class OrderCancellationException extends RuntimeException {

    private final boolean notFound;

    public OrderCancellationException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
