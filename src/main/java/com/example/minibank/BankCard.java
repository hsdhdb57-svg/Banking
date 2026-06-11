package com.example.minibank;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record BankCard(
        long id,
        long accountId,
        String cardNumber,
        LocalDate expiresAt,
        String status,
        LocalDateTime createdAt
) {
    public String maskedNumber() {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }
}
