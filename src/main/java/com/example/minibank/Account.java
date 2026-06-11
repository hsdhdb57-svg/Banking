package com.example.minibank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Account(
        long id,
        String owner,
        BigDecimal balance,
        String status,
        LocalDateTime createdAt
) {
}
