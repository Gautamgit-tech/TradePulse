package com.tradepulse.tradepulse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Document(collection = "orders")
@CompoundIndexes({
        @CompoundIndex(name = "user_orders_by_time", def = "{'userId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "pending_orders_by_price",
                def = "{'symbol': 1, 'side': 1, 'status': 1, 'price': 1, 'createdAt': 1}")
})
public class Order {

    @Id
    private String id;
    private String userId;
    private String symbol;
    private OrderSide side;
    private OrderType orderType;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal filledQuantity;
    private BigDecimal executedPrice;
    private BigDecimal realizedPnl;
    private BigDecimal averageBuyPriceAtFill;
    private OrderStatus status;
    private Instant createdAt;
    private Instant filledAt;

    public Order() {
    }

    public Order(String userId, String symbol, OrderSide side, OrderType orderType,
                 BigDecimal price, BigDecimal quantity, BigDecimal filledQuantity,
                 OrderStatus status, Instant createdAt) {
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.filledQuantity = filledQuantity;
        this.status = status;
        this.createdAt = createdAt;
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

    public BigDecimal getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(BigDecimal filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public BigDecimal getExecutedPrice() {
        return executedPrice;
    }

    public void setExecutedPrice(BigDecimal executedPrice) {
        this.executedPrice = executedPrice;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getAverageBuyPriceAtFill() {
        return averageBuyPriceAtFill;
    }

    public void setAverageBuyPriceAtFill(BigDecimal averageBuyPriceAtFill) {
        this.averageBuyPriceAtFill = averageBuyPriceAtFill;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFilledAt() {
        return filledAt;
    }

    public void setFilledAt(Instant filledAt) {
        this.filledAt = filledAt;
    }
}
