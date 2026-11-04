package com.tradepulse.tradepulse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = MOCK)
class SecurityIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void protectedTradingApisRequireBearerAuthentication() throws Exception {
        mockMvc.perform(get("/api/wallet/any-user")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/any-user")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/holdings/any-user")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/future-endpoint")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedTradingApisRejectMalformedBearerTokens() throws Exception {
        mockMvc.perform(get("/api/wallet/any-user")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tradingTerminalRemainsPublic() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void marketDataProviderStatusIsPublic() throws Exception {
        mockMvc.perform(get("/api/market-data/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("SIMULATOR"))
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    void historicalMarketCandlesArePublic() throws Exception {
        mockMvc.perform(get("/api/market/candles")
                        .param("symbol", "BINANCE:BTCUSDT")
                        .param("interval", "1m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void expandedMarketSymbolCatalogIsPublic() throws Exception {
        mockMvc.perform(get("/api/market/symbols"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9));
    }
}
