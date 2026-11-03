package com.tradepulse.tradepulse.dto;

import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class PlaceOrderRequest {

    @NotBlank
    private String userId;

    @NotBlank
    private String symbol;

    @NotNull
    private OrderSide side;

    @NotNull
    private OrderType orderType;

    @NotNull
    @DecimalMin(value = "0.00000001")
    private BigDecimal price;

    @NotNull
    @DecimalMin(value = "0.00000001")
    private BigDecimal quantity;

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

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
