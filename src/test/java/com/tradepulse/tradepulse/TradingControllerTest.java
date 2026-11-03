package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderType;
import com.tradepulse.tradepulse.model.Holding;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.controller.ApiExceptionHandler;
import com.tradepulse.tradepulse.service.HoldingService;
import com.tradepulse.tradepulse.service.InsufficientSharesException;
import com.tradepulse.tradepulse.service.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TradingControllerTest {

    private TradingService tradingService;
    private HoldingService holdingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        holdingService = mock(HoldingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new com.tradepulse.tradepulse.controller.TradingController(tradingService, holdingService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getWalletIsMappedToApiWalletAndReturnsBalanceFields() throws Exception {
        when(tradingService.getOrCreateWallet("demo-user")).thenReturn(
                new Wallet("demo-user", "INR", new BigDecimal("100000.00"), BigDecimal.ZERO));

        mockMvc.perform(get("/api/wallet/{userId}", "demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("demo-user"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.availableBalance").value(100000.00))
                .andExpect(jsonPath("$.lockedBalance").value(0));

        verify(tradingService).getOrCreateWallet("demo-user");
    }

    @Test
    void postOrderIsMappedToApiOrders() throws Exception {
        Order placedOrder = new Order("demo-user", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100.00"), BigDecimal.ONE, BigDecimal.ZERO, OrderStatus.PENDING, Instant.now());
        when(tradingService.placeOrder(any())).thenReturn(placedOrder);
        when(tradingService.getOrCreateWallet("demo-user")).thenReturn(
                new Wallet("demo-user", "INR", new BigDecimal("99900.00"), new BigDecimal("100.00")));
        when(holdingService.getActiveHoldings("demo-user")).thenReturn(List.of());

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userId": "demo-user",
                                  "symbol": "AAPL",
                                  "side": "BUY",
                                  "orderType": "LIMIT",
                                  "price": 100.00,
                                  "quantity": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("PENDING"))
                .andExpect(jsonPath("$.order.symbol").value("AAPL"))
                .andExpect(jsonPath("$.wallet.userId").value("demo-user"))
                .andExpect(jsonPath("$.holdings").isArray());

        verify(tradingService).placeOrder(any());
    }

    @Test
    void postSellOrderReturnsBadRequestWhenSharesAreInsufficient() throws Exception {
        doThrow(new InsufficientSharesException()).when(tradingService).placeOrder(any());

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userId": "demo-user",
                                  "symbol": "AAPL",
                                  "side": "SELL",
                                  "orderType": "MARKET",
                                  "price": 100.00,
                                  "quantity": 3
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Insufficient shares to sell"));
    }

    @Test
    void getOrdersIsMappedToApiOrdersByUserId() throws Exception {
        when(tradingService.getRecentOrders("demo-user")).thenReturn(List.of(
                new Order("demo-user", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                        new BigDecimal("100.00"), BigDecimal.ONE, BigDecimal.ZERO,
                        OrderStatus.PENDING, Instant.parse("2026-11-02T10:00:00Z"))));

        mockMvc.perform(get("/api/orders/{userId}", "demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(tradingService).getRecentOrders("demo-user");
    }

    @Test
    void getHoldingsIsMappedToApiHoldingsByUserId() throws Exception {
        when(holdingService.getActiveHoldings("demo-user")).thenReturn(List.of(
                new Holding("demo-user", "AAPL", new BigDecimal("2"),
                        new BigDecimal("100.00"), new BigDecimal("200.00"))));

        mockMvc.perform(get("/api/holdings/{userId}", "demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].averageBuyPrice").value(100.00))
                .andExpect(jsonPath("$[0].totalInvested").value(200.00));

        verify(holdingService).getActiveHoldings("demo-user");
    }

    @Test
    void deleteOrderCancelsPendingOrderAndReturnsUpdatedWallet() throws Exception {
        Order cancelled = new Order("demo-user", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100.00"), BigDecimal.ONE, BigDecimal.ZERO,
                OrderStatus.CANCELLED, Instant.now());
        cancelled.setId("order-1");
        when(tradingService.cancelOrder("order-1", "demo-user")).thenReturn(cancelled);
        when(tradingService.getOrCreateWallet("demo-user")).thenReturn(
                new Wallet("demo-user", "INR", new BigDecimal("100000.00"), BigDecimal.ZERO));

        mockMvc.perform(delete("/api/orders/{orderId}", "order-1").param("userId", "demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-1"))
                .andExpect(jsonPath("$.order.status").value("CANCELLED"))
                .andExpect(jsonPath("$.wallet.availableBalance").value(100000.00))
                .andExpect(jsonPath("$.wallet.lockedBalance").value(0));
    }

    @Test
    void deleteOrderReturnsBadRequestWhenOrderCannotBeCancelled() throws Exception {
        doThrow(new com.tradepulse.tradepulse.service.OrderCancellationException(
                "Order cannot be cancelled", false))
                .when(tradingService).cancelOrder("filled-order", "demo-user");

        mockMvc.perform(delete("/api/orders/{orderId}", "filled-order").param("userId", "demo-user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Order cannot be cancelled"));
    }
}
