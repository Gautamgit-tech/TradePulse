package com.tradepulse.tradepulse;

import com.mongodb.client.result.UpdateResult;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.LedgerRecord;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderType;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.repository.OrderRepository;
import com.tradepulse.tradepulse.repository.WalletRepository;
import com.tradepulse.tradepulse.repository.LedgerRecordRepository;
import com.tradepulse.tradepulse.service.HoldingService;
import com.tradepulse.tradepulse.service.OrderMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderMatchingServiceTest {

    private OrderRepository orderRepository;
    private WalletRepository walletRepository;
    private LedgerRecordRepository ledgerRecordRepository;
    private MongoTemplate mongoTemplate;
    private SimpMessagingTemplate messagingTemplate;
    private HoldingService holdingService;
    private OrderMatchingService orderMatchingService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        walletRepository = mock(WalletRepository.class);
        ledgerRecordRepository = mock(LedgerRecordRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        holdingService = mock(HoldingService.class);
        orderMatchingService = new OrderMatchingService(
                orderRepository, walletRepository, ledgerRecordRepository, mongoTemplate,
                messagingTemplate, holdingService);
    }

    @Test
    void matchingBuyFillsOrderDebitsLockedInrAndNotifiesUser() {
        Order buyOrder = order(OrderSide.BUY);
        Wallet wallet = new Wallet("demo-user", "INR", new BigDecimal("90000"),
                new BigDecimal("10000"));
        wallet.setId("wallet-1");
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(orderRepository
                .findBySymbolAndSideAndStatusAndOrderTypeAndPriceGreaterThanEqualOrderByPriceDescCreatedAtAsc(
                        "AAPL", OrderSide.BUY, OrderStatus.PENDING, OrderType.LIMIT, new BigDecimal("100.00")))
                .thenReturn(List.of(buyOrder));
        when(orderRepository
                .findBySymbolAndSideAndStatusAndOrderTypeAndPriceLessThanEqualOrderByPriceAscCreatedAtAsc(
                        "AAPL", OrderSide.SELL, OrderStatus.PENDING, OrderType.LIMIT, new BigDecimal("100.00")))
                .thenReturn(List.of());
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(buyOrder);
        when(walletRepository.findByUserIdAndCurrency("demo-user", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Wallet.class)))
                .thenReturn(updateResult);

        orderMatchingService.matchPendingOrders("AAPL", new BigDecimal("100.00"));

        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Wallet.class));
        verify(holdingService).recordBuyFill(buyOrder);
        verify(messagingTemplate).convertAndSend("/topic/orders/demo-user", buyOrder);
    }

    @Test
    void immediateMarketBuyFillDebitsWalletAndRecordsHolding() {
        Order marketOrder = new Order("demo-user", "AAPL", OrderSide.BUY, OrderType.MARKET,
                new BigDecimal("100.00"), new BigDecimal("2"), BigDecimal.ZERO,
                OrderStatus.FILLED, Instant.parse("2026-11-02T10:00:00Z"));
        marketOrder.setId("market-order-1");
        Wallet wallet = new Wallet("demo-user", "INR", new BigDecimal("90000"),
                new BigDecimal("200"));
        wallet.setId("wallet-1");
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(marketOrder);
        when(walletRepository.findByUserIdAndCurrency("demo-user", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Wallet.class)))
                .thenReturn(updateResult);

        Order result = orderMatchingService.executeMarketOrder(marketOrder);

        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.FILLED, result.getStatus());
        verify(holdingService).recordBuyFill(marketOrder);
        verify(messagingTemplate).convertAndSend("/topic/orders/demo-user", marketOrder);
    }

    @Test
    void matchingSellFillsOrderWithoutDebitingInrWallet() {
        Order sellOrder = order(OrderSide.SELL);
        sellOrder.setFilledQuantity(sellOrder.getQuantity());
        when(orderRepository
                .findBySymbolAndSideAndStatusAndOrderTypeAndPriceGreaterThanEqualOrderByPriceDescCreatedAtAsc(
                        "AAPL", OrderSide.BUY, OrderStatus.PENDING, OrderType.LIMIT, new BigDecimal("100.00")))
                .thenReturn(List.of());
        when(orderRepository
                .findBySymbolAndSideAndStatusAndOrderTypeAndPriceLessThanEqualOrderByPriceAscCreatedAtAsc(
                        "AAPL", OrderSide.SELL, OrderStatus.PENDING, OrderType.LIMIT, new BigDecimal("100.00")))
                .thenReturn(List.of(sellOrder));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(sellOrder);
        Wallet wallet = new Wallet("demo-user", "INR", new BigDecimal("90000"),
                new BigDecimal("0"));
        wallet.setId("wallet-1");
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(walletRepository.findByUserIdAndCurrency("demo-user", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.findAndModify(any(Query.class), any(org.springframework.data.mongodb.core.aggregation.AggregationUpdate.class),
                any(FindAndModifyOptions.class), eq(com.tradepulse.tradepulse.model.Holding.class)))
                .thenReturn(new com.tradepulse.tradepulse.model.Holding(
                        "demo-user", "AAPL", BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Wallet.class)))
                .thenReturn(updateResult);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Order.class)))
                .thenReturn(updateResult);
        when(holdingService.recordSellFill(sellOrder, new BigDecimal("100.00")))
                .thenReturn(new BigDecimal("0.00"));
        when(ledgerRecordRepository.save(any(LedgerRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        orderMatchingService.matchPendingOrders("AAPL", new BigDecimal("100.00"));

        verify(walletRepository).findByUserIdAndCurrency("demo-user", "INR");
        verify(holdingService, never()).recordBuyFill(any());
        verify(holdingService).recordSellFill(sellOrder, new BigDecimal("100.00"));
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Wallet.class));
        org.mockito.ArgumentCaptor<LedgerRecord> ledgerCaptor =
                org.mockito.ArgumentCaptor.forClass(LedgerRecord.class);
        verify(ledgerRecordRepository).save(ledgerCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("EQUITY_SALE_CREDIT", ledgerCaptor.getValue().getReason());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("200.00"), ledgerCaptor.getValue().getAmount());
        verify(messagingTemplate).convertAndSend("/topic/orders/demo-user", sellOrder);
    }

    private Order order(OrderSide side) {
        Order order = new Order("demo-user", "AAPL", side, OrderType.LIMIT,
                new BigDecimal("100.00"), new BigDecimal("2"), BigDecimal.ZERO,
                OrderStatus.FILLED, Instant.parse("2026-11-02T10:00:00Z"));
        order.setId("order-1");
        return order;
    }
}
