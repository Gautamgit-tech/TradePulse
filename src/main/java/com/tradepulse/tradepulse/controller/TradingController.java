package com.tradepulse.tradepulse.controller;

import com.tradepulse.tradepulse.dto.PlaceOrderRequest;
import com.tradepulse.tradepulse.dto.OrderPlacementResponse;
import com.tradepulse.tradepulse.dto.OrderCancellationResponse;
import com.tradepulse.tradepulse.model.Holding;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.service.HoldingService;
import com.tradepulse.tradepulse.service.TradingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TradingController {

    private final TradingService tradingService;
    private final HoldingService holdingService;

    public TradingController(TradingService tradingService, HoldingService holdingService) {
        this.tradingService = tradingService;
        this.holdingService = holdingService;
    }

    @GetMapping("/wallet/{userId}")
    public Wallet getWallet(@PathVariable String userId) {
        return tradingService.getOrCreateWallet(userId);
    }

    @PostMapping("/orders")
    public OrderPlacementResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        Order order = tradingService.placeOrder(request);
        return new OrderPlacementResponse(
                order,
                tradingService.getOrCreateWallet(request.getUserId()),
                holdingService.getActiveHoldings(request.getUserId()));
    }

    @GetMapping("/orders/{userId}")
    public List<Order> getOrders(@PathVariable String userId) {
        return tradingService.getRecentOrders(userId);
    }

    @DeleteMapping("/orders/{orderId}")
    public OrderCancellationResponse cancelOrder(@PathVariable String orderId,
                                                  @RequestParam("userId") String userId) {
        Order order = tradingService.cancelOrder(orderId, userId);
        return new OrderCancellationResponse(order, tradingService.getOrCreateWallet(userId));
    }

    @GetMapping("/holdings/{userId}")
    public List<Holding> getHoldings(@PathVariable String userId) {
        return holdingService.getActiveHoldings(userId);
    }
}
