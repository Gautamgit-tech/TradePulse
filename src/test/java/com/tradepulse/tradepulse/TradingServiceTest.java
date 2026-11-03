package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.dto.PlaceOrderRequest;
import com.tradepulse.tradepulse.model.LedgerRecord;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderType;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.repository.LedgerRecordRepository;
import com.tradepulse.tradepulse.repository.OrderRepository;
import com.tradepulse.tradepulse.repository.WalletRepository;
import com.tradepulse.tradepulse.service.HoldingService;
import com.tradepulse.tradepulse.service.InsufficientFundsException;
import com.tradepulse.tradepulse.service.OrderMatchingService;
import com.tradepulse.tradepulse.service.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class TradingServiceTest {

    private WalletRepository walletRepository;
    private OrderRepository orderRepository;
    private LedgerRecordRepository ledgerRecordRepository;
    private MongoTemplate mongoTemplate;
    private OrderMatchingService orderMatchingService;
    private HoldingService holdingService;
    private SimpMessagingTemplate messagingTemplate;
    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        walletRepository = mock(WalletRepository.class);
        orderRepository = mock(OrderRepository.class);
        ledgerRecordRepository = mock(LedgerRecordRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        orderMatchingService = mock(OrderMatchingService.class);
        holdingService = mock(HoldingService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        tradingService = new TradingService(
                walletRepository, orderRepository, ledgerRecordRepository, mongoTemplate,
                orderMatchingService, holdingService, messagingTemplate);
    }

    @Test
    void buyOrderAtomicallyLocksFundsAndWritesLedger() {
        Wallet wallet = new Wallet("trader-1", "INR", new BigDecimal("1000.00"), BigDecimal.ZERO);
        wallet.setId("wallet-1");
        Wallet lockedWallet = new Wallet("trader-1", "INR", new BigDecimal("800.00"),
                new BigDecimal("200.00"));
        lockedWallet.setId("wallet-1");
        when(walletRepository.findByUserIdAndCurrency("trader-1", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Wallet.class))).thenReturn(lockedWallet);
        when(ledgerRecordRepository.save(any(LedgerRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Order order = tradingService.placeOrder(buyOrderRequest());

        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertEquals(new BigDecimal("200.00"), order.getPrice().multiply(order.getQuantity()));
        assertEquals("AAPL", order.getSymbol());
        var queryCaptor = forClass(Query.class);
        var updateCaptor = forClass(Update.class);
        verify(mongoTemplate).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(Wallet.class));
        Document balanceGuard = (Document) queryCaptor.getValue().getQueryObject().get("availableBalance");
        assertEquals(new BigDecimal("200.00"), balanceGuard.get("$gte"));
        Document increments = (Document) updateCaptor.getValue().getUpdateObject().get("$inc");
        assertEquals(new BigDecimal("-200.00"), increments.get("availableBalance"));
        assertEquals(new BigDecimal("200.00"), increments.get("lockedBalance"));
        verify(ledgerRecordRepository).save(any(LedgerRecord.class));
    }

    @Test
    void buyOrderIsRejectedWhenConditionalWalletUpdateCannotLockFunds() {
        Wallet wallet = new Wallet("trader-1", "INR", new BigDecimal("100.00"), BigDecimal.ZERO);
        wallet.setId("wallet-1");
        when(walletRepository.findByUserIdAndCurrency("trader-1", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Wallet.class))).thenReturn(null);

        assertThrows(InsufficientFundsException.class,
                () -> tradingService.placeOrder(buyOrderRequest()));

        verify(orderRepository, never()).save(any(Order.class));
        verify(ledgerRecordRepository, never()).save(any(LedgerRecord.class));
    }

    @Test
    void marketBuyIsSentThroughImmediateFillSettlement() {
        Wallet wallet = new Wallet("trader-1", "INR", new BigDecimal("1000.00"), BigDecimal.ZERO);
        wallet.setId("wallet-1");
        Wallet lockedWallet = new Wallet("trader-1", "INR", new BigDecimal("800.00"),
                new BigDecimal("200.00"));
        lockedWallet.setId("wallet-1");
        when(walletRepository.findByUserIdAndCurrency("trader-1", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Wallet.class))).thenReturn(lockedWallet);
        when(ledgerRecordRepository.save(any(LedgerRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId("market-order-1");
            return order;
        });
        when(orderMatchingService.executeMarketOrder(any(Order.class)))
                .thenAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    order.setStatus(OrderStatus.FILLED);
                    order.setFilledQuantity(order.getQuantity());
                    return order;
                });
        PlaceOrderRequest request = buyOrderRequest();
        request.setOrderType(OrderType.MARKET);

        Order result = tradingService.placeOrder(request);

        assertEquals(OrderStatus.FILLED, result.getStatus());
        assertEquals(new BigDecimal("2"), result.getFilledQuantity());
        verify(orderMatchingService).executeMarketOrder(result);
    }

    @Test
    void sellOrderReservesOwnedSharesBeforeSaving() {
        Wallet wallet = new Wallet("trader-1", "INR", new BigDecimal("1000.00"), BigDecimal.ZERO);
        wallet.setId("wallet-1");
        when(walletRepository.findByUserIdAndCurrency("trader-1", "INR"))
                .thenReturn(Optional.of(wallet));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        PlaceOrderRequest request = buyOrderRequest();
        request.setSide(OrderSide.SELL);

        Order result = tradingService.placeOrder(request);

        assertEquals(OrderStatus.PENDING, result.getStatus());
        verify(holdingService).reserveSellShares("trader-1", "AAPL", new BigDecimal("2"));
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Wallet.class));
    }

    @Test
    void sellOrderRejectsWhenSharesAreInsufficient() {
        Wallet wallet = new Wallet("trader-1", "INR", new BigDecimal("1000.00"), BigDecimal.ZERO);
        wallet.setId("wallet-1");
        when(walletRepository.findByUserIdAndCurrency("trader-1", "INR"))
                .thenReturn(Optional.of(wallet));
        org.mockito.Mockito.doThrow(new com.tradepulse.tradepulse.service.InsufficientSharesException())
                .when(holdingService).reserveSellShares("trader-1", "AAPL", new BigDecimal("2"));
        PlaceOrderRequest request = buyOrderRequest();
        request.setSide(OrderSide.SELL);

        assertThrows(com.tradepulse.tradepulse.service.InsufficientSharesException.class,
                () -> tradingService.placeOrder(request));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void cancellingPendingBuyAtomicallyUnlocksFundsAndAudits() {
        Order pendingOrder = new Order("trader-1", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100.00"), new BigDecimal("2"), BigDecimal.ZERO,
                OrderStatus.PENDING, java.time.Instant.now());
        pendingOrder.setId("order-1");
        Order cancelledOrder = new Order("trader-1", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100.00"), new BigDecimal("2"), BigDecimal.ZERO,
                OrderStatus.CANCELLED, pendingOrder.getCreatedAt());
        cancelledOrder.setId("order-1");
        Wallet wallet = new Wallet("trader-1", "INR", new BigDecimal("800.00"),
                new BigDecimal("200.00"));
        wallet.setId("wallet-1");
        Wallet unlockedWallet = new Wallet("trader-1", "INR", new BigDecimal("1000.00"),
                BigDecimal.ZERO);
        unlockedWallet.setId("wallet-1");

        when(orderRepository.findById("order-1")).thenReturn(Optional.of(pendingOrder));
        when(walletRepository.findByUserIdAndCurrency("trader-1", "INR"))
                .thenReturn(Optional.of(wallet));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(cancelledOrder);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Wallet.class))).thenReturn(unlockedWallet);
        when(ledgerRecordRepository.save(any(LedgerRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Order result = tradingService.cancelOrder("order-1", "trader-1");

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        var updates = forClass(Update.class);
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), updates.capture(),
                any(FindAndModifyOptions.class), any());
        Document cancellationInc = (Document) updates.getAllValues().get(1).getUpdateObject().get("$inc");
        assertEquals(new BigDecimal("-200.00"), cancellationInc.get("lockedBalance"));
        assertEquals(new BigDecimal("200.00"), cancellationInc.get("availableBalance"));
        var ledgerCaptor = forClass(LedgerRecord.class);
        verify(ledgerRecordRepository).save(ledgerCaptor.capture());
        assertEquals("ORDER_CANCEL_UNLOCK", ledgerCaptor.getValue().getReason());
        assertEquals(new BigDecimal("200.00"), ledgerCaptor.getValue().getAmount());
        verify(messagingTemplate).convertAndSend("/topic/orders/trader-1", cancelledOrder);
    }

    @Test
    void cancellingOrderOwnedByAnotherUserIsNotFound() {
        Order order = new Order("another-user", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100.00"), BigDecimal.ONE, BigDecimal.ZERO,
                OrderStatus.PENDING, java.time.Instant.now());
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

        var exception = assertThrows(com.tradepulse.tradepulse.service.OrderCancellationException.class,
                () -> tradingService.cancelOrder("order-1", "trader-1"));

        assertTrue(exception.isNotFound());
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Order.class));
    }

    private PlaceOrderRequest buyOrderRequest() {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setUserId("trader-1");
        request.setSymbol("aapl");
        request.setSide(OrderSide.BUY);
        request.setOrderType(OrderType.LIMIT);
        request.setPrice(new BigDecimal("100.00"));
        request.setQuantity(new BigDecimal("2"));
        return request;
    }
}
