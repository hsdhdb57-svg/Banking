import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public record BankCard(
        long id,
        long accountId,
        String cardNumber,
        BigDecimal balance,
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

