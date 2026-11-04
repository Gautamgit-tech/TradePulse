package com.tradepulse.tradepulse.controller;

import com.tradepulse.tradepulse.market.MarketDataProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
public class MarketDataStatusController {

    private final MarketDataProvider marketDataProvider;

    public MarketDataStatusController(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "provider", marketDataProvider.getProviderName(),
                "connected", marketDataProvider.isConnected());
    }
}
