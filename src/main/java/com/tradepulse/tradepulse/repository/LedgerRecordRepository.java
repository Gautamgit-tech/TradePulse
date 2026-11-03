package com.tradepulse.tradepulse.repository;

import com.tradepulse.tradepulse.model.LedgerRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LedgerRecordRepository extends MongoRepository<LedgerRecord, String> {
}
