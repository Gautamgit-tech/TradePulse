package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.service.OrderMatchingService;
import com.tradepulse.tradepulse.market.MarketDataProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.mockito.ArgumentCaptor;

class MarketDataSimulatorTest {

    private SimpMessagingTemplate messagingTemplate;
    private OrderMatchingService orderMatchingService;
    private MarketDataProcessor marketDataProcessor;
    private MarketDataSimulator simulator;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        orderMatchingService = mock(OrderMatchingService.class);
        marketDataProcessor = new MarketDataProcessor(messagingTemplate, orderMatchingService);
        simulator = new MarketDataSimulator(marketDataProcessor);
    }

    @Test
    void publishesAndMatchesTicksForEveryWatchlistSymbol() {
        simulator.sendTick();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> tickCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(18)).convertAndSend(topicCaptor.capture(), tickCaptor.capture());
        assertEquals(Set.of(
                "/topic/ticks/AAPL",
                "/topic/ticks/NVDA",
                "/topic/ticks/MSFT",
                "/topic/ticks/TSLA",
                "/topic/ticks/AMZN",
                "/topic/ticks/GOOGL",
                "/topic/ticks/BINANCE:BTCUSDT",
                "/topic/ticks/BINANCE:ETHUSDT",
                "/topic/ticks/BINANCE:SOLUSDT",
                "/topic/depth/AAPL",
                "/topic/depth/NVDA",
                "/topic/depth/MSFT",
                "/topic/depth/TSLA",
                "/topic/depth/AMZN",
                "/topic/depth/GOOGL",
                "/topic/depth/BINANCE:BTCUSDT",
                "/topic/depth/BINANCE:ETHUSDT",
                "/topic/depth/BINANCE:SOLUSDT"
                ), Set.copyOf(topicCaptor.getAllValues()));

        for (int index = 0; index < topicCaptor.getAllValues().size(); index++) {
            String topic = topicCaptor.getAllValues().get(index);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) tickCaptor.getAllValues().get(index);
            if (topic.startsWith("/topic/ticks/")) {
                String symbol = topic.substring("/topic/ticks/".length());
                assertEquals(symbol, message.get("symbol"));
                assertTrue(((BigDecimal) message.get("price")).signum() > 0);
                assertTrue(((BigDecimal) message.get("open")).signum() > 0);
                assertTrue(((BigDecimal) message.get("high")).compareTo((BigDecimal) message.get("low")) >= 0);
                assertEquals(message.get("price"), message.get("close"));
                long tickVolume = (long) message.get("tickVolume");
                assertTrue(tickVolume >= 50 && tickVolume <= 500);
                assertTrue(((Number) message.get("volume")).longValue() >= tickVolume);
                assertTrue(((Number) message.get("barTime")).longValue() % 60 == 0);
                verify(orderMatchingService).matchPendingOrders(eq(symbol), any(BigDecimal.class));
            } else {
                String symbol = topic.substring("/topic/depth/".length());
                assertEquals(symbol, message.get("symbol"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> bids = (List<Map<String, Object>>) message.get("bids");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> asks = (List<Map<String, Object>>) message.get("asks");
                assertEquals(5, bids.size());
                assertEquals(5, asks.size());
                assertTrue(((Number) message.get("buyPercentage")).intValue()
                        + ((Number) message.get("sellPercentage")).intValue() == 100);
                long bidTotal = 0;
                long askTotal = 0;
                for (int level = 0; level < 5; level++) {
                    bidTotal += ((Number) bids.get(level).get("quantity")).longValue();
                    askTotal += ((Number) asks.get(level).get("quantity")).longValue();
                    long bidQuantity = ((Number) bids.get(level).get("quantity")).longValue();
                    long askQuantity = ((Number) asks.get(level).get("quantity")).longValue();
                    assertTrue(bidQuantity >= 50 && bidQuantity <= 1200);
                    assertTrue(askQuantity >= 50 && askQuantity <= 1200);
                    if (level > 0) {
                        assertTrue(((BigDecimal) bids.get(level - 1).get("price"))
                                .compareTo((BigDecimal) bids.get(level).get("price")) > 0);
                        assertTrue(((BigDecimal) asks.get(level - 1).get("price"))
                                .compareTo((BigDecimal) asks.get(level).get("price")) < 0);
                    }
                }
                assertEquals(bidTotal, ((Number) message.get("totalBuyQuantity")).longValue());
                assertEquals(askTotal, ((Number) message.get("totalSellQuantity")).longValue());
            }
        }
    }

    @Test
    void retainsOneMinuteCandlesAndCapsHistoryAt150Entries() {
        Instant firstMinute = Instant.parse("2026-11-04T10:00:00Z");
        marketDataProcessor.processTick("AAPL", new BigDecimal("100.00"), 10, firstMinute);
        marketDataProcessor.processTick("AAPL", new BigDecimal("105.00"), 20, firstMinute.plusSeconds(30));
        marketDataProcessor.processTick("AAPL", new BigDecimal("98.00"), 30, firstMinute.plusSeconds(59));

        var firstCandle = marketDataProcessor.getRecentCandles("AAPL").get(0);
        assertEquals(firstMinute.getEpochSecond(), firstCandle.time());
        assertEquals(new BigDecimal("100.00"), firstCandle.open());
        assertEquals(new BigDecimal("105.00"), firstCandle.high());
        assertEquals(new BigDecimal("98.00"), firstCandle.low());
        assertEquals(new BigDecimal("98.00"), firstCandle.close());
        assertEquals(60, firstCandle.volume());

        for (int minute = 1; minute <= 150; minute++) {
            marketDataProcessor.processTick("AAPL", new BigDecimal("100.00"), 1,
                    firstMinute.plusSeconds(minute * 60L));
        }
        var retainedCandles = marketDataProcessor.getRecentCandles("AAPL");
        assertEquals(150, retainedCandles.size());
        assertEquals(firstMinute.plusSeconds(60).getEpochSecond(), retainedCandles.get(0).time());
        assertEquals(firstMinute.plusSeconds(150 * 60L).getEpochSecond(),
                retainedCandles.get(retainedCandles.size() - 1).time());
    }
}
