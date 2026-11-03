package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.model.Holding;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderType;
import com.tradepulse.tradepulse.repository.HoldingRepository;
import com.tradepulse.tradepulse.repository.OrderRepository;
import com.tradepulse.tradepulse.service.HoldingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HoldingServiceTest {

    private HoldingRepository holdingRepository;
    private OrderRepository orderRepository;
    private MongoTemplate mongoTemplate;
    private HoldingService holdingService;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        orderRepository = mock(OrderRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        holdingService = new HoldingService(holdingRepository, orderRepository, mongoTemplate);
    }

    @Test
    void buyFillAtomicallyUpsertsQuantityInvestmentAndWeightedAverage() {
        Holding updated = new Holding("demo-user", "AAPL", new BigDecimal("3"),
                new BigDecimal("110.00"), new BigDecimal("330.00"));
        when(mongoTemplate.findAndModify(any(Query.class), any(AggregationUpdate.class),
                any(FindAndModifyOptions.class), eq(Holding.class))).thenReturn(updated);

        Holding result = holdingService.recordBuyFill(new Order(
                "demo-user", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("120.00"), BigDecimal.ONE, BigDecimal.ONE,
                OrderStatus.FILLED, Instant.parse("2026-11-02T10:00:00Z")));

        assertTrue(result.getAverageBuyPrice().compareTo(new BigDecimal("110.00")) == 0);
        var updateCaptor = org.mockito.ArgumentCaptor.forClass(AggregationUpdate.class);
        var optionsCaptor = org.mockito.ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(),
                optionsCaptor.capture(), eq(Holding.class));
        String update = updateCaptor.getValue().getUpdateObject().toJson();
        assertTrue(update.contains("quantity"));
        assertTrue(update.contains("totalInvested"));
        assertTrue(update.contains("averageBuyPrice"));
        assertTrue(update.contains("$divide"));
        assertTrue(optionsCaptor.getValue().isUpsert());
    }

    @Test
    void holdingsReadReconcilesPreviouslyFilledMarketBuy() {
        Order filledMarketBuy = new Order(
                "demo-user", "AAPL", OrderSide.BUY, OrderType.MARKET,
                new BigDecimal("2475.00"), new BigDecimal("2"), new BigDecimal("2"),
                OrderStatus.FILLED, Instant.parse("2026-11-02T10:00:00Z"));
        when(orderRepository.findByUserIdAndStatus("demo-user", OrderStatus.FILLED))
                .thenReturn(List.of(filledMarketBuy));
        when(holdingRepository.findByUserIdAndSymbol("demo-user", "AAPL"))
                .thenReturn(Optional.empty());
        when(holdingRepository.save(any(Holding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(holdingRepository.findByUserIdAndQuantityGreaterThanOrderBySymbolAsc(
                "demo-user", BigDecimal.ZERO))
                .thenReturn(List.of(), List.of(
                        new Holding("demo-user", "AAPL", new BigDecimal("2"),
                                new BigDecimal("2475.00000000"), new BigDecimal("4950.00"))));

        List<Holding> result = holdingService.getActiveHoldings("demo-user");

        assertEquals(1, result.size());
        var savedCaptor = org.mockito.ArgumentCaptor.forClass(Holding.class);
        verify(holdingRepository).save(savedCaptor.capture());
        assertEquals(new BigDecimal("2"), savedCaptor.getValue().getQuantity());
        assertEquals(new BigDecimal("4950.00"), savedCaptor.getValue().getTotalInvested());
        assertEquals(new BigDecimal("2475.00000000"), savedCaptor.getValue().getAverageBuyPrice());
    }

    @Test
    void partialSaleReducesQuantityAndCostBasisAndCalculatesRealizedPnl() {
        Holding updated = new Holding("demo-user", "AAPL", BigDecimal.ONE,
                new BigDecimal("100.00"), new BigDecimal("100.00"));
        updated.setReservedQuantity(BigDecimal.ZERO);
        when(mongoTemplate.findAndModify(any(Query.class), any(AggregationUpdate.class),
                any(FindAndModifyOptions.class), eq(Holding.class))).thenReturn(updated);
        Order sell = new Order("demo-user", "AAPL", OrderSide.SELL, OrderType.MARKET,
                new BigDecimal("125.00"), BigDecimal.ONE, BigDecimal.ONE,
                OrderStatus.FILLED, Instant.parse("2026-11-02T10:00:00Z"));

        BigDecimal realized = holdingService.recordSellFill(sell, new BigDecimal("125.00"));

        assertEquals(new BigDecimal("25.00"), realized);
        assertEquals(new BigDecimal("100.00"), sell.getAverageBuyPriceAtFill());
        var updateCaptor = org.mockito.ArgumentCaptor.forClass(AggregationUpdate.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(Holding.class));
        String update = updateCaptor.getValue().getUpdateObject().toJson();
        assertTrue(update.contains("quantity"));
        assertTrue(update.contains("reservedQuantity"));
        assertTrue(update.contains("totalInvested"));
    }

    @Test
    void reserveSellSharesFailsWhenAvailableQuantityIsTooLow() {
        when(mongoTemplate.findAndModify(any(Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(FindAndModifyOptions.class), eq(Holding.class))).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.tradepulse.tradepulse.service.InsufficientSharesException.class,
                () -> holdingService.reserveSellShares("demo-user", "AAPL", new BigDecimal("3")));
    }

    @Test
    void fullSaleReducesPositionToZeroWithoutChangingAveragePrice() {
        Holding updated = new Holding("demo-user", "AAPL", BigDecimal.ZERO,
                new BigDecimal("100.00"), BigDecimal.ZERO);
        updated.setReservedQuantity(BigDecimal.ZERO);
        when(mongoTemplate.findAndModify(any(Query.class), any(AggregationUpdate.class),
                any(FindAndModifyOptions.class), eq(Holding.class))).thenReturn(updated);
        Order sell = new Order("demo-user", "AAPL", OrderSide.SELL, OrderType.MARKET,
                new BigDecimal("90.00"), new BigDecimal("3"), new BigDecimal("3"),
                OrderStatus.FILLED, Instant.parse("2026-11-02T10:00:00Z"));

        BigDecimal realized = holdingService.recordSellFill(sell, new BigDecimal("90.00"));

        assertEquals(new BigDecimal("-30.00"), realized);
        assertEquals(BigDecimal.ZERO, updated.getQuantity());
        assertEquals(new BigDecimal("100.00"), updated.getAverageBuyPrice());
    }
}
