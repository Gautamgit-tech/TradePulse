package com.tradepulse.tradepulse.market;

import com.tradepulse.tradepulse.service.OrderMatchingService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

@Component
public class MarketDataProcessor {

    private static final long CANDLE_DURATION_SECONDS = 60;
    private static final int MAX_CANDLES_PER_SYMBOL = 150;

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderMatchingService orderMatchingService;
    private final Map<String, NavigableMap<Long, Candle>> candleHistory = new LinkedHashMap<>();
    private final Random random = new Random();

    public MarketDataProcessor(SimpMessagingTemplate messagingTemplate,
                               OrderMatchingService orderMatchingService) {
        this.messagingTemplate = messagingTemplate;
        this.orderMatchingService = orderMatchingService;
    }

    public synchronized void processTick(String symbol, BigDecimal price, long tickVolume, Instant timestamp) {
        if (symbol == null || symbol.isBlank() || price == null || price.signum() <= 0
                || tickVolume < 0 || timestamp == null) {
            throw new IllegalArgumentException("A market tick must include a symbol, positive price, volume, and timestamp.");
        }

        int priceScale = priceScaleFor(symbol);
        BigDecimal normalizedPrice = price.setScale(priceScale, RoundingMode.HALF_UP);
        long epochSecond = timestamp.getEpochSecond();
        long candleTime = epochSecond - epochSecond % CANDLE_DURATION_SECONDS;
        NavigableMap<Long, Candle> history =
                candleHistory.computeIfAbsent(symbol, ignored -> new TreeMap<>());
        Candle candle = history.get(candleTime);
        if (candle == null) {
            candle = new Candle(candleTime, normalizedPrice, tickVolume);
            history.put(candleTime, candle);
            while (history.size() > MAX_CANDLES_PER_SYMBOL) {
                history.pollFirstEntry();
            }
        } else {
            candle.update(normalizedPrice, tickVolume);
        }

        Map<String, Object> tick = Map.of(
                "symbol", symbol,
                "price", normalizedPrice,
                "open", candle.open,
                "high", candle.high,
                "low", candle.low,
                "close", candle.close,
                "volume", candle.volume,
                "tickVolume", tickVolume,
                "barTime", candle.time,
                "timestamp", timestamp.toString());
        messagingTemplate.convertAndSend("/topic/ticks/" + symbol, (Object) tick);
        messagingTemplate.convertAndSend("/topic/depth/" + symbol,
                (Object) generateDepth(symbol, normalizedPrice, timestamp));
        orderMatchingService.matchPendingOrders(symbol, normalizedPrice);
    }

    public synchronized List<MarketCandle> getRecentCandles(String symbol) {
        NavigableMap<Long, Candle> history = candleHistory.get(symbol);
        if (history == null) {
            return List.of();
        }
        return history.values().stream()
                .map(candle -> new MarketCandle(candle.time, candle.open, candle.high,
                        candle.low, candle.close, candle.volume))
                .toList();
    }

    private Map<String, Object> generateDepth(String symbol, BigDecimal lastPrice, Instant timestamp) {
        int priceScale = priceScaleFor(symbol);
        BigDecimal minimumTick = BigDecimal.ONE.movePointLeft(priceScale);
        BigDecimal step = lastPrice.multiply(new BigDecimal("0.00015"))
                .max(minimumTick)
                .setScale(priceScale, RoundingMode.HALF_UP);
        List<Map<String, Object>> bids = new ArrayList<>(5);
        List<Map<String, Object>> asks = new ArrayList<>(5);
        long totalBuyQuantity = 0;
        long totalSellQuantity = 0;

        for (int level = 1; level <= 5; level++) {
            long bidQuantity = random.nextInt(1151) + 50;
            long askQuantity = random.nextInt(1151) + 50;
            BigDecimal bidPrice = lastPrice.subtract(step.multiply(BigDecimal.valueOf(level)))
                    .max(minimumTick).setScale(priceScale, RoundingMode.HALF_UP);
            BigDecimal askPrice = lastPrice.add(step.multiply(BigDecimal.valueOf(level)))
                    .setScale(priceScale, RoundingMode.HALF_UP);
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

    private int priceScaleFor(String symbol) {
        if (symbol.startsWith("BINANCE:")) {
            return 8;
        }
        if (symbol.startsWith("OANDA:")) {
            return 6;
        }
        return 2;
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

    public record MarketCandle(long time, BigDecimal open, BigDecimal high,
                               BigDecimal low, BigDecimal close, long volume) {
    }
}
