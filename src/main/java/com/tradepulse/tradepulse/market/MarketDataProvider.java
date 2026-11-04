package com.tradepulse.tradepulse.market;

public interface MarketDataProvider {

    void start();

    void stop();

    boolean isConnected();

    String getProviderName();
}
