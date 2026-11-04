package com.tradepulse.tradepulse.controller;

import com.tradepulse.tradepulse.market.MarketDataProcessor;
import com.tradepulse.tradepulse.market.MarketDataProcessor.MarketCandle;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/market/candles")
public class MarketCandleController {

    private static final Set<String> SUPPORTED_INTERVALS = Set.of("1m");

    private final MarketDataProcessor marketDataProcessor;

    public MarketCandleController(MarketDataProcessor marketDataProcessor) {
        this.marketDataProcessor = marketDataProcessor;
    }

    @GetMapping
    public List<MarketCandle> getCandles(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String interval) {
        if (symbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A market symbol is required.");
        }
        if (!SUPPORTED_INTERVALS.contains(interval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only the 1m interval is supported.");
        }
        return marketDataProcessor.getRecentCandles(symbol.trim().toUpperCase(Locale.ROOT));
    }
}
