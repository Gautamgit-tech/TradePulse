package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.service.OrderMatchingService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
public class MarketDataSimulator {

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderMatchingService orderMatchingService;
    private final Map<String, BigDecimal> currentPrices = new LinkedHashMap<>();
    private final Map<String, Candle> currentCandles = new LinkedHashMap<>();
    private final Random random = new Random();
    private static final long CANDLE_DURATION_SECONDS = 5;

    public MarketDataSimulator(SimpMessagingTemplate messagingTemplate,
                               OrderMatchingService orderMatchingService) {
        this.messagingTemplate = messagingTemplate;
        this.orderMatchingService = orderMatchingService;
        currentPrices.put("AAPL", new BigDecimal("2475.00"));
        currentPrices.put("NVDA", new BigDecimal("1180.00"));
        currentPrices.put("MSFT", new BigDecimal("820.00"));
        currentPrices.put("RELIANCE", new BigDecimal("1460.00"));
        currentPrices.put("INFY", new BigDecimal("1580.00"));
    }

    @Scheduled(fixedRate = 500)
    public void sendTick() {
        Instant timestamp = Instant.now();
        long epochSecond = timestamp.getEpochSecond();
        long candleTime = epochSecond - epochSecond % CANDLE_DURATION_SECONDS;
        currentPrices.replaceAll((symbol, currentPrice) -> {
            double delta = (random.nextDouble() - 0.49) * currentPrice.doubleValue() * 0.001;
            BigDecimal nextPrice = currentPrice.add(BigDecimal.valueOf(delta))
                    .max(new BigDecimal("0.01"))
                    .setScale(2, RoundingMode.HALF_UP);
            long tickVolume = random.nextInt(451) + 50;
            Candle candle = currentCandles.get(symbol);
            if (candle == null || candle.time != candleTime) {
                candle = new Candle(candleTime, nextPrice, tickVolume);
                currentCandles.put(symbol, candle);
            } else {
                candle.update(nextPrice, tickVolume);
            }

            Object tick = Map.of(
                    "symbol", symbol,
                    "price", nextPrice,
                    "open", candle.open,
                    "high", candle.high,
                    "low", candle.low,
                    "close", candle.close,
                    "volume", candle.volume,
                    "tickVolume", tickVolume,
                    "barTime", candle.time,
                    "timestamp", timestamp.toString());
            messagingTemplate.convertAndSend("/topic/ticks/" + symbol, tick);
            Object depth = generateDepth(symbol, nextPrice, timestamp);
            messagingTemplate.convertAndSend("/topic/depth/" + symbol, depth);
            orderMatchingService.matchPendingOrders(symbol, nextPrice);
            return nextPrice;
        });
    }

    private Map<String, Object> generateDepth(String symbol, BigDecimal lastPrice, Instant timestamp) {
        BigDecimal step = lastPrice.multiply(new BigDecimal("0.00015"))
                .max(new BigDecimal("0.01"))
                .setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> bids = new ArrayList<>(5);
        List<Map<String, Object>> asks = new ArrayList<>(5);
        long totalBuyQuantity = 0;
        long totalSellQuantity = 0;

        for (int level = 1; level <= 5; level++) {
            long bidQuantity = random.nextInt(1151) + 50;
            long askQuantity = random.nextInt(1151) + 50;
            BigDecimal bidPrice = lastPrice.subtract(step.multiply(BigDecimal.valueOf(level)))
                    .max(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal askPrice = lastPrice.add(step.multiply(BigDecimal.valueOf(level)))
                    .setScale(2, RoundingMode.HALF_UP);
            bids.add(Map.of(
                    "price", bidPrice,
                    "quantity", bidQuantity,
                    "orders", random.nextInt(20) + 1));
            asks.add(Map.of(
                    "price", askPrice,
                    "quantity", askQuantity,
                    "orders", random.nextInt(20) + 1));
            totalBuyQuantity += bidQuantity;
            totalSellQuantity += askQuantity;
        }

        long totalQuantity = totalBuyQuantity + totalSellQuantity;
        int buyPercentage = (int) Math.round(totalBuyQuantity * 100.0 / totalQuantity);
        Map<String, Object> depth = new LinkedHashMap<>();
        depth.put("symbol", symbol);
        depth.put("bids", bids);
        depth.put("asks", asks);
        depth.put("totalBuyQuantity", totalBuyQuantity);
        depth.put("totalSellQuantity", totalSellQuantity);
        depth.put("buyPercentage", buyPercentage);
        depth.put("sellPercentage", 100 - buyPercentage);
        depth.put("timestamp", timestamp.toString());
        return depth;
    }

    private static final class Candle {
        private final long time;
        private final BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long volume;

        private Candle(long time, BigDecimal price, long volume) {
            this.time = time;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.volume = volume;
        }

        private void update(BigDecimal price, long tickVolume) {
            high = high.max(price);
            low = low.min(price);
            close = price;
            volume += tickVolume;
        }
    }
}