package com.tradepulse.tradepulse.service;

import com.tradepulse.tradepulse.dto.PlaceOrderRequest;
import com.tradepulse.tradepulse.model.LedgerRecord;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderType;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.repository.LedgerRecordRepository;
import com.tradepulse.tradepulse.repository.OrderRepository;
import com.tradepulse.tradepulse.repository.WalletRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class TradingService {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

    private final WalletRepository walletRepository;
    private final OrderRepository orderRepository;
    private final LedgerRecordRepository ledgerRecordRepository;
    private final MongoTemplate mongoTemplate;
    private final OrderMatchingService orderMatchingService;
    private final HoldingService holdingService;
    private final SimpMessagingTemplate messagingTemplate;

    public TradingService(WalletRepository walletRepository,
                          OrderRepository orderRepository,
                          LedgerRecordRepository ledgerRecordRepository,
                          MongoTemplate mongoTemplate,
                          OrderMatchingService orderMatchingService,
                          HoldingService holdingService,
                          SimpMessagingTemplate messagingTemplate) {
        this.walletRepository = walletRepository;
        this.orderRepository = orderRepository;
        this.ledgerRecordRepository = ledgerRecordRepository;
        this.mongoTemplate = mongoTemplate;
        this.orderMatchingService = orderMatchingService;
        this.holdingService = holdingService;
        this.messagingTemplate = messagingTemplate;
    }

    public Wallet getOrCreateWallet(String userId) {
        return walletRepository.findByUserIdAndCurrency(userId, DEFAULT_CURRENCY)
                .orElseGet(() -> createDefaultWallet(userId));
    }

    public List<Order> getRecentOrders(String userId) {
        return orderRepository.findTop100ByUserIdOrderByCreatedAtDesc(userId);
    }

    public Order cancelOrder(String orderId, String userId) {
        Order existingOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderCancellationException("Order not found", true));
        if (!Objects.equals(existingOrder.getUserId(), userId)) {
            throw new OrderCancellationException("Order not found", true);
        }
        if (existingOrder.getSide() == null || existingOrder.getPrice() == null
                || existingOrder.getQuantity() == null) {
            throw new OrderCancellationException("Order cannot be cancelled", false);
        }

        Order cancelledOrder = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(orderId)
                        .and("userId").is(userId)
                        .and("status").is(OrderStatus.PENDING)),
                new Update().set("status", OrderStatus.CANCELLED),
                FindAndModifyOptions.options().returnNew(true),
                Order.class);
        if (cancelledOrder == null) {
            throw new OrderCancellationException("Order cannot be cancelled", false);
        }

        if (cancelledOrder.getSide() == OrderSide.BUY) {
            unlockCancelledBuyOrder(cancelledOrder);
        } else {
            try {
                holdingService.releaseSellReservation(cancelledOrder);
            } catch (RuntimeException failure) {
                restorePendingStatus(cancelledOrder, failure);
                throw failure;
            }
        }

        messagingTemplate.convertAndSend("/topic/orders/" + userId, cancelledOrder);
        return cancelledOrder;
    }

    private void unlockCancelledBuyOrder(Order order) {
        BigDecimal amount = order.getPrice().multiply(order.getQuantity());
        Wallet wallet = walletRepository.findByUserIdAndCurrency(order.getUserId(), DEFAULT_CURRENCY)
                .orElseThrow(() -> {
                    OrderCancellationException failure =
                            new OrderCancellationException("Wallet not found for order cancellation", true);
                    restorePendingStatus(order, failure);
                    return failure;
                });
        Query walletQuery = Query.query(Criteria.where("_id").is(wallet.getId())
                .and("lockedBalance").gte(amount));
        Update unlock = new Update()
                .inc("lockedBalance", amount.negate())
                .inc("availableBalance", amount);
        Wallet updatedWallet = mongoTemplate.findAndModify(
                walletQuery, unlock, FindAndModifyOptions.options().returnNew(true), Wallet.class);
        if (updatedWallet == null) {
            OrderCancellationException failure =
                    new OrderCancellationException("Could not unlock funds for order cancellation", false);
            restorePendingStatus(order, failure);
            throw failure;
        }

        String transactionGroupId = UUID.randomUUID().toString();
        try {
            ledgerRecordRepository.save(new LedgerRecord(
                    transactionGroupId, wallet.getId(), wallet.getId(), amount,
                    "ORDER_CANCEL_UNLOCK", Instant.now()));
        } catch (RuntimeException failure) {
            try {
                UpdateResult rollback = mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(wallet.getId())
                                .and("availableBalance").gte(amount)),
                        new Update().inc("availableBalance", amount.negate())
                                .inc("lockedBalance", amount),
                        Wallet.class);
                if (rollback.getModifiedCount() != 1) {
                    throw new IllegalStateException("Could not roll back wallet unlock after ledger failure.");
                }
                restorePendingStatus(order, failure);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void restorePendingStatus(Order order, RuntimeException originalFailure) {
        try {
            UpdateResult rollback = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(order.getId())
                            .and("status").is(OrderStatus.CANCELLED)),
                    new Update().set("status", OrderStatus.PENDING),
                    Order.class);
            if (rollback.getModifiedCount() != 1) {
                throw new IllegalStateException("Could not restore pending status for order " + order.getId());
            }
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private Wallet createDefaultWallet(String userId) {
        try {
            Wallet wallet = walletRepository.save(new Wallet(
                    userId, DEFAULT_CURRENCY, INITIAL_BALANCE, BigDecimal.ZERO));
            return Objects.requireNonNull(wallet, "Wallet repository returned no saved wallet.");
        } catch (DuplicateKeyException duplicateKeyException) {
            return walletRepository.findByUserIdAndCurrency(userId, DEFAULT_CURRENCY)
                    .orElseThrow(() -> duplicateKeyException);
        }
    }

    public Order placeOrder(PlaceOrderRequest request) {
        Wallet wallet = getOrCreateWallet(request.getUserId());
        BigDecimal lockAmount = request.getPrice().multiply(request.getQuantity());
        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);
        String transactionGroupId = UUID.randomUUID().toString();
        Wallet updatedWallet = null;
        boolean sharesReserved = false;

        if (request.getSide() == OrderSide.BUY) {
            updatedWallet = lockFunds(wallet, lockAmount);
        } else {
            holdingService.reserveSellShares(request.getUserId(), symbol, request.getQuantity());
            sharesReserved = true;
        }

        Instant now = Instant.now();
        boolean ledgerSaved = false;
        Order savedOrder;
        try {
            if (updatedWallet != null) {
                ledgerRecordRepository.save(new LedgerRecord(
                        transactionGroupId, updatedWallet.getId(), updatedWallet.getId(),
                        lockAmount, "ORDER_FUND_LOCK", now));
                ledgerSaved = true;
            }

            Order order = new Order(
                    request.getUserId(), symbol,
                    request.getSide(),
                    request.getOrderType(),
                    request.getPrice(),
                    request.getQuantity(),
                    BigDecimal.ZERO,
                    OrderStatus.PENDING,
                    now);
            savedOrder = orderRepository.save(order);
        } catch (RuntimeException failure) {
            if (updatedWallet != null) {
                rollbackLock(updatedWallet.getId(), lockAmount, transactionGroupId, ledgerSaved, failure);
            }
            if (sharesReserved) {
                try {
                    Order reservation = new Order(request.getUserId(), symbol, OrderSide.SELL,
                            request.getOrderType(), request.getPrice(), request.getQuantity(),
                            BigDecimal.ZERO, OrderStatus.PENDING, now);
                    holdingService.releaseSellReservation(reservation);
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            throw failure;
        }
        if (savedOrder.getOrderType() == OrderType.MARKET) {
            return orderMatchingService.executeMarketOrder(savedOrder);
        }
        return savedOrder;
    }

    private Wallet lockFunds(Wallet wallet, BigDecimal amount) {
        Query query = Query.query(Criteria.where("_id").is(wallet.getId())
                .and("availableBalance").gte(amount));
        Update update = new Update()
                .inc("availableBalance", amount.negate())
                .inc("lockedBalance", amount);
        Wallet updatedWallet = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), Wallet.class);

        if (updatedWallet == null) {
            throw new InsufficientFundsException();
        }
        return updatedWallet;
    }

    private void rollbackLock(String walletId, BigDecimal amount, String transactionGroupId,
                              boolean ledgerSaved, RuntimeException originalFailure) {
        try {
            var result = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(walletId)),
                    new Update().inc("availableBalance", amount).inc("lockedBalance", amount.negate()),
                    Wallet.class);
            if (result.getModifiedCount() != 1) {
                throw new IllegalStateException("Could not restore wallet balance after order failure.");
            }
            if (ledgerSaved) {
                ledgerRecordRepository.save(new LedgerRecord(
                        transactionGroupId, walletId, walletId, amount,
                        "ORDER_FUND_LOCK_ROLLBACK", Instant.now()));
            }
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }
}
