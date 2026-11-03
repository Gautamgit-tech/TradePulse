package com.tradepulse.tradepulse.service;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException() {
        super("Insufficient available balance to place this BUY order.");
    }
}
