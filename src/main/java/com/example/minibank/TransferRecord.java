package com.example.minibank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferRecord(
        long id,
        long fromAccountId,
        long toAccountId,
        BigDecimal amount,
        String status,
        LocalDateTime createdAt
) {
}
