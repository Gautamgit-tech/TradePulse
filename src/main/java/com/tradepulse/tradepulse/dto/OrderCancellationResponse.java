package com.tradepulse.tradepulse.dto;

import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.Wallet;

public record OrderCancellationResponse(Order order, Wallet wallet) {
}
