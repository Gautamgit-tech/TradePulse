package com.tradepulse.tradepulse.controller;

import com.tradepulse.tradepulse.dto.MarketSymbolResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market")
public class MarketSymbolController {

    private static final List<MarketSymbolResponse> SYMBOLS = List.of(
            new MarketSymbolResponse("AAPL", "Apple Inc.", "EQUITY", "NASDAQ"),
            new MarketSymbolResponse("NVDA", "NVIDIA Corporation", "EQUITY", "NASDAQ"),
            new MarketSymbolResponse("MSFT", "Microsoft Corporation", "EQUITY", "NASDAQ"),
            new MarketSymbolResponse("TSLA", "Tesla Inc.", "EQUITY", "NASDAQ"),
            new MarketSymbolResponse("AMZN", "Amazon.com Inc.", "EQUITY", "NASDAQ"),
            new MarketSymbolResponse("GOOGL", "Alphabet Inc.", "EQUITY", "NASDAQ"),
            new MarketSymbolResponse("BINANCE:BTCUSDT", "Bitcoin / Tether", "CRYPTO", "BINANCE"),
            new MarketSymbolResponse("BINANCE:ETHUSDT", "Ethereum / Tether", "CRYPTO", "BINANCE"),
            new MarketSymbolResponse("BINANCE:SOLUSDT", "Solana / Tether", "CRYPTO", "BINANCE"));

    @GetMapping("/symbols")
    public List<MarketSymbolResponse> getSymbols() {
        return SYMBOLS;
    }
}
