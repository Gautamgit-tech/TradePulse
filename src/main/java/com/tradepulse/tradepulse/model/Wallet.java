package com.tradepulse.tradepulse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

@Document(collection = "wallets")
@CompoundIndex(name = "user_currency_unique", def = "{'userId': 1, 'currency': 1}", unique = true)
public class Wallet {

    @Id
    private String id;
    private String userId;
    private String currency;
    private BigDecimal availableBalance;
    private BigDecimal lockedBalance;

    public Wallet() {
    }

    public Wallet(String userId, String currency, BigDecimal availableBalance, BigDecimal lockedBalance) {
        this.userId = userId;
        this.currency = currency;
        this.availableBalance = availableBalance;
        this.lockedBalance = lockedBalance;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(BigDecimal availableBalance) {
        this.availableBalance = availableBalance;
    }

    public BigDecimal getLockedBalance() {
        return lockedBalance;
    }

    public void setLockedBalance(BigDecimal lockedBalance) {
        this.lockedBalance = lockedBalance;
    }
}
