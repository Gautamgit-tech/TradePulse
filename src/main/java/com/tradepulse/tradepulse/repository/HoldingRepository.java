package com.tradepulse.tradepulse.repository;

import com.tradepulse.tradepulse.model.Holding;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends MongoRepository<Holding, String> {
    List<Holding> findByUserIdAndQuantityGreaterThanOrderBySymbolAsc(String userId, BigDecimal quantity);

    Optional<Holding> findByUserIdAndSymbol(String userId, String symbol);
}
