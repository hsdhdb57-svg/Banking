import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class BankService {
    private final Database database;

    public BankService(Database database) {
        this.database = database;
    }

    public Account createAccount(String owner, BigDecimal initialBalance) throws SQLException {
        String cleanOwner = requireText(owner, "Owner");
        BigDecimal amount = normalizeNonNegative(initialBalance, "Initial balance");

        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                long accountId = insertAccount(connection, cleanOwner, amount);
                BankCard card = insertCard(connection, accountId);
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    insertTransaction(connection, accountId, "INITIAL_DEPOSIT", amount, null, null,
                            "Initial account balance");
                }
                insertHistory(connection, "ACCOUNT", accountId, "CREATED",
                        "Created account for " + cleanOwner + " with balance " + amount);
                insertHistory(connection, "CARD", card.id(), "ISSUED",
                        "Issued card " + card.maskedNumber() + " for account " + accountId);
                connection.commit();
                return findAccount(connection, accountId, false);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public void deposit(long accountId, BigDecimal amount) throws SQLException {
        BigDecimal money = normalizePositive(amount);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                Account account = findAccount(connection, accountId, true);
                updateBalance(connection, accountId, account.balance().add(money));
                insertTransaction(connection, accountId, "DEPOSIT", money, null, null, "Cash deposit");
                insertHistory(connection, "ACCOUNT", accountId, "DEPOSIT",
                        "Deposited " + money + "; new balance " + account.balance().add(money));
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public void withdraw(long accountId, BigDecimal amount) throws SQLException {
        BigDecimal money = normalizePositive(amount);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                Account account = findAccount(connection, accountId, true);
                BigDecimal newBalance = account.balance().subtract(money);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Not enough money on account " + accountId);
                }
                updateBalance(connection, accountId, newBalance);
                insertTransaction(connection, accountId, "WITHDRAW", money, null, null, "Cash withdrawal");
                insertHistory(connection, "ACCOUNT", accountId, "WITHDRAW",
                        "Withdrew " + money + "; new balance " + newBalance);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public long transfer(long fromAccountId, long toAccountId, BigDecimal amount) throws SQLException {
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("Cannot transfer money to the same account");
        }

        BigDecimal money = normalizePositive(amount);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                long firstLockId = Math.min(fromAccountId, toAccountId);
                long secondLockId = Math.max(fromAccountId, toAccountId);
                Account firstLocked = findAccount(connection, firstLockId, true);
                Account secondLocked = findAccount(connection, secondLockId, true);
                Account from = firstLocked.id() == fromAccountId ? firstLocked : secondLocked;
                Account to = firstLocked.id() == toAccountId ? firstLocked : secondLocked;
                BigDecimal fromBalance = from.balance().subtract(money);
                BigDecimal toBalance = to.balance().add(money);

                if (fromBalance.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Not enough money on source account " + fromAccountId);
                }

                updateBalance(connection, fromAccountId, fromBalance);
                updateBalance(connection, toAccountId, toBalance);
                long transferId = insertTransfer(connection, fromAccountId, toAccountId, money, "COMPLETED");
                insertTransaction(connection, fromAccountId, "TRANSFER_OUT", money, toAccountId, transferId,
                        "Transfer to account " + toAccountId);
                insertTransaction(connection, toAccountId, "TRANSFER_IN", money, fromAccountId, transferId,
                        "Transfer from account " + fromAccountId);
                insertHistory(connection, "TRANSFER", transferId, "COMPLETED",
                        "Moved " + money + " from account " + fromAccountId + " to account " + toAccountId);
                insertHistory(connection, "ACCOUNT", fromAccountId, "BALANCE_CHANGED",
                        "Transfer " + transferId + "; new balance " + fromBalance);
                insertHistory(connection, "ACCOUNT", toAccountId, "BALANCE_CHANGED",
                        "Transfer " + transferId + "; new balance " + toBalance);
                connection.commit();
                return transferId;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Account getAccount(long accountId) throws SQLException {
        try (Connection connection = database.connect()) {
            return findAccount(connection, accountId, false);
        }
    }

    public BankCard createCard(long accountId) throws SQLException {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                findAccount(connection, accountId, true);
                BankCard card = insertCard(connection, accountId);
                insertHistory(connection, "CARD", card.id(), "ISSUED",
                        "Issued card " + card.maskedNumber() + " for account " + accountId);
                connection.commit();
                return card;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public List<Account> listAccounts() throws SQLException {
        String sql = """
                SELECT id, owner, balance, status, created_at
                FROM accounts
                ORDER BY id
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Account> accounts = new ArrayList<>();
            while (resultSet.next()) {
                accounts.add(mapAccount(resultSet));
            }
            return accounts;
        }
    }

    public List<BankCard> listCards(long accountId) throws SQLException {
        String sql = """
                SELECT id, account_id, card_number, expires_at, status, created_at
                FROM bank_cards
                WHERE account_id = ?
                ORDER BY id
                """;

        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<BankCard> cards = new ArrayList<>();
                while (resultSet.next()) {
                    cards.add(mapCard(resultSet));
                }
                return cards;
            }
        }
    }

    public List<TransferRecord> listTransfers() throws SQLException {
        String sql = """
                SELECT id, from_account_id, to_account_id, amount, status, created_at
                FROM transfers
                ORDER BY created_at DESC, id DESC
                """;

        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<TransferRecord> transfers = new ArrayList<>();
            while (resultSet.next()) {
                transfers.add(new TransferRecord(
                        resultSet.getLong("id"),
                        resultSet.getLong("from_account_id"),
                        resultSet.getLong("to_account_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()
                ));
            }
            return transfers;
        }
    }

    public List<TransactionRecord> listTransactions(Long accountId) throws SQLException {
        String sql = accountId == null
                ? """
                SELECT id, account_id, type, amount, related_account_id, transfer_id, description, created_at
                FROM transactions
                ORDER BY created_at DESC, id DESC
                """
                : """
                SELECT id, account_id, type, amount, related_account_id, transfer_id, description, created_at
                FROM transactions
                WHERE account_id = ?
                ORDER BY created_at DESC, id DESC
                """;

        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (accountId != null) {
                statement.setLong(1, accountId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TransactionRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new TransactionRecord(
                            resultSet.getLong("id"),
                            resultSet.getLong("account_id"),
                            resultSet.getString("type"),
                            resultSet.getBigDecimal("amount"),
                            nullableLong(resultSet, "related_account_id"),
                            nullableLong(resultSet, "transfer_id"),
                            resultSet.getString("description"),
                            resultSet.getTimestamp("created_at").toLocalDateTime()
                    ));
                }
                return records;
            }
        }
    }

    private BankCard insertCard(Connection connection, long accountId) throws SQLException {
        String sql = """
                INSERT INTO bank_cards(account_id, card_number, expires_at)
                VALUES (?, ?, ?)
                """;
        SQLException lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, accountId);
                statement.setString(2, generateCardNumber());
                statement.setDate(3, java.sql.Date.valueOf(LocalDate.now().plusYears(4)));
                statement.executeUpdate();
                long cardId = generatedId(statement);
                return findCard(connection, cardId);
            } catch (SQLException e) {
                lastError = e;
            }
        }
        throw lastError == null ? new SQLException("Cannot issue card") : lastError;
    }

    public List<ChangeHistoryEntry> listChangeHistory() throws SQLException {
        String sql = """
                SELECT id, entity_type, entity_id, action, details, created_at
                FROM change_history
                ORDER BY created_at DESC, id DESC
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<ChangeHistoryEntry> history = new ArrayList<>();
            while (resultSet.next()) {
                history.add(new ChangeHistoryEntry(
                        resultSet.getLong("id"),
                        resultSet.getString("entity_type"),
                        resultSet.getLong("entity_id"),
                        resultSet.getString("action"),
                        resultSet.getString("details"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()
                ));
            }
            return history;
        }
    }

    private long insertAccount(Connection connection, String owner, BigDecimal balance) throws SQLException {
        String sql = "INSERT INTO accounts(owner, balance) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, owner);
            statement.setBigDecimal(2, balance);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private long insertTransfer(Connection connection, long fromAccountId, long toAccountId,
                                BigDecimal amount, String status) throws SQLException {
        String sql = """
                INSERT INTO transfers(from_account_id, to_account_id, amount, status)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, fromAccountId);
            statement.setLong(2, toAccountId);
            statement.setBigDecimal(3, amount);
            statement.setString(4, status);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private void insertTransaction(Connection connection, long accountId, String type, BigDecimal amount,
                                   Long relatedAccountId, Long transferId, String description) throws SQLException {
        String sql = """
                INSERT INTO transactions(account_id, type, amount, related_account_id, transfer_id, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            statement.setString(2, type);
            statement.setBigDecimal(3, amount);
            setNullableLong(statement, 4, relatedAccountId);
            setNullableLong(statement, 5, transferId);
            statement.setString(6, description);
            statement.executeUpdate();
        }
    }

    private void insertHistory(Connection connection, String entityType, long entityId,
                               String action, String details) throws SQLException {
        String sql = """
                INSERT INTO change_history(entity_type, entity_id, action, details)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setLong(2, entityId);
            statement.setString(3, action);
            statement.setString(4, details);
            statement.executeUpdate();
        }
    }

    private Account findAccount(Connection connection, long accountId, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT id, owner, balance, status, created_at
                FROM accounts
                WHERE id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Account " + accountId + " was not found");
                }
                Account account = mapAccount(resultSet);
                if (!"ACTIVE".equals(account.status())) {
                    throw new IllegalArgumentException("Account " + accountId + " is not active");
                }
                return account;
            }
        }
    }

    private BankCard findCard(Connection connection, long cardId) throws SQLException {
        String sql = """
                SELECT id, account_id, card_number, expires_at, status, created_at
                FROM bank_cards
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cardId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Card " + cardId + " was not found");
                }
                return mapCard(resultSet);
            }
        }
    }

    private void updateBalance(Connection connection, long accountId, BigDecimal balance) throws SQLException {
        String sql = "UPDATE accounts SET balance = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, balance);
            statement.setLong(2, accountId);
            statement.executeUpdate();
        }
    }

    private Account mapAccount(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new Account(
                resultSet.getLong("id"),
                resultSet.getString("owner"),
                resultSet.getBigDecimal("balance"),
                resultSet.getString("status"),
                createdAt.toLocalDateTime()
        );
    }

    private BankCard mapCard(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new BankCard(
                resultSet.getLong("id"),
                resultSet.getLong("account_id"),
                resultSet.getString("card_number"),
                resultSet.getDate("expires_at").toLocalDate(),
                resultSet.getString("status"),
                createdAt.toLocalDateTime()
        );
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("Database did not return generated id");
            }
            return keys.getLong(1);
        }
    }

    private static BigDecimal normalizePositive(BigDecimal amount) {
        BigDecimal normalized = normalizeNonNegative(amount, "Amount");
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        return normalized;
    }

    private static BigDecimal normalizeNonNegative(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String generateCardNumber() {
        StringBuilder number = new StringBuilder("4000");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) {
            number.append(random.nextInt(10));
        }
        return number.toString();
    }
}

