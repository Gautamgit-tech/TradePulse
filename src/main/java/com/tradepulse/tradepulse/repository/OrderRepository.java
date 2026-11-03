package com.tradepulse.tradepulse.repository;

import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.OrderSide;
import com.tradepulse.tradepulse.model.OrderStatus;
import com.tradepulse.tradepulse.model.OrderType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.math.BigDecimal;
import java.util.List;

public interface OrderRepository extends MongoRepository<Order, String> {
    List<Order> findTop100ByUserIdOrderByCreatedAtDesc(String userId);

    List<Order> findByUserIdAndStatus(String userId, OrderStatus status);

    List<Order> findBySymbolAndSideAndStatusAndOrderTypeAndPriceGreaterThanEqualOrderByPriceDescCreatedAtAsc(
            String symbol, OrderSide side, OrderStatus status, OrderType orderType, BigDecimal price);

    List<Order> findBySymbolAndSideAndStatusAndOrderTypeAndPriceLessThanEqualOrderByPriceAscCreatedAtAsc(
            String symbol, OrderSide side, OrderStatus status, OrderType orderType, BigDecimal price);
}
