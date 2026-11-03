package com.tradepulse.tradepulse.service;

import com.tradepulse.tradepulse.model.Holding;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.repository.HoldingRepository;
import com.tradepulse.tradepulse.repository.OrderRepository;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HoldingService {

    private final HoldingRepository holdingRepository;
    private final OrderRepository orderRepository;
    private final MongoTemplate mongoTemplate;

    public HoldingService(HoldingRepository holdingRepository,
                          OrderRepository orderRepository,
                          MongoTemplate mongoTemplate) {
        this.holdingRepository = holdingRepository;
        this.orderRepository = orderRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public List<Holding> getActiveHoldings(String userId) {
        List<Holding> holdings = holdingRepository.findByUserIdAndQuantityGreaterThanOrderBySymbolAsc(
                userId, BigDecimal.ZERO);
        if (holdings.isEmpty()) {
            reconcileFromFilledBuyOrders(userId);
            return holdingRepository.findByUserIdAndQuantityGreaterThanOrderBySymbolAsc(
                    userId, BigDecimal.ZERO);
        }
        return holdings;
    }

    private void reconcileFromFilledBuyOrders(String userId) {
        Map<String, PositionTotals> totalsBySymbol = new LinkedHashMap<>();
        List<Order> filledOrders = new ArrayList<>(
                orderRepository.findByUserIdAndStatus(userId, OrderStatus.FILLED));
        filledOrders.sort(Comparator.comparing(
                order -> order.getFilledAt() == null ? order.getCreatedAt() : order.getFilledAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));

        for (Order order : filledOrders) {
            BigDecimal quantity = filledQuantity(order);
            PositionTotals totals = totalsBySymbol.computeIfAbsent(
                    order.getSymbol(), ignored -> new PositionTotals());
            if (order.getSide() == OrderSide.BUY) {
                BigDecimal fillPrice = order.getExecutedPrice() == null
                        ? order.getPrice() : order.getExecutedPrice();
                totals.quantity = totals.quantity.add(quantity);
                totals.totalInvested = totals.totalInvested.add(fillPrice.multiply(quantity));
            } else if (totals.quantity.signum() > 0) {
                BigDecimal soldQuantity = quantity.min(totals.quantity);
                BigDecimal averagePrice = totals.totalInvested.divide(
                        totals.quantity, 8, RoundingMode.HALF_UP);
                totals.quantity = totals.quantity.subtract(soldQuantity);
                totals.totalInvested = totals.totalInvested.subtract(averagePrice.multiply(soldQuantity));
            }
        }

        for (Map.Entry<String, PositionTotals> entry : totalsBySymbol.entrySet()) {
            PositionTotals totals = entry.getValue();
            if (totals.quantity.signum() <= 0) {
                continue;
            }
            BigDecimal averageBuyPrice = totals.totalInvested.divide(
                    totals.quantity, 8, RoundingMode.HALF_UP);
            Holding holding = holdingRepository.findByUserIdAndSymbol(userId, entry.getKey())
                    .orElseGet(() -> {
                        Holding newHolding = new Holding();
                        newHolding.setReservedQuantity(BigDecimal.ZERO);
                        return newHolding;
                    });
            holding.setUserId(userId);
            holding.setSymbol(entry.getKey());
            holding.setQuantity(totals.quantity);
            holding.setTotalInvested(totals.totalInvested);
            holding.setAverageBuyPrice(averageBuyPrice);
            holdingRepository.save(holding);
        }
    }

    public void reserveSellShares(String userId, String symbol, BigDecimal quantity) {
        if (holdingRepository.findByUserIdAndSymbol(userId, symbol).isEmpty()) {
            reconcileFromFilledBuyOrders(userId);
        }
        Document freeQuantity = new Document("$subtract", List.<Object>of(
                "$quantity", new Document("$ifNull",
                        List.<Object>of("$reservedQuantity", new Decimal128(BigDecimal.ZERO)))));
        Document expression = new Document("$gte", List.<Object>of(freeQuantity, new Decimal128(quantity)));
        Query query = new BasicQuery(new Document("userId", userId)
                .append("symbol", symbol)
                .append("$expr", expression));
        Holding holding = mongoTemplate.findAndModify(
                query,
                new org.springframework.data.mongodb.core.query.Update().inc("reservedQuantity", quantity),
                FindAndModifyOptions.options().returnNew(true),
                Holding.class);
        if (holding == null) {
            throw new InsufficientSharesException();
        }
    }

    public void releaseSellReservation(Order order) {
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("userId").is(order.getUserId())
                        .and("symbol").is(order.getSymbol())
                        .and("reservedQuantity").gte(order.getQuantity())),
                new org.springframework.data.mongodb.core.query.Update()
                        .inc("reservedQuantity", order.getQuantity().negate()),
                Holding.class);
        if (result.getModifiedCount() != 1) {
            throw new IllegalStateException("Could not release reserved shares for order " + order.getId());
        }
    }

    public BigDecimal recordSellFill(Order order, BigDecimal executionPrice) {
        BigDecimal quantity = filledQuantity(order);
        var oldQuantity = ConditionalOperators.ifNull("quantity").then(BigDecimal.ZERO);
        var oldReserved = ConditionalOperators.ifNull("reservedQuantity").then(BigDecimal.ZERO);
        var oldInvested = ConditionalOperators.ifNull("totalInvested").then(BigDecimal.ZERO);
        var averagePrice = ConditionalOperators.ifNull("averageBuyPrice").then(BigDecimal.ZERO);
        var newQuantity = ArithmeticOperators.Subtract.valueOf(oldQuantity).subtract(quantity);
        var newReserved = ArithmeticOperators.Subtract.valueOf(oldReserved).subtract(quantity);
        var soldCostBasis = ArithmeticOperators.Multiply.valueOf(averagePrice).multiplyBy(quantity);
        var newInvested = ArithmeticOperators.Subtract.valueOf(oldInvested).subtract(soldCostBasis);
        AggregationUpdate update = AggregationUpdate.update()
                .set("quantity").toValue(newQuantity)
                .set("reservedQuantity").toValue(newReserved)
                .set("totalInvested").toValue(newInvested);
        Query query = Query.query(Criteria.where("userId").is(order.getUserId())
                .and("symbol").is(order.getSymbol())
                .and("quantity").gte(quantity)
                .and("reservedQuantity").gte(quantity));
        Holding updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), Holding.class);
        if (updated == null) {
            throw new InsufficientSharesException();
        }
        BigDecimal averageBuyPrice = updated.getAverageBuyPrice();
        if (averageBuyPrice == null) {
            throw new IllegalStateException("Holding is missing its average buy price.");
        }
        order.setAverageBuyPriceAtFill(averageBuyPrice);
        BigDecimal realizedPnl = executionPrice.subtract(averageBuyPrice).multiply(quantity);
        order.setRealizedPnl(realizedPnl);
        return realizedPnl;
    }

    public void rollbackSellFill(Order order) {
        BigDecimal quantity = filledQuantity(order);
        BigDecimal costBasis = order.getAverageBuyPriceAtFill().multiply(quantity);
        var oldQuantity = ConditionalOperators.ifNull("quantity").then(BigDecimal.ZERO);
        var oldInvested = ConditionalOperators.ifNull("totalInvested").then(BigDecimal.ZERO);
        AggregationUpdate update = AggregationUpdate.update()
                .set("quantity").toValue(ArithmeticOperators.Add.valueOf(oldQuantity).add(quantity))
                .set("totalInvested").toValue(ArithmeticOperators.Add.valueOf(oldInvested).add(costBasis));
        Holding restored = mongoTemplate.findAndModify(
                Query.query(Criteria.where("userId").is(order.getUserId())
                        .and("symbol").is(order.getSymbol())),
                update,
                FindAndModifyOptions.options().returnNew(true),
                Holding.class);
        if (restored == null) {
            throw new IllegalStateException("Could not restore shares after failed sale settlement.");
        }
    }

    public Holding recordBuyFill(Order order) {
        BigDecimal quantity = filledQuantity(order);
        BigDecimal fillPrice = order.getExecutedPrice() == null
                ? order.getPrice() : order.getExecutedPrice();
        BigDecimal invested = fillPrice.multiply(quantity);

        var oldQuantity = ConditionalOperators.ifNull("quantity").then(BigDecimal.ZERO);
        var oldInvested = ConditionalOperators.ifNull("totalInvested").then(BigDecimal.ZERO);
        var newQuantity = ArithmeticOperators.Add.valueOf(oldQuantity).add(quantity);
        var newInvested = ArithmeticOperators.Add.valueOf(oldInvested).add(invested);

        AggregationUpdate update = AggregationUpdate.update()
                .set("quantity").toValue(newQuantity)
                .set("totalInvested").toValue(newInvested)
                .set("averageBuyPrice").toValue(
                        ArithmeticOperators.Divide.valueOf(newInvested).divideBy(newQuantity));
        Query holdingQuery = Query.query(Criteria.where("userId").is(order.getUserId())
                .and("symbol").is(order.getSymbol()));

        try {
            return upsertHolding(holdingQuery, update, true);
        } catch (DuplicateKeyException duplicateKeyException) {
            try {
                return upsertHolding(holdingQuery, update, false);
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(duplicateKeyException);
                throw retryFailure;
            }
        }
    }

    private Holding upsertHolding(Query query, AggregationUpdate update, boolean upsert) {
        Holding holding = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().upsert(upsert).returnNew(true),
                Holding.class);
        if (holding == null) {
            throw new IllegalStateException("Could not upsert active holding.");
        }
        return holding;
    }

    private BigDecimal filledQuantity(Order order) {
        return order.getFilledQuantity() == null || order.getFilledQuantity().signum() <= 0
                ? order.getQuantity()
                : order.getFilledQuantity();
    }

    private static class PositionTotals {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal totalInvested = BigDecimal.ZERO;
    }
}
