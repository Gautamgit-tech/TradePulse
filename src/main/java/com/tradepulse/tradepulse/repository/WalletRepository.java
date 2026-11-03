package com.tradepulse.tradepulse.repository;

import com.tradepulse.tradepulse.model.Wallet;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface WalletRepository extends MongoRepository<Wallet, String> {
    Optional<Wallet> findByUserIdAndCurrency(String userId, String currency);
}
