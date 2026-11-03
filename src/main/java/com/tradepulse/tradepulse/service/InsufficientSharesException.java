package com.tradepulse.tradepulse.service;

public class InsufficientSharesException extends RuntimeException {

    public InsufficientSharesException() {
        super("Insufficient shares to sell");
    }
}
