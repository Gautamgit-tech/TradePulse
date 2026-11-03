package com.tradepulse.tradepulse.service;

import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderType;
import com.tradepulse.tradepulse.model.LedgerRecord;
import com.tradepulse.tradepulse.model.Wallet;
import com.tradepulse.tradepulse.repository.LedgerRecordRepository;
import com.tradepulse.tradepulse.repository.OrderRepository;
import com.tradepulse.tradepulse.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;

@Service
public class OrderMatchingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderMatchingService.class);
    private static final String CURRENCY = "INR";

    private final OrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final LedgerRecordRepository ledgerRecordRepository;
    private final MongoTemplate mongoTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final HoldingService holdingService;

    public OrderMatchingService(OrderRepository orderRepository,
                                WalletRepository walletRepository,
                                LedgerRecordRepository ledgerRecordRepository,
                                MongoTemplate mongoTemplate,
                                SimpMessagingTemplate messagingTemplate,
                                HoldingService holdingService) {
        this.orderRepository = orderRepository;
        this.walletRepository = walletRepository;
        this.ledgerRecordRepository = ledgerRecordRepository;
        this.mongoTemplate = mongoTemplate;
        this.messagingTemplate = messagingTemplate;
        this.holdingService = holdingService;
    }

    public void matchPendingOrders(String symbol, BigDecimal marketPrice) {
        List<Order> buyOrders = orderRepository
                .findBySymbolAndSideAndStatusAndOrderTypeAndPriceGreaterThanEqualOrderByPriceDescCreatedAtAsc(
                        symbol, OrderSide.BUY, OrderStatus.PENDING, OrderType.LIMIT, marketPrice);
        List<Order> sellOrders = orderRepository
                .findBySymbolAndSideAndStatusAndOrderTypeAndPriceLessThanEqualOrderByPriceAscCreatedAtAsc(
                        symbol, OrderSide.SELL, OrderStatus.PENDING, OrderType.LIMIT, marketPrice);

        buyOrders.forEach(order -> match(order, marketPrice));
        sellOrders.forEach(order -> match(order, marketPrice));
    }

    public Order executeMarketOrder(Order marketOrder) {
        if (marketOrder.getOrderType() != OrderType.MARKET) {
            throw new IllegalArgumentException("Only MARKET orders can use immediate execution.");
        }
        Query claimQuery = Query.query(Criteria.where("_id").is(marketOrder.getId())
                .and("status").is(OrderStatus.PENDING)
                .and("orderType").is(OrderType.MARKET));
        Order filledOrder = mongoTemplate.findAndModify(
                claimQuery,
                new Update().set("status", OrderStatus.FILLED)
                        .set("filledQuantity", marketOrder.getQuantity())
                        .set("executedPrice", marketOrder.getPrice())
                        .set("filledAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true),
                Order.class);
        if (filledOrder == null) {
            return orderRepository.findById(marketOrder.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Market order disappeared during execution: " + marketOrder.getId()));
        }
        return settleFilledOrder(filledOrder, marketOrder.getPrice());
    }

    private void match(Order candidate, BigDecimal marketPrice) {
        Criteria criteria = Criteria.where("_id").is(candidate.getId())
                .and("symbol").is(candidate.getSymbol())
                .and("side").is(candidate.getSide())
                .and("status").is(OrderStatus.PENDING)
                .and("orderType").is(OrderType.LIMIT);
        if (candidate.getSide() == OrderSide.BUY) {
            criteria.and("price").gte(marketPrice);
        } else {
            criteria.and("price").lte(marketPrice);
        }

        Update fillOrder = new Update()
                .set("status", OrderStatus.FILLED)
                .set("filledQuantity", candidate.getQuantity())
                .set("executedPrice", marketPrice)
                .set("filledAt", Instant.now());
        Order filledOrder = mongoTemplate.findAndModify(
                Query.query(criteria),
                fillOrder,
                FindAndModifyOptions.options().returnNew(true),
                Order.class);
        if (filledOrder == null) {
            return;
        }

        settleFilledOrder(filledOrder, marketPrice);
    }

    private Order settleFilledOrder(Order filledOrder, BigDecimal executionPrice) {
        if (filledOrder.getSide() == OrderSide.BUY
                && !debitLockedFunds(filledOrder, executionPrice)) {
            rejectInconsistentBuyFill(filledOrder);
            return filledOrder;
        }

        if (filledOrder.getSide() == OrderSide.BUY) {
            holdingService.recordBuyFill(filledOrder);
        } else {
            settleSellFill(filledOrder, executionPrice);
        }

        messagingTemplate.convertAndSend("/topic/orders/" + filledOrder.getUserId(), filledOrder);
        return filledOrder;
    }

    private void settleSellFill(Order order, BigDecimal executionPrice) {
        BigDecimal quantity = order.getFilledQuantity();
        BigDecimal proceeds = executionPrice.multiply(quantity);
        Wallet wallet = null;
        boolean sharesSettled = false;
        boolean walletCredited = false;
        boolean ledgerSaved = false;
        String transactionGroupId = UUID.randomUUID().toString();
        try {
            BigDecimal realizedPnl = holdingService.recordSellFill(order, executionPrice);
            sharesSettled = true;
            wallet = walletRepository.findByUserIdAndCurrency(order.getUserId(), CURRENCY)
                    .orElseThrow(() -> new IllegalStateException(
                            "INR wallet for user " + order.getUserId() + " was not found."));
            var credit = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(wallet.getId())),
                    new Update().inc("availableBalance", proceeds),
                    Wallet.class);
            if (credit.getModifiedCount() != 1) {
                throw new IllegalStateException("Could not credit sale proceeds to the INR wallet.");
            }
            walletCredited = true;
            ledgerRecordRepository.save(new LedgerRecord(
                    transactionGroupId, wallet.getId(), wallet.getId(), proceeds,
                    "EQUITY_SALE_CREDIT", Instant.now()));
            ledgerSaved = true;

            var update = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(order.getId())
                            .and("status").is(OrderStatus.FILLED)),
                    new Update().set("realizedPnl", realizedPnl)
                            .set("averageBuyPriceAtFill", order.getAverageBuyPriceAtFill())
                            .set("executedPrice", executionPrice),
                    Order.class);
            if (update.getModifiedCount() != 1) {
                throw new IllegalStateException("Could not persist realized P&L for sale order " + order.getId());
            }
            order.setExecutedPrice(executionPrice);
            order.setRealizedPnl(realizedPnl);
        } catch (RuntimeException settlementFailure) {
            if (walletCredited && wallet != null) {
                try {
                    var rollback = mongoTemplate.updateFirst(
                            Query.query(Criteria.where("_id").is(wallet.getId())
                                    .and("availableBalance").gte(proceeds)),
                            new Update().inc("availableBalance", proceeds.negate()),
                            Wallet.class);
                    if (rollback.getModifiedCount() != 1) {
                        throw new IllegalStateException("Could not reverse uncommitted sale proceeds.");
                    }
                } catch (RuntimeException rollbackFailure) {
                    settlementFailure.addSuppressed(rollbackFailure);
                }
            }
            try {
                if (sharesSettled) {
                    holdingService.rollbackSellFill(order);
                } else {
                    holdingService.releaseSellReservation(order);
                }
            } catch (RuntimeException rollbackFailure) {
                settlementFailure.addSuppressed(rollbackFailure);
            }
            if (ledgerSaved && wallet != null) {
                try {
                    ledgerRecordRepository.save(new LedgerRecord(
                            transactionGroupId, wallet.getId(), wallet.getId(), proceeds,
                            "EQUITY_SALE_CREDIT_ROLLBACK", Instant.now()));
                } catch (RuntimeException rollbackFailure) {
                    settlementFailure.addSuppressed(rollbackFailure);
                }
            }
            rejectSellOrder(order);
            LOGGER.error("Could not settle SELL order {}.", order.getId(), settlementFailure);
        }
    }

    private void rejectSellOrder(Order order) {
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.getId())
                        .and("status").is(OrderStatus.FILLED)),
                new Update().set("status", OrderStatus.REJECTED)
                        .set("filledQuantity", BigDecimal.ZERO),
                Order.class);
        if (result.getModifiedCount() != 1) {
            throw new IllegalStateException("Could not reject unsettled SELL order " + order.getId());
        }
        order.setStatus(OrderStatus.REJECTED);
        order.setFilledQuantity(BigDecimal.ZERO);
    }

    private boolean debitLockedFunds(Order order, BigDecimal executionPrice) {
        Wallet wallet = walletRepository.findByUserIdAndCurrency(order.getUserId(), CURRENCY).orElse(null);
        if (wallet == null) {
            LOGGER.error("Cannot settle BUY order {}: INR wallet for user {} was not found.",
                    order.getId(), order.getUserId());
            return false;
        }

        BigDecimal lockedAmount = order.getPrice().multiply(order.getQuantity());
        BigDecimal executionAmount = executionPrice.multiply(order.getQuantity());
        BigDecimal priceImprovement = lockedAmount.subtract(executionAmount);
        if (priceImprovement.signum() < 0) {
            LOGGER.error("Cannot settle BUY order {}: execution price exceeds the locked limit.",
                    order.getId());
            return false;
        }
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(wallet.getId())
                        .and("lockedBalance").gte(lockedAmount)),
                new Update().inc("lockedBalance", lockedAmount.negate())
                        .inc("availableBalance", priceImprovement),
                Wallet.class);
        if (result.getModifiedCount() != 1) {
            LOGGER.error("Cannot settle BUY order {}: locked INR balance is below {}.",
                    order.getId(), lockedAmount);
            return false;
        }
        return true;
    }

    private void rejectInconsistentBuyFill(Order order) {
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.getId())
                        .and("status").is(OrderStatus.FILLED)),
                new Update().set("status", OrderStatus.REJECTED)
                        .set("filledQuantity", BigDecimal.ZERO),
                Order.class);
        if (result.getModifiedCount() != 1) {
            throw new IllegalStateException("Could not reject unsettled BUY order " + order.getId());
        }
        order.setStatus(OrderStatus.REJECTED);
        order.setFilledQuantity(BigDecimal.ZERO);
        messagingTemplate.convertAndSend("/topic/orders/" + order.getUserId(), order);
    }
}
