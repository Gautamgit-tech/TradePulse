package com.tradepulse.tradepulse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

@Document(collection = "holdings")
@CompoundIndex(name = "user_symbol_unique", def = "{'userId': 1, 'symbol': 1}", unique = true)
public class Holding {

    @Id
    private String id;
    private String userId;
    private String symbol;
    private BigDecimal quantity;
    private BigDecimal reservedQuantity;
    private BigDecimal averageBuyPrice;
    private BigDecimal totalInvested;

    public Holding() {
    }

    public Holding(String userId, String symbol, BigDecimal quantity,
                   BigDecimal averageBuyPrice, BigDecimal totalInvested) {
        this.userId = userId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.averageBuyPrice = averageBuyPrice;
        this.totalInvested = totalInvested;
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

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getReservedQuantity() {
        return reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
    }

    public void setReservedQuantity(BigDecimal reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    @Transient
    public BigDecimal getAvailableQuantity() {
        return quantity == null ? BigDecimal.ZERO : quantity.subtract(getReservedQuantity());
    }

    public BigDecimal getAverageBuyPrice() {
        return averageBuyPrice;
    }

    public void setAverageBuyPrice(BigDecimal averageBuyPrice) {
        this.averageBuyPrice = averageBuyPrice;
    }

    public BigDecimal getTotalInvested() {
        return totalInvested;
    }

    public void setTotalInvested(BigDecimal totalInvested) {
        this.totalInvested = totalInvested;
    }
}
