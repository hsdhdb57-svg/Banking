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
        String generatedUsername = "user" + System.currentTimeMillis();
        return createAccount(owner, generatedUsername, "1234", initialBalance);
    }

    public Account createAccount(String owner, String username, String password, BigDecimal initialBalance) throws SQLException {
        String cleanOwner = requireText(owner, "Owner");
        String cleanUsername = requireText(username, "Username").toLowerCase();
        String cleanPassword = requireText(password, "Password");
        BigDecimal amount = normalizeNonNegative(initialBalance, "Initial balance");

        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                long accountId = insertAccount(connection, cleanOwner, cleanUsername, cleanPassword, BigDecimal.ZERO);
                BankCard card = insertCard(connection, accountId, amount);
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    insertTransaction(connection, accountId, "INITIAL_DEPOSIT", amount, null, null,
                            "Initial balance on card " + card.cardNumber());
                }
                insertHistory(connection, "ACCOUNT", accountId, "CREATED",
                        "Created account for " + cleanOwner);
                insertHistory(connection, "CARD", card.id(), "ISSUED",
                        "Issued card " + card.maskedNumber() + " for account " + accountId + " with balance " + amount);
                connection.commit();
                return findAccount(connection, accountId, false);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Account authenticate(String username, String password) throws SQLException {
        String cleanUsername = requireText(username, "Username").toLowerCase();
        String cleanPassword = requireText(password, "Password");
        String sql = """
                SELECT a.id, a.owner, a.username,
                       COALESCE((SELECT SUM(c.balance) FROM bank_cards c WHERE c.account_id = a.id), a.balance) AS balance,
                       a.status, a.created_at
                FROM accounts a
                WHERE a.username = ? AND a.password = ?
                """;

        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cleanUsername);
            statement.setString(2, cleanPassword);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Wrong username or password");
                }
                return mapAccount(resultSet);
            }
        }
    }

    public void deposit(long accountId, BigDecimal amount) throws SQLException {
        BankCard card = primaryCard(accountId);
        depositToCard(card.id(), amount);
    }

    public void depositToCard(long cardId, BigDecimal amount) throws SQLException {
        BigDecimal money = normalizePositive(amount);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                BankCard card = findCard(connection, cardId, true);
                findAccount(connection, card.accountId(), true);
                BigDecimal newBalance = card.balance().add(money);
                updateCardBalance(connection, card.id(), newBalance);
                updateAccountBalance(connection, card.accountId());
                insertTransaction(connection, card.accountId(), "DEPOSIT", money, null, null,
                        "Cash deposit to card " + card.cardNumber());
                insertHistory(connection, "CARD", card.id(), "DEPOSIT",
                        "Deposited " + money + "; new balance " + newBalance);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public void withdraw(long accountId, BigDecimal amount) throws SQLException {
        BankCard card = primaryCard(accountId);
        withdrawFromCard(card.id(), amount);
    }

    public void withdrawFromCard(long cardId, BigDecimal amount) throws SQLException {
        BigDecimal money = normalizePositive(amount);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                BankCard card = findCard(connection, cardId, true);
                findAccount(connection, card.accountId(), true);
                BigDecimal newBalance = card.balance().subtract(money);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Недостаточно средств на балансе");
                }
                updateCardBalance(connection, card.id(), newBalance);
                updateAccountBalance(connection, card.accountId());
                insertTransaction(connection, card.accountId(), "WITHDRAW", money, null, null,
                        "Cash withdrawal from card " + card.cardNumber());
                insertHistory(connection, "CARD", card.id(), "WITHDRAW",
                        "Withdrew " + money + "; new balance " + newBalance);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public long transfer(long fromAccountId, long toAccountId, BigDecimal amount) throws SQLException {
        BankCard fromCard = primaryCard(fromAccountId);
        BankCard toCard = primaryCard(toAccountId);
        return transferBetweenCards(fromCard.id(), toCard.cardNumber(), amount);
    }

    public long transferToCard(long fromCardId, String toCardNumber, BigDecimal amount) throws SQLException {
        return transferBetweenCards(fromCardId, toCardNumber, amount);
    }

    public long transferBetweenCards(long fromCardId, String toCardNumber, BigDecimal amount) throws SQLException {
        String cleanCardNumber = normalizeCardNumber(toCardNumber);
        BigDecimal money = normalizePositive(amount);
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                BankCard targetCard = getCardByNumber(connection, cleanCardNumber, false);
                if (fromCardId == targetCard.id()) {
                    throw new IllegalArgumentException("Нельзя переводить деньги на эту же карту");
                }

                long firstLockId = Math.min(fromCardId, targetCard.id());
                long secondLockId = Math.max(fromCardId, targetCard.id());
                BankCard firstLocked = findCard(connection, firstLockId, true);
                BankCard secondLocked = findCard(connection, secondLockId, true);
                BankCard from = firstLocked.id() == fromCardId ? firstLocked : secondLocked;
                BankCard to = firstLocked.id() == targetCard.id() ? firstLocked : secondLocked;
                findAccount(connection, from.accountId(), true);
                findAccount(connection, to.accountId(), true);

                BigDecimal fromBalance = from.balance().subtract(money);
                BigDecimal toBalance = to.balance().add(money);

                if (fromBalance.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Недостаточно средств на балансе");
                }

                updateCardBalance(connection, from.id(), fromBalance);
                updateCardBalance(connection, to.id(), toBalance);
                updateAccountBalance(connection, from.accountId());
                updateAccountBalance(connection, to.accountId());
                long transferId = insertTransfer(connection, from.accountId(), to.accountId(), from.id(), to.id(), money, "COMPLETED");
                insertTransaction(connection, from.accountId(), "TRANSFER_OUT", money, to.accountId(), transferId,
                        "Transfer to card " + to.cardNumber());
                insertTransaction(connection, to.accountId(), "TRANSFER_IN", money, from.accountId(), transferId,
                        "Transfer from card " + from.cardNumber());
                insertHistory(connection, "TRANSFER", transferId, "COMPLETED",
                        "Moved " + money + " from card " + from.cardNumber() + " to card " + to.cardNumber());
                insertHistory(connection, "CARD", from.id(), "BALANCE_CHANGED",
                        "Transfer " + transferId + "; new balance " + fromBalance);
                insertHistory(connection, "CARD", to.id(), "BALANCE_CHANGED",
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
                BankCard card = insertCard(connection, accountId, BigDecimal.ZERO);
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
                SELECT a.id, a.owner, a.username,
                       COALESCE((SELECT SUM(c.balance) FROM bank_cards c WHERE c.account_id = a.id), a.balance) AS balance,
                       a.status, a.created_at
                FROM accounts a
                ORDER BY a.id
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

    public BankCard getCardByNumber(String cardNumber) throws SQLException {
        try (Connection connection = database.connect()) {
            return getCardByNumber(connection, cardNumber, false);
        }
    }

    private BankCard getCardByNumber(Connection connection, String cardNumber, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT id, account_id, card_number, balance, expires_at, status, created_at
                FROM bank_cards
                WHERE card_number = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizeCardNumber(cardNumber));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Card was not found");
                }
                return mapCard(resultSet);
            }
        }
    }

    public List<BankCard> listCards(long accountId) throws SQLException {
        String sql = """
                SELECT id, account_id, card_number, balance, expires_at, status, created_at
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

    private BankCard insertCard(Connection connection, long accountId, BigDecimal balance) throws SQLException {
        String sql = """
                INSERT INTO bank_cards(account_id, card_number, balance, expires_at)
                VALUES (?, ?, ?, ?)
                """;
        SQLException lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, accountId);
                statement.setString(2, generateCardNumber());
                statement.setBigDecimal(3, balance);
                statement.setDate(4, java.sql.Date.valueOf(LocalDate.now().plusYears(4)));
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

    private long insertAccount(Connection connection, String owner, String username, String password, BigDecimal balance) throws SQLException {
        String sql = "INSERT INTO accounts(owner, username, password, balance) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, owner);
            statement.setString(2, username);
            statement.setString(3, password);
            statement.setBigDecimal(4, balance);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private long insertTransfer(Connection connection, long fromAccountId, long toAccountId,
                                long fromCardId, long toCardId, BigDecimal amount, String status) throws SQLException {
        String sql = """
                INSERT INTO transfers(from_account_id, to_account_id, from_card_id, to_card_id, amount, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, fromAccountId);
            statement.setLong(2, toAccountId);
            statement.setLong(3, fromCardId);
            statement.setLong(4, toCardId);
            statement.setBigDecimal(5, amount);
            statement.setString(6, status);
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
                SELECT a.id, a.owner, a.username,
                       COALESCE((SELECT SUM(c.balance) FROM bank_cards c WHERE c.account_id = a.id), a.balance) AS balance,
                       a.status, a.created_at
                FROM accounts a
                WHERE a.id = ?
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
        return findCard(connection, cardId, false);
    }

    private BankCard findCard(Connection connection, long cardId, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT id, account_id, card_number, balance, expires_at, status, created_at
                FROM bank_cards
                WHERE id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cardId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Card " + cardId + " was not found");
                }
                BankCard card = mapCard(resultSet);
                if (!"ACTIVE".equals(card.status())) {
                    throw new IllegalArgumentException("Card " + cardId + " is not active");
                }
                return card;
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

    private void updateCardBalance(Connection connection, long cardId, BigDecimal balance) throws SQLException {
        String sql = "UPDATE bank_cards SET balance = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, balance);
            statement.setLong(2, cardId);
            statement.executeUpdate();
        }
    }

    private void updateAccountBalance(Connection connection, long accountId) throws SQLException {
        String sql = """
                UPDATE accounts
                SET balance = COALESCE((SELECT SUM(balance) FROM bank_cards WHERE account_id = ?), 0)
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            statement.setLong(2, accountId);
            statement.executeUpdate();
        }
    }

    private BankCard primaryCard(long accountId) throws SQLException {
        String sql = """
                SELECT id, account_id, card_number, balance, expires_at, status, created_at
                FROM bank_cards
                WHERE account_id = ?
                ORDER BY id
                LIMIT 1
                """;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("У аккаунта нет карт");
                }
                return mapCard(resultSet);
            }
        }
    }

    private Account mapAccount(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new Account(
                resultSet.getLong("id"),
                resultSet.getString("owner"),
                resultSet.getString("username"),
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
                resultSet.getBigDecimal("balance"),
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

    private static String normalizeCardNumber(String value) {
        String cleanValue = requireText(value, "Card number").replace(" ", "");
        if (!cleanValue.matches("\\d{16}")) {
            throw new IllegalArgumentException("Card number must contain 16 digits");
        }
        return cleanValue;
    }

}

