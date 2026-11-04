package com.tradepulse.tradepulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepulse.tradepulse.market.FinnhubMarketDataProvider;
import com.tradepulse.tradepulse.market.MarketDataProcessor;
import com.tradepulse.tradepulse.market.MarketDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MarketDataConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataConfig.class);
    private static final String PLACEHOLDER_API_KEY = "YOUR_FINNHUB_KEY_HERE";

    @Bean
    MarketDataSimulator marketDataSimulator(MarketDataProcessor marketDataProcessor) {
        return new MarketDataSimulator(marketDataProcessor);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @Primary
    MarketDataProvider marketDataProvider(
            MarketDataSimulator simulator,
            MarketDataProcessor marketDataProcessor,
            @Value("${tradepulse.market-data.provider:simulator}") String configuredProvider,
            @Value("${tradepulse.market-data.finnhub-api-key:}") String apiKey,
            @Value("${tradepulse.market-data.symbols:AAPL,NVDA,MSFT,TSLA,AMZN,GOOGL,BINANCE:BTCUSDT,BINANCE:ETHUSDT,BINANCE:SOLUSDT}") String symbols) {
        if ("finnhub".equalsIgnoreCase(configuredProvider) && hasFinnhubApiKey(apiKey)) {
            LOGGER.info("Starting Finnhub market data provider with simulator fallback.");
            return new FinnhubMarketDataProvider(
                    simulator, marketDataProcessor, new ObjectMapper(), apiKey, symbols);
        }
        if ("finnhub".equalsIgnoreCase(configuredProvider)) {
            LOGGER.warn("Finnhub was requested without an API key; starting the market data simulator.");
        } else {
            LOGGER.info("Starting simulator market data provider.");
        }
        return simulator;
    }

    private boolean hasFinnhubApiKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_API_KEY.equals(apiKey.trim());
    }
}
