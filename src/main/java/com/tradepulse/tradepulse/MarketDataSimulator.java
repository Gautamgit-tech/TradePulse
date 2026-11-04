package com.tradepulse.tradepulse;

import com.tradepulse.tradepulse.market.MarketDataProcessor;
import com.tradepulse.tradepulse.market.MarketDataProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MarketDataSimulator implements MarketDataProvider {

    private final MarketDataProcessor marketDataProcessor;
    private final Map<String, BigDecimal> currentPrices = new LinkedHashMap<>();
    private final Random random = new Random();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;
    private volatile boolean running;

    public MarketDataSimulator(MarketDataProcessor marketDataProcessor) {
        this.marketDataProcessor = marketDataProcessor;
        currentPrices.put("AAPL", new BigDecimal("2475.00"));
        currentPrices.put("NVDA", new BigDecimal("1180.00"));
        currentPrices.put("MSFT", new BigDecimal("820.00"));
        currentPrices.put("TSLA", new BigDecimal("245.00"));
        currentPrices.put("AMZN", new BigDecimal("185.00"));
        currentPrices.put("GOOGL", new BigDecimal("175.00"));
        currentPrices.put("BINANCE:BTCUSDT", new BigDecimal("60000.00000000"));
        currentPrices.put("BINANCE:ETHUSDT", new BigDecimal("3000.00000000"));
        currentPrices.put("BINANCE:SOLUSDT", new BigDecimal("150.00000000"));
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "tradepulse-market-simulator");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        tickTask = scheduler.scheduleAtFixedRate(this::sendTick, 0, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isConnected() {
        return running;
    }

    @Override
    public String getProviderName() {
        return "SIMULATOR";
    }

    public void sendTick() {
        Instant timestamp = Instant.now();
        currentPrices.replaceAll((symbol, currentPrice) -> {
            double delta = (random.nextDouble() - 0.49) * currentPrice.doubleValue() * 0.001;
            BigDecimal nextPrice = currentPrice.add(BigDecimal.valueOf(delta))
                    .max(BigDecimal.ONE.movePointLeft(priceScaleFor(symbol)))
                    .setScale(priceScaleFor(symbol), RoundingMode.HALF_UP);
            long tickVolume = random.nextInt(451) + 50;
            marketDataProcessor.processTick(symbol, nextPrice, tickVolume, timestamp);
            return nextPrice;
        });
    }

    private int priceScaleFor(String symbol) {
        return symbol.startsWith("BINANCE:") ? 8 : 2;
    }
}
