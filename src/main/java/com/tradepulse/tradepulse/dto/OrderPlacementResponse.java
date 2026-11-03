package com.tradepulse.tradepulse.dto;

import com.tradepulse.tradepulse.model.Holding;
import com.tradepulse.tradepulse.model.Order;
import com.tradepulse.tradepulse.model.Wallet;

import java.util.List;

public record OrderPlacementResponse(Order order, Wallet wallet, List<Holding> holdings) {
}
