import java.time.LocalDateTime;

public record ChangeHistoryEntry(
        long id,
        String entityType,
        long entityId,
        String action,
        String details,
        LocalDateTime createdAt
) {
}

