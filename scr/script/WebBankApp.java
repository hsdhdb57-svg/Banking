import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebBankApp {
    private static final int PORT = 8080;

    private final Database database;
    private final BankService bankService;

    public WebBankApp(Database database, BankService bankService) {
        this.database = database;
        this.bankService = bankService;
    }

    public static void main(String[] args) throws IOException {
        Database database = new Database();
        database.initialize();
        BankService bankService = new BankService(database);
        WebBankApp app = new WebBankApp(database, bankService);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", app::handle);
        server.start();

        System.out.println("Banking site is running: http://127.0.0.1:" + PORT + "/");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(exchange, mainPage(query(exchange)));
            } else if ("GET".equals(method) && "/style.css".equals(path)) {
                sendCss(exchange);
            } else if ("POST".equals(method) && "/login".equals(path)) {
                login(exchange);
            } else if ("POST".equals(method) && "/create-account".equals(path)) {
                createAccount(exchange);
            } else if ("GET".equals(method) && "/account".equals(path)) {
                sendHtml(exchange, accountPage(query(exchange)));
            } else if ("POST".equals(method) && "/account/deposit".equals(path)) {
                deposit(exchange);
            } else if ("POST".equals(method) && "/account/withdraw".equals(path)) {
                withdraw(exchange);
            } else if ("POST".equals(method) && "/account/transfer".equals(path)) {
                transfer(exchange);
            } else if ("POST".equals(method) && "/account/card".equals(path)) {
                issueCard(exchange);
            } else if ("GET".equals(method) && "/database".equals(path)) {
                sendHtml(exchange, databasePage(query(exchange)));
            } else if ("POST".equals(method) && "/database/delete".equals(path)) {
                deleteDatabaseRecord(exchange);
            } else {
                send(exchange, 404, "Page not found", "text/plain; charset=UTF-8");
            }
        } catch (Exception e) {
            sendHtml(exchange, page("Error", template("error.html", Map.of(
                    "error", escape(e.getMessage())
            ))));
        }
    }

    private String mainPage(Map<String, String> query) throws SQLException {
        return page("Main", template("main.html", Map.of(
                "message", queryMessage(query),
                "accountsTable", accountsSmallTable()
        )));
    }

    private String accountPage(Map<String, String> query) throws SQLException {
        String accountIdValue = query.get("id");
        if (accountIdValue == null || accountIdValue.isBlank()) {
            return page("Account", template("account-login.html", Map.of(
                    "message", queryMessage(query)
            )));
        }

        long accountId = Long.parseLong(accountIdValue);
        Account account = bankService.getAccount(accountId);
        List<BankCard> cards = bankService.listCards(accountId);
        List<TransactionRecord> transactions = bankService.listTransactions(accountId);

        return page("Account", template("account.html", Map.of(
                "accountId", String.valueOf(account.id()),
                "owner", escape(account.owner()),
                "username", escape(account.username()),
                "balance", escape(account.balance().toString()),
                "message", queryMessage(query),
                "cards", cardsHtml(cards, account.id()),
                "transactions", transactionsTable(transactions)
        )));
    }

    private String databasePage(Map<String, String> query) throws SQLException {
        String tables = tableSection("accounts", "accounts",
                "SELECT id, owner, username, password, balance, status, created_at FROM accounts ORDER BY id")
                + tableSection("bank_cards", "bank_cards",
                "SELECT id, account_id, card_number, expires_at, status, created_at FROM bank_cards ORDER BY id")
                + tableSection("transfers", "transfers",
                "SELECT id, from_account_id, to_account_id, amount, status, created_at FROM transfers ORDER BY id")
                + tableSection("transactions", "transactions",
                "SELECT id, account_id, type, amount, related_account_id, transfer_id, description, created_at FROM transactions ORDER BY id")
                + tableSection("change_history", "change_history",
                "SELECT id, entity_type, entity_id, action, details, created_at FROM change_history ORDER BY id");

        return page("Database", template("database.html", Map.of(
                "message", queryMessage(query),
                "tables", tables
        )));
    }

    private void createAccount(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        Account account = bankService.createAccount(
                form.get("owner"),
                form.get("username"),
                form.get("password"),
                money(form.get("balance"))
        );
        redirect(exchange, "/account?id=" + account.id() + "&message=" + url("Счет создан"));
    }

    private void login(HttpExchange exchange) throws Exception {
        try {
            Map<String, String> form = form(exchange);
            Account account = bankService.authenticate(form.get("username"), form.get("password"));
            redirect(exchange, "/account?id=" + account.id());
        } catch (IllegalArgumentException e) {
            redirect(exchange, "/account?message=" + url("Неверный логин или пароль"));
        }
    }

    private void deposit(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.deposit(accountId, money(form.get("amount")));
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("Счет пополнен"));
    }

    private void withdraw(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.withdraw(accountId, money(form.get("amount")));
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("Деньги сняты"));
    }

    private void transfer(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long fromId = number(form.get("fromId"));
        bankService.transferToCard(fromId, form.get("toCard"), money(form.get("amount")));
        redirect(exchange, "/account?id=" + fromId + "&message=" + url("Перевод выполнен"));
    }

    private void issueCard(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.createCard(accountId);
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("Карта выпущена"));
    }

    private void deleteDatabaseRecord(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        String table = form.get("table");
        long id = number(form.get("id"));

        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                switch (table) {
                    case "accounts" -> deleteAccount(connection, id);
                    case "bank_cards" -> deleteBankCard(connection, id);
                    case "transfers" -> deleteTransfer(connection, id);
                    case "transactions" -> execute(connection, "DELETE FROM transactions WHERE id = ?", id);
                    case "change_history" -> execute(connection, "DELETE FROM change_history WHERE id = ?", id);
                    default -> throw new IllegalArgumentException("Unsupported table");
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }

        redirect(exchange, "/database?message=" + url("Запись удалена"));
    }

    private String accountsSmallTable() throws SQLException {
        List<Account> accounts = bankService.listAccounts();
        if (accounts.isEmpty()) {
            return "<p class=\"muted\">Пока нет счетов.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr><th>ID</th><th>Пользователь</th><th>Логин</th><th>Баланс</th><th></th></tr></thead><tbody>");
        for (Account account : accounts) {
            html.append("<tr>")
                    .append(td(String.valueOf(account.id())))
                    .append(td(account.owner()))
                    .append(td(account.username()))
                    .append(td(account.balance().toString()))
                    .append("<td><a class=\"link\" href=\"/account\">Войти</a></td>")
                    .append("</tr>");
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    private String cardsHtml(List<BankCard> cards, long accountId) {
        StringBuilder html = new StringBuilder("<section class=\"card-carousel\"><button class=\"card-nav\" type=\"button\" onclick=\"scrollCards(-1)\" aria-label=\"Previous card\">&lt;</button><div class=\"cards-list\" id=\"cardsList\">");
        boolean first = true;
        for (BankCard card : cards) {
            html.append("<article class=\"bank-card")
                    .append(first ? " active" : "")
                    .append("\">")
                    .append("<span>").append(escape(card.status())).append("</span>")
                    .append("<button class=\"card-number\" type=\"button\" data-full=\"")
                    .append(escape(card.cardNumber()))
                    .append("\" data-mask=\"")
                    .append(escape(card.maskedNumber()))
                    .append("\" onclick=\"toggleCardNumber(this)\">")
                    .append(escape(card.maskedNumber()))
                    .append("</button>")
                    .append("<small>до ").append(escape(card.expiresAt().toString()))
                    .append("</small>")
                    .append("</article>");
            first = false;
        }
        html.append("<form method=\"post\" action=\"/account/card\" class=\"add-card-form\">")
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(accountId).append("\">")
                .append("<button class=\"bank-card add-card\" type=\"submit\" aria-label=\"Add card\">+</button>")
                .append("</form>");
        html.append("</div><button class=\"card-nav\" type=\"button\" onclick=\"scrollCards(1)\" aria-label=\"Next card\">&gt;</button></section>");
        return html.toString();
    }

    private String transactionsTable(List<TransactionRecord> transactions) {
        if (transactions.isEmpty()) {
            return "<p class=\"muted\">Операций пока нет.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"history-list\">");
        for (TransactionRecord transaction : transactions) {
            html.append("<div class=\"history-item ")
                    .append(historyClass(transaction.type()))
                    .append("\"><strong>")
                    .append(escape(historyText(transaction)))
                    .append("</strong><span>")
                    .append(escape(transaction.createdAt().toString()))
                    .append("</span></div>");
        }
        html.append("</div>");
        return html.toString();
    }

    private String historyText(TransactionRecord transaction) {
        return switch (transaction.type()) {
            case "DEPOSIT", "INITIAL_DEPOSIT" -> "+" + transaction.amount();
            case "WITHDRAW" -> "-" + transaction.amount();
            case "TRANSFER_OUT" -> "перевод на карточку " + transferTarget(transaction.description()) + " на сумму " + transaction.amount();
            case "TRANSFER_IN" -> "+" + transaction.amount() + " перевод получен";
            default -> transaction.type() + " " + transaction.amount();
        };
    }

    private String transferTarget(String description) {
        if (description == null || description.isBlank()) {
            return "-";
        }
        return description.replace("Transfer to card ", "").replace("Transfer to ", "");
    }

    private String historyClass(String type) {
        return switch (type) {
            case "DEPOSIT", "INITIAL_DEPOSIT", "TRANSFER_IN" -> "positive";
            case "WITHDRAW", "TRANSFER_OUT" -> "negative";
            default -> "";
        };
    }

    private String tableSection(String tableName, String title, String sql) throws SQLException {
        return "<section class=\"panel database-table\">"
                + "<div class=\"section-title\"><h2>" + escape(title) + "</h2><span>" + escape(tableName) + "</span></div>"
                + queryAsTable(tableName, sql)
                + "</section>";
    }

    private void deleteAccount(Connection connection, long accountId) throws SQLException {
        execute(connection, "DELETE FROM transactions WHERE account_id = ? OR related_account_id = ? OR transfer_id IN (SELECT id FROM transfers WHERE from_account_id = ? OR to_account_id = ?)",
                accountId, accountId, accountId, accountId);
        execute(connection, "DELETE FROM change_history WHERE entity_type = 'CARD' AND entity_id IN (SELECT id FROM bank_cards WHERE account_id = ?)", accountId);
        execute(connection, "DELETE FROM change_history WHERE entity_type = 'TRANSFER' AND entity_id IN (SELECT id FROM transfers WHERE from_account_id = ? OR to_account_id = ?)",
                accountId, accountId);
        execute(connection, "DELETE FROM transfers WHERE from_account_id = ? OR to_account_id = ?", accountId, accountId);
        execute(connection, "DELETE FROM bank_cards WHERE account_id = ?", accountId);
        execute(connection, "DELETE FROM change_history WHERE entity_type = 'ACCOUNT' AND entity_id = ?", accountId);
        execute(connection, "DELETE FROM accounts WHERE id = ?", accountId);
    }

    private void deleteBankCard(Connection connection, long cardId) throws SQLException {
        execute(connection, "DELETE FROM change_history WHERE entity_type = 'CARD' AND entity_id = ?", cardId);
        execute(connection, "DELETE FROM bank_cards WHERE id = ?", cardId);
    }

    private void deleteTransfer(Connection connection, long transferId) throws SQLException {
        execute(connection, "DELETE FROM transactions WHERE transfer_id = ?", transferId);
        execute(connection, "DELETE FROM change_history WHERE entity_type = 'TRANSFER' AND entity_id = ?", transferId);
        execute(connection, "DELETE FROM transfers WHERE id = ?", transferId);
    }

    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    private String queryAsTable(String tableName, String sql) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columns = metaData.getColumnCount();
            StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr>");
            for (int i = 1; i <= columns; i++) {
                html.append("<th>").append(escape(metaData.getColumnLabel(i))).append("</th>");
            }
            html.append("<th></th></tr></thead><tbody>");

            boolean hasRows = false;
            while (resultSet.next()) {
                hasRows = true;
                long rowId = resultSet.getLong("id");
                html.append("<tr>");
                for (int i = 1; i <= columns; i++) {
                    Object value = resultSet.getObject(i);
                    html.append(td(value == null ? "-" : String.valueOf(value)));
                }
                html.append("<td><form method=\"post\" action=\"/database/delete\" class=\"delete-form\">")
                        .append("<input type=\"hidden\" name=\"table\" value=\"").append(escape(tableName)).append("\">")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(rowId).append("\">")
                        .append("<button class=\"danger-button\" type=\"submit\">Удалить</button>")
                        .append("</form></td></tr>");
            }

            if (!hasRows) {
                html.append("<tr><td colspan=\"").append(columns + 1).append("\" class=\"muted\">Нет данных</td></tr>");
            }
            html.append("</tbody></table></div>");
            return html.toString();
        }
    }

    private String page(String title, String content) {
        return template("layout.html", Map.of(
                "title", escape(title),
                "content", content
        ));
    }

    private String template(String fileName, Map<String, String> values) {
        String html = resourceText("/html/" + fileName);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return html;
    }

    private String resourceText(String path) {
        try (InputStream input = WebBankApp.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Resource was not found: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read resource: " + path, e);
        }
    }

    private Map<String, String> query(HttpExchange exchange) {
        return parse(exchange.getRequestURI().getRawQuery());
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parse(body);
    }

    private Map<String, String> parse(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int delimiter = pair.indexOf('=');
            String key = delimiter >= 0 ? pair.substring(0, delimiter) : pair;
            String value = delimiter >= 0 ? pair.substring(delimiter + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private String queryMessage(Map<String, String> query) {
        String message = query.get("message");
        if (message == null || message.isBlank()) {
            return "";
        }
        return "<p class=\"message\">" + escape(message) + "</p>";
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        send(exchange, 200, html, "text/html; charset=UTF-8");
    }

    private void sendCss(HttpExchange exchange) throws IOException {
        send(exchange, 200, resourceText("/css/style.css"), "text/css; charset=UTF-8");
    }

    private void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String td(String value) {
        return "<td>" + escape(value) + "</td>";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static BigDecimal money(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Введите сумму");
        }
        return new BigDecimal(value.trim().replace(',', '.'));
    }

    private static long number(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Введите ID");
        }
        return Long.parseLong(value.trim());
    }
}
