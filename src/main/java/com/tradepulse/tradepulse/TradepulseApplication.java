package com.tradepulse.tradepulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradepulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradepulseApplication.class, args);
    }
}