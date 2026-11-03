package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.service.OrderMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
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
    private MarketDataSimulator simulator;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        orderMatchingService = mock(OrderMatchingService.class);
        simulator = new MarketDataSimulator(messagingTemplate, orderMatchingService);
    }

    @Test
    void publishesAndMatchesTicksForEveryWatchlistSymbol() {
        simulator.sendTick();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> tickCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(10)).convertAndSend(topicCaptor.capture(), tickCaptor.capture());
        assertEquals(Set.of(
                "/topic/ticks/AAPL",
                "/topic/ticks/NVDA",
                "/topic/ticks/MSFT",
                "/topic/ticks/RELIANCE",
                "/topic/ticks/INFY",
                "/topic/depth/AAPL",
                "/topic/depth/NVDA",
                "/topic/depth/MSFT",
                "/topic/depth/RELIANCE",
                "/topic/depth/INFY"), Set.copyOf(topicCaptor.getAllValues()));

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
                assertTrue(((Number) message.get("barTime")).longValue() % 5 == 0);
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
}
