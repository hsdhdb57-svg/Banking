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

        System.out.println("РЎР°Р№С‚ РјРёРЅРё-Р±Р°РЅРєРёРЅРіР° РѕС‚РєСЂС‹С‚: http://localhost:" + PORT);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(exchange, mainPage(query(exchange)));
            } else if ("GET".equals(method) && "/style.css".equals(path)) {
                sendCss(exchange);
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
            } else {
                send(exchange, 404, "РЎС‚СЂР°РЅРёС†Р° РЅРµ РЅР°Р№РґРµРЅР°", "text/plain; charset=UTF-8");
            }
        } catch (Exception e) {
            sendHtml(exchange, page("РћС€РёР±РєР°", template("error.html", Map.of(
                    "error", escape(e.getMessage())
            ))));
        }
    }

    private String mainPage(Map<String, String> query) throws SQLException {
        return page("Р“Р»Р°РІРЅР°СЏ", template("main.html", Map.of(
                "message", queryMessage(query),
                "accountsTable", accountsSmallTable()
        )));
    }

    private String accountPage(Map<String, String> query) throws SQLException {
        String accountIdValue = query.get("id");
        if (accountIdValue == null || accountIdValue.isBlank()) {
            return page("РђРєРєР°СѓРЅС‚", template("account-login.html", Map.of(
                    "message", queryMessage(query)
            )));
        }

        long accountId = Long.parseLong(accountIdValue);
        Account account = bankService.getAccount(accountId);
        List<BankCard> cards = bankService.listCards(accountId);
        List<TransactionRecord> transactions = bankService.listTransactions(accountId);

        return page("РђРєРєР°СѓРЅС‚", template("account.html", Map.of(
                "accountId", String.valueOf(account.id()),
                "owner", escape(account.owner()),
                "balance", escape(account.balance().toString()),
                "message", queryMessage(query),
                "cards", cardsHtml(cards),
                "transactions", transactionsTable(transactions)
        )));
    }

    private String databasePage(Map<String, String> query) throws SQLException {
        String tables = tableSection("accounts", "РЎС‡РµС‚Р°",
                "SELECT id, owner, balance, status, created_at FROM accounts ORDER BY id")
                + tableSection("bank_cards", "РљР°СЂС‚С‹",
                "SELECT id, account_id, card_number, expires_at, status, created_at FROM bank_cards ORDER BY id")
                + tableSection("transfers", "РџРµСЂРµРІРѕРґС‹",
                "SELECT id, from_account_id, to_account_id, amount, status, created_at FROM transfers ORDER BY id")
                + tableSection("transactions", "РўСЂР°РЅР·Р°РєС†РёРё",
                "SELECT id, account_id, type, amount, related_account_id, transfer_id, description, created_at FROM transactions ORDER BY id")
                + tableSection("change_history", "РСЃС‚РѕСЂРёСЏ РёР·РјРµРЅРµРЅРёР№",
                "SELECT id, entity_type, entity_id, action, details, created_at FROM change_history ORDER BY id");

        return page("Р‘Р°Р·Р° РґР°РЅРЅС‹С…", template("database.html", Map.of(
                "message", queryMessage(query),
                "tables", tables
        )));
    }

    private void createAccount(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        Account account = bankService.createAccount(form.get("owner"), money(form.get("balance")));
        redirect(exchange, "/account?id=" + account.id() + "&message=" + url("РЎС‡РµС‚ СЃРѕР·РґР°РЅ"));
    }

    private void deposit(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.deposit(accountId, money(form.get("amount")));
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("РЎС‡РµС‚ РїРѕРїРѕР»РЅРµРЅ"));
    }

    private void withdraw(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.withdraw(accountId, money(form.get("amount")));
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("Р”РµРЅСЊРіРё СЃРЅСЏС‚С‹"));
    }

    private void transfer(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long fromId = number(form.get("fromId"));
        long toId = number(form.get("toId"));
        bankService.transfer(fromId, toId, money(form.get("amount")));
        redirect(exchange, "/account?id=" + fromId + "&message=" + url("РџРµСЂРµРІРѕРґ РІС‹РїРѕР»РЅРµРЅ"));
    }

    private void issueCard(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.createCard(accountId);
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("РљР°СЂС‚Р° РІС‹РїСѓС‰РµРЅР°"));
    }

    private String accountsSmallTable() throws SQLException {
        List<Account> accounts = bankService.listAccounts();
        if (accounts.isEmpty()) {
            return "<p class=\"muted\">РџРѕРєР° РЅРµС‚ СЃС‡РµС‚РѕРІ.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr><th>ID</th><th>РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ</th><th>Р‘Р°Р»Р°РЅСЃ</th><th></th></tr></thead><tbody>");
        for (Account account : accounts) {
            html.append("<tr>")
                    .append(td(String.valueOf(account.id())))
                    .append(td(account.owner()))
                    .append(td(account.balance().toString()))
                    .append("<td><a class=\"link\" href=\"/account?id=").append(account.id()).append("\">РћС‚РєСЂС‹С‚СЊ</a></td>")
                    .append("</tr>");
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    private String cardsHtml(List<BankCard> cards) {
        if (cards.isEmpty()) {
            return "<p class=\"muted\">РљР°СЂС‚ РїРѕРєР° РЅРµС‚.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"cards-list\">");
        for (BankCard card : cards) {
            html.append("<div class=\"bank-card\">")
                    .append("<span>").append(escape(card.status())).append("</span>")
                    .append("<strong>").append(escape(card.maskedNumber())).append("</strong>")
                    .append("<small>РґРѕ ").append(escape(card.expiresAt().toString()))
                    .append(" В· ").append(escape(card.createdAt().toLocalDate().toString())).append("</small>")
                    .append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    private String transactionsTable(List<TransactionRecord> transactions) {
        if (transactions.isEmpty()) {
            return "<p class=\"muted\">РћРїРµСЂР°С†РёР№ РїРѕРєР° РЅРµС‚.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"table-wrap compact\"><table><thead><tr><th>РўРёРї</th><th>РЎСѓРјРјР°</th><th>РЎРІСЏР·СЊ</th><th>Р”Р°С‚Р°</th></tr></thead><tbody>");
        for (TransactionRecord transaction : transactions) {
            html.append("<tr>")
                    .append(td(transaction.type()))
                    .append(td(transaction.amount().toString()))
                    .append(td(transaction.relatedAccountId() == null ? "-" : String.valueOf(transaction.relatedAccountId())))
                    .append(td(transaction.createdAt().toString()))
                    .append("</tr>");
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    private String tableSection(String tableName, String title, String sql) throws SQLException {
        return "<section class=\"panel database-table\">"
                + "<div class=\"section-title\"><h2>" + escape(title) + "</h2><span>" + escape(tableName) + "</span></div>"
                + queryAsTable(sql)
                + "</section>";
    }

    private String queryAsTable(String sql) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columns = metaData.getColumnCount();
            StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr>");
            for (int i = 1; i <= columns; i++) {
                html.append("<th>").append(escape(metaData.getColumnLabel(i))).append("</th>");
            }
            html.append("</tr></thead><tbody>");

            boolean hasRows = false;
            while (resultSet.next()) {
                hasRows = true;
                html.append("<tr>");
                for (int i = 1; i <= columns; i++) {
                    Object value = resultSet.getObject(i);
                    html.append(td(value == null ? "-" : String.valueOf(value)));
                }
                html.append("</tr>");
            }

            if (!hasRows) {
                html.append("<tr><td colspan=\"").append(columns).append("\" class=\"muted\">РќРµС‚ РґР°РЅРЅС‹С…</td></tr>");
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
            throw new IllegalArgumentException("Р’РІРµРґРёС‚Рµ СЃСѓРјРјСѓ");
        }
        return new BigDecimal(value.trim().replace(',', '.'));
    }

    private static long number(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Р’РІРµРґРёС‚Рµ ID");
        }
        return Long.parseLong(value.trim());
    }
}

