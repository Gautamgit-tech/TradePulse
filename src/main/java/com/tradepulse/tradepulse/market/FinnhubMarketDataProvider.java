package com.tradepulse.tradepulse.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepulse.tradepulse.MarketDataSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FinnhubMarketDataProvider implements MarketDataProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(FinnhubMarketDataProvider.class);
    private static final long INITIAL_RECONNECT_DELAY_SECONDS = 1;
    private static final long MAX_RECONNECT_DELAY_SECONDS = 30;

    private final MarketDataSimulator simulator;
    private final MarketDataProcessor marketDataProcessor;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Set<String> symbols = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient;
    private final URI endpoint;

    private ScheduledExecutorService reconnectExecutor;
    private ScheduledFuture<?> reconnectTask;
    private WebSocket webSocket;
    private long nextReconnectDelaySeconds = INITIAL_RECONNECT_DELAY_SECONDS;
    private volatile boolean started;
    private volatile boolean connected;

    public FinnhubMarketDataProvider(MarketDataSimulator simulator,
                                     MarketDataProcessor marketDataProcessor,
                                     ObjectMapper objectMapper,
                                     String apiKey,
                                     String configuredSymbols) {
        this.simulator = simulator;
        this.marketDataProcessor = marketDataProcessor;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        Arrays.stream(configuredSymbols.split(","))
                .map(String::trim)
                .filter(symbol -> !symbol.isEmpty())
                .map(symbol -> symbol.toUpperCase(Locale.ROOT))
                .forEach(symbols::add);
        if (this.symbols.isEmpty()) {
            throw new IllegalArgumentException("At least one Finnhub market data symbol must be configured.");
        }
        this.endpoint = URI.create("wss://ws.finnhub.io?token="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "tradepulse-finnhub-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        simulator.start();
        connect();
    }

    @Override
    public synchronized void stop() {
        started = false;
        connected = false;
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        WebSocket activeSocket = webSocket;
        webSocket = null;
        if (activeSocket != null) {
            activeSocket.sendClose(WebSocket.NORMAL_CLOSURE, "TradePulse stopping");
        }
        simulator.stop();
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
            reconnectExecutor = null;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getProviderName() {
        return connected ? "FINNHUB" : "SIMULATOR";
    }

    public void subscribeSymbol(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (symbols.add(normalizedSymbol) && connected) {
            WebSocket activeSocket = webSocket;
            if (activeSocket != null) {
                activeSocket.sendText(subscriptionMessage(normalizedSymbol), true)
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                LOGGER.warn("Could not subscribe to Finnhub symbol {}.", normalizedSymbol, failure);
                            }
                        });
            }
        }
    }

    void processMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (!"trade".equals(root.path("type").asText())) {
                return;
            }
            JsonNode trades = root.path("data");
            if (!trades.isArray()) {
                LOGGER.warn("Ignoring Finnhub trade message without a data array.");
                return;
            }
            for (JsonNode trade : trades) {
                String symbol = trade.path("s").asText("").toUpperCase(Locale.ROOT);
                JsonNode priceNode = trade.get("p");
                JsonNode timestampNode = trade.get("t");
                JsonNode volumeNode = trade.get("v");
                if (!symbols.contains(symbol) || priceNode == null || !priceNode.isNumber()
                        || timestampNode == null || !timestampNode.canConvertToLong()
                        || volumeNode == null || !volumeNode.canConvertToLong()) {
                    LOGGER.warn("Ignoring malformed or unconfigured Finnhub trade.");
                    continue;
                }
                BigDecimal price = priceNode.decimalValue();
                long volume = volumeNode.longValue();
                if (price.signum() <= 0 || volume < 0) {
                    LOGGER.warn("Ignoring Finnhub trade with invalid price or volume for {}.", symbol);
                    continue;
                }
                marketDataProcessor.processTick(symbol, price, volume,
                        Instant.ofEpochMilli(timestampNode.longValue()));
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not process Finnhub market data message.", exception);
        }
    }

    private void connect() {
        if (!started) {
            return;
        }
        httpClient.newWebSocketBuilder()
                .buildAsync(endpoint, new FinnhubListener())
                .whenComplete((socket, failure) -> {
                    if (failure != null) {
                        handleDisconnect(null, failure);
                    } else if (!started) {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "TradePulse stopping");
                    }
                });
    }

    private void handleDisconnect(WebSocket socket, Throwable failure) {
        synchronized (this) {
            if (!started || (socket != null && webSocket != socket)) {
                return;
            }
            if (failure != null) {
                LOGGER.warn("Finnhub market data connection lost; using simulator until reconnect.", failure);
            } else {
                LOGGER.warn("Finnhub market data connection closed; using simulator until reconnect.");
            }
            connected = false;
            webSocket = null;
            simulator.start();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!started || reconnectExecutor == null
                || (reconnectTask != null && !reconnectTask.isDone())) {
            return;
        }
        long delaySeconds = nextReconnectDelaySeconds;
        nextReconnectDelaySeconds = Math.min(nextReconnectDelaySeconds * 2, MAX_RECONNECT_DELAY_SECONDS);
        reconnectTask = reconnectExecutor.schedule(() -> {
            synchronized (FinnhubMarketDataProvider.this) {
                reconnectTask = null;
                if (!started) {
                    return;
                }
            }
            connect();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private final class FinnhubListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            synchronized (FinnhubMarketDataProvider.this) {
                if (!started) {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "TradePulse stopping");
                    return;
                }
                webSocket = socket;
                connected = true;
                nextReconnectDelaySeconds = INITIAL_RECONNECT_DELAY_SECONDS;
                if (reconnectTask != null) {
                    reconnectTask.cancel(false);
                    reconnectTask = null;
                }
                simulator.stop();
            }
            LOGGER.info("Connected to Finnhub market data for {}.", symbols);
            for (String symbol : symbols) {
                socket.sendText(subscriptionMessage(symbol), true)
                        .exceptionally(failure -> {
                            handleDisconnect(socket, failure);
                            return null;
                        });
            }
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    processMessage(textBuffer.toString());
                    textBuffer.setLength(0);
                }
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            handleDisconnect(socket, null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            handleDisconnect(socket, error);
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("Market symbol is required.");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9._-]+(?::[A-Z0-9._-]+)?")) {
            throw new IllegalArgumentException("Invalid Finnhub market symbol.");
        }
        return normalized;
    }

    private String subscriptionMessage(String symbol) {
        return "{\"type\":\"subscribe\",\"symbol\":\"" + symbol + "\"}";
    }
}
