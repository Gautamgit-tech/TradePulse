package com.tradepulse.tradepulse.dto;

import java.math.BigDecimal;

public record UserProfileResponse(
        String id,
        String email,
        String fullName,
        BigDecimal availableCash) {
}
