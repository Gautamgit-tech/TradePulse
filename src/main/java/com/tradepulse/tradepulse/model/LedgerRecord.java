package com.tradepulse.tradepulse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Document(collection = "ledger_records")
public class LedgerRecord {

    @Id
    private String id;
    private String transactionGroupId;
    private String fromWalletId;
    private String toWalletId;
    private BigDecimal amount;
    private String reason;
    private Instant timestamp;

    public LedgerRecord() {
    }

    public LedgerRecord(String transactionGroupId, String fromWalletId, String toWalletId,
                        BigDecimal amount, String reason, Instant timestamp) {
        this.transactionGroupId = transactionGroupId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTransactionGroupId() {
        return transactionGroupId;
    }

    public void setTransactionGroupId(String transactionGroupId) {
        this.transactionGroupId = transactionGroupId;
    }

    public String getFromWalletId() {
        return fromWalletId;
    }

    public void setFromWalletId(String fromWalletId) {
        this.fromWalletId = fromWalletId;
    }

    public String getToWalletId() {
        return toWalletId;
    }

    public void setToWalletId(String toWalletId) {
        this.toWalletId = toWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
