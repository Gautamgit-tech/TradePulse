package com.tradepulse.tradepulse.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepulse.tradepulse.MarketDataSimulator;
import com.tradepulse.tradepulse.service.OrderMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class FinnhubMarketDataProviderTest {

    private MarketDataProcessor marketDataProcessor;
    private FinnhubMarketDataProvider provider;

    @BeforeEach
    void setUp() {
        marketDataProcessor = mock(MarketDataProcessor.class);
        MarketDataSimulator simulator = new MarketDataSimulator(
                new MarketDataProcessor(mock(SimpMessagingTemplate.class), mock(OrderMatchingService.class)));
        provider = new FinnhubMarketDataProvider(
                simulator, marketDataProcessor, new ObjectMapper(), "test-api-key", "AAPL,MSFT,NVDA");
    }

    @Test
    void parsesFinnhubTradesAndForwardsTheNormalizedTick() {
        provider.processMessage("""
                {"type":"trade","data":[{"p":220.55,"s":"AAPL","t":1625097600000,"v":100}]}
                """);

        verify(marketDataProcessor).processTick(
                "AAPL", new BigDecimal("220.55"), 100L, Instant.ofEpochMilli(1625097600000L));
    }

    @Test
    void ignoresUnconfiguredSymbolsAndNonTradeMessages() {
        provider.processMessage("""
                {"type":"trade","data":[{"p":220.55,"s":"TSLA","t":1625097600000,"v":100}]}
                """);
        provider.processMessage("""
                {"type":"ping"}
                """);

        verify(marketDataProcessor, never()).processTick(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }
}
