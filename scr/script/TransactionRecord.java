import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionRecord(
        long id,
        long accountId,
        String type,
        BigDecimal amount,
        Long relatedAccountId,
        Long transferId,
        String description,
        LocalDateTime createdAt
) {
}

