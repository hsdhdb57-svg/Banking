import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public class Database {
    private static final String DEFAULT_URL = "jdbc:h2:./data/minibank;MODE=PostgreSQL;DATABASE_TO_UPPER=false";

    private final String url;

    public Database() {
        this(DEFAULT_URL);
    }

    public Database(String url) {
        this.url = url;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    public void initialize() {
        try (Connection connection = connect()) {
            runSchema(connection);
            runMigrations(connection);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize database", e);
        }
    }

    private void runSchema(Connection connection) throws Exception {
        String sql;
        try (InputStream input = Database.class.getResourceAsStream("/schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("schema.sql was not found in resources");
            }
            sql = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
        }

        for (String statementSql : sql.split(";")) {
            String trimmed = statementSql.trim();
            if (!trimmed.isEmpty()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private void runMigrations(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS username VARCHAR(80)");
        execute(connection, "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS password VARCHAR(120)");
        execute(connection, "UPDATE accounts SET username = 'user' || id WHERE username IS NULL OR username = ''");
        execute(connection, "UPDATE accounts SET password = '1234' WHERE password IS NULL OR password = ''");
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_username ON accounts(username)");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}

