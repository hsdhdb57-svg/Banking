package com.example.minibank;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
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

        System.out.println("Сайт мини-банкинга открыт: http://localhost:" + PORT);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(exchange, mainPage(query(exchange)));
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
                send(exchange, 404, "Страница не найдена");
            }
        } catch (Exception e) {
            sendHtml(exchange, layout("Ошибка", """
                    <section class="panel">
                        <h1>Ошибка</h1>
                        <p class="error">%s</p>
                        <a class="button" href="/">На главную</a>
                    </section>
                    """.formatted(escape(e.getMessage()))));
        }
    }

    private String mainPage(Map<String, String> query) throws SQLException {
        String message = queryMessage(query);
        String accountsTable = accountsSmallTable();
        return layout("Главная", """
                <section class="hero">
                    <div>
                        <p class="eyebrow">Java + SQL</p>
                        <h1>Мини-банкинг</h1>
                        <p>Счета, переводы, транзакции, карты и просмотр базы данных.</p>
                    </div>
                    <div class="actions">
                        <a class="button primary" href="/account">Личный кабинет</a>
                        <a class="button" href="/database">База данных</a>
                    </div>
                </section>
                %s
                <section class="grid two">
                    <div class="panel">
                        <h2>Создать счет</h2>
                        <form method="post" action="/create-account">
                            <label>Имя пользователя
                                <input name="owner" required maxlength="120" placeholder="Например: Анна">
                            </label>
                            <label>Начальный баланс
                                <input name="balance" required inputmode="decimal" placeholder="1000.00">
                            </label>
                            <button class="button primary" type="submit">Создать</button>
                        </form>
                    </div>
                    <div class="panel">
                        <h2>Счета</h2>
                        %s
                    </div>
                </section>
                """.formatted(message, accountsTable));
    }

    private String accountPage(Map<String, String> query) throws SQLException {
        String accountIdValue = query.get("id");
        String message = queryMessage(query);

        if (accountIdValue == null || accountIdValue.isBlank()) {
            return layout("Аккаунт", """
                    <section class="panel narrow">
                        <h1>Личный кабинет</h1>
                        %s
                        <form method="get" action="/account">
                            <label>ID счета
                                <input name="id" required inputmode="numeric" placeholder="1">
                            </label>
                            <button class="button primary" type="submit">Войти</button>
                        </form>
                    </section>
                    """.formatted(message));
        }

        long accountId = Long.parseLong(accountIdValue);
        Account account = bankService.getAccount(accountId);
        List<BankCard> cards = bankService.listCards(accountId);
        List<TransactionRecord> transactions = bankService.listTransactions(accountId);

        return layout("Аккаунт", """
                <section class="account-head">
                    <div>
                        <p class="eyebrow">Счет #%d</p>
                        <h1>%s</h1>
                        <p class="balance">%s</p>
                    </div>
                    <form method="get" action="/account" class="login-inline">
                        <label>Другой ID
                            <input name="id" inputmode="numeric" placeholder="1">
                        </label>
                        <button class="button" type="submit">Войти</button>
                    </form>
                </section>
                %s
                <section class="grid three">
                    <div class="panel">
                        <h2>Пополнение</h2>
                        <form method="post" action="/account/deposit">
                            <input type="hidden" name="id" value="%d">
                            <label>Сумма
                                <input name="amount" required inputmode="decimal" placeholder="250.00">
                            </label>
                            <button class="button primary" type="submit">Пополнить</button>
                        </form>
                    </div>
                    <div class="panel">
                        <h2>Снятие</h2>
                        <form method="post" action="/account/withdraw">
                            <input type="hidden" name="id" value="%d">
                            <label>Сумма
                                <input name="amount" required inputmode="decimal" placeholder="100.00">
                            </label>
                            <button class="button" type="submit">Снять</button>
                        </form>
                    </div>
                    <div class="panel">
                        <h2>Перевод</h2>
                        <form method="post" action="/account/transfer">
                            <input type="hidden" name="fromId" value="%d">
                            <label>ID получателя
                                <input name="toId" required inputmode="numeric" placeholder="2">
                            </label>
                            <label>Сумма
                                <input name="amount" required inputmode="decimal" placeholder="300.00">
                            </label>
                            <button class="button primary" type="submit">Перевести</button>
                        </form>
                    </div>
                </section>
                <section class="grid two">
                    <div class="panel">
                        <div class="section-title">
                            <h2>Карты</h2>
                            <form method="post" action="/account/card">
                                <input type="hidden" name="id" value="%d">
                                <button class="icon-button" title="Выпустить карту" aria-label="Выпустить карту">+</button>
                            </form>
                        </div>
                        %s
                    </div>
                    <div class="panel">
                        <h2>История счета</h2>
                        %s
                    </div>
                </section>
                """.formatted(
                account.id(),
                escape(account.owner()),
                escape(account.balance().toString()),
                message,
                account.id(),
                account.id(),
                account.id(),
                account.id(),
                cardsHtml(cards),
                transactionsTable(transactions)
        ));
    }

    private String databasePage(Map<String, String> query) throws SQLException {
        String message = queryMessage(query);
        return layout("База данных", """
                <section class="page-title">
                    <div>
                        <p class="eyebrow">SQL tables</p>
                        <h1>База данных</h1>
                    </div>
                    <a class="button" href="/">Главная</a>
                </section>
                %s
                %s
                %s
                %s
                %s
                %s
                """.formatted(
                message,
                tableSection("accounts", "Счета", "SELECT id, owner, balance, status, created_at FROM accounts ORDER BY id"),
                tableSection("bank_cards", "Карты", "SELECT id, account_id, card_number, expires_at, status, created_at FROM bank_cards ORDER BY id"),
                tableSection("transfers", "Переводы", "SELECT id, from_account_id, to_account_id, amount, status, created_at FROM transfers ORDER BY id"),
                tableSection("transactions", "Транзакции", "SELECT id, account_id, type, amount, related_account_id, transfer_id, description, created_at FROM transactions ORDER BY id"),
                tableSection("change_history", "История изменений", "SELECT id, entity_type, entity_id, action, details, created_at FROM change_history ORDER BY id")
        ));
    }

    private void createAccount(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        Account account = bankService.createAccount(form.get("owner"), money(form.get("balance")));
        redirect(exchange, "/account?id=" + account.id() + "&message=" + url("Счет создан"));
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
        long toId = number(form.get("toId"));
        bankService.transfer(fromId, toId, money(form.get("amount")));
        redirect(exchange, "/account?id=" + fromId + "&message=" + url("Перевод выполнен"));
    }

    private void issueCard(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        long accountId = number(form.get("id"));
        bankService.createCard(accountId);
        redirect(exchange, "/account?id=" + accountId + "&message=" + url("Карта выпущена"));
    }

    private String accountsSmallTable() throws SQLException {
        List<Account> accounts = bankService.listAccounts();
        if (accounts.isEmpty()) {
            return "<p class=\"muted\">Пока нет счетов.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr><th>ID</th><th>Пользователь</th><th>Баланс</th><th></th></tr></thead><tbody>");
        for (Account account : accounts) {
            html.append("<tr>")
                    .append(td(String.valueOf(account.id())))
                    .append(td(account.owner()))
                    .append(td(account.balance().toString()))
                    .append("<td><a class=\"link\" href=\"/account?id=").append(account.id()).append("\">Открыть</a></td>")
                    .append("</tr>");
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    private String cardsHtml(List<BankCard> cards) {
        if (cards.isEmpty()) {
            return "<p class=\"muted\">Карт пока нет.</p>";
        }
        StringBuilder html = new StringBuilder("<div class=\"cards-list\">");
        for (BankCard card : cards) {
            html.append("""
                    <div class="bank-card">
                        <span>%s</span>
                        <strong>%s</strong>
                        <small>до %s · %s</small>
                    </div>
                    """.formatted(
                    escape(card.status()),
                    escape(card.maskedNumber()),
                    escape(card.expiresAt().toString()),
                    escape(card.createdAt().toLocalDate().toString())
            ));
        }
        html.append("</div>");
        return html.toString();
    }

    private String transactionsTable(List<TransactionRecord> transactions) {
        if (transactions.isEmpty()) {
            return "<p class=\"muted\">Операций пока нет.</p>";
        }

        StringBuilder html = new StringBuilder("<div class=\"table-wrap compact\"><table><thead><tr><th>Тип</th><th>Сумма</th><th>Связь</th><th>Дата</th></tr></thead><tbody>");
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
        return """
                <section class="panel database-table">
                    <div class="section-title">
                        <h2>%s</h2>
                        <span>%s</span>
                    </div>
                    %s
                </section>
                """.formatted(escape(title), escape(tableName), queryAsTable(sql));
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
                html.append("<tr><td colspan=\"").append(columns).append("\" class=\"muted\">Нет данных</td></tr>");
            }
            html.append("</tbody></table></div>");
            return html.toString();
        }
    }

    private String layout(String title, String content) {
        return """
                <!doctype html>
                <html lang="ru">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s · Мини-банкинг</title>
                    <style>
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            min-height: 100vh;
                            font-family: Arial, sans-serif;
                            color: #17202a;
                            background: #f5f7fb;
                        }
                        a { color: inherit; }
                        .topbar {
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            gap: 16px;
                            padding: 18px 32px;
                            background: #ffffff;
                            border-bottom: 1px solid #dde5ee;
                            position: sticky;
                            top: 0;
                            z-index: 1;
                        }
                        .brand { font-weight: 800; letter-spacing: 0; }
                        .nav { display: flex; gap: 8px; flex-wrap: wrap; }
                        .nav a, .button, .icon-button {
                            min-height: 40px;
                            border: 1px solid #c8d2df;
                            background: #ffffff;
                            border-radius: 8px;
                            padding: 10px 14px;
                            text-decoration: none;
                            font-weight: 700;
                            cursor: pointer;
                        }
                        .button.primary {
                            background: #126f6a;
                            color: #ffffff;
                            border-color: #126f6a;
                        }
                        .icon-button {
                            width: 40px;
                            padding: 0;
                            font-size: 24px;
                            line-height: 1;
                        }
                        main {
                            width: min(1180px, calc(100%% - 32px));
                            margin: 28px auto 52px;
                        }
                        .hero, .account-head, .page-title {
                            display: flex;
                            justify-content: space-between;
                            align-items: center;
                            gap: 24px;
                            padding: 32px;
                            background: #ffffff;
                            border: 1px solid #dde5ee;
                            border-radius: 8px;
                            margin-bottom: 18px;
                        }
                        .hero h1, .account-head h1, .page-title h1 {
                            margin: 4px 0 8px;
                            font-size: 40px;
                        }
                        .hero p, .account-head p, .page-title p { margin: 0; }
                        .eyebrow {
                            color: #b94700;
                            font-weight: 800;
                            text-transform: uppercase;
                            font-size: 12px;
                        }
                        .balance {
                            font-size: 28px;
                            font-weight: 800;
                            color: #126f6a;
                        }
                        .actions { display: flex; gap: 10px; flex-wrap: wrap; }
                        .grid {
                            display: grid;
                            gap: 18px;
                            margin-top: 18px;
                        }
                        .grid.two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                        .grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
                        .panel {
                            background: #ffffff;
                            border: 1px solid #dde5ee;
                            border-radius: 8px;
                            padding: 22px;
                        }
                        .panel.narrow {
                            max-width: 520px;
                            margin: 0 auto;
                        }
                        .panel h1, .panel h2 { margin-top: 0; }
                        .section-title {
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            gap: 12px;
                            margin-bottom: 12px;
                        }
                        .section-title h2 { margin: 0; }
                        .section-title span {
                            color: #5b6673;
                            font-size: 14px;
                            font-weight: 700;
                        }
                        form { display: grid; gap: 12px; }
                        .login-inline {
                            grid-template-columns: minmax(120px, 180px) auto;
                            align-items: end;
                        }
                        label {
                            display: grid;
                            gap: 6px;
                            color: #344054;
                            font-size: 14px;
                            font-weight: 700;
                        }
                        input {
                            width: 100%%;
                            min-height: 42px;
                            border: 1px solid #c8d2df;
                            border-radius: 8px;
                            padding: 9px 11px;
                            font: inherit;
                            background: #fbfcfe;
                        }
                        .table-wrap {
                            width: 100%%;
                            overflow-x: auto;
                            border: 1px solid #e3e9f1;
                            border-radius: 8px;
                        }
                        table {
                            width: 100%%;
                            border-collapse: collapse;
                            min-width: 560px;
                        }
                        th, td {
                            padding: 10px 12px;
                            border-bottom: 1px solid #e3e9f1;
                            text-align: left;
                            white-space: nowrap;
                            font-size: 14px;
                        }
                        th {
                            background: #eef3f8;
                            color: #344054;
                        }
                        tr:last-child td { border-bottom: 0; }
                        .compact table { min-width: 520px; }
                        .link {
                            color: #126f6a;
                            font-weight: 800;
                            text-decoration: none;
                        }
                        .message, .error {
                            border-radius: 8px;
                            padding: 12px 14px;
                            margin: 0 0 18px;
                            font-weight: 700;
                        }
                        .message {
                            background: #e6f4ef;
                            color: #126f6a;
                            border: 1px solid #a9d9cb;
                        }
                        .error {
                            background: #fff0eb;
                            color: #b94700;
                            border: 1px solid #f1baa6;
                        }
                        .muted { color: #667085; }
                        .cards-list {
                            display: grid;
                            gap: 12px;
                        }
                        .bank-card {
                            min-height: 130px;
                            border-radius: 8px;
                            padding: 18px;
                            display: grid;
                            align-content: space-between;
                            color: #ffffff;
                            background: linear-gradient(135deg, #17202a, #126f6a 70%%, #b94700);
                        }
                        .bank-card span, .bank-card small { opacity: .86; }
                        .bank-card strong {
                            font-size: 22px;
                            letter-spacing: 0;
                        }
                        .database-table { margin-top: 18px; }
                        @media (max-width: 850px) {
                            .topbar, .hero, .account-head, .page-title {
                                align-items: stretch;
                                flex-direction: column;
                            }
                            .grid.two, .grid.three { grid-template-columns: 1fr; }
                            .hero h1, .account-head h1, .page-title h1 { font-size: 32px; }
                            .login-inline { grid-template-columns: 1fr; }
                        }
                    </style>
                </head>
                <body>
                    <header class="topbar">
                        <a class="brand" href="/">Мини-банкинг</a>
                        <nav class="nav">
                            <a href="/">Главная</a>
                            <a href="/account">Аккаунт</a>
                            <a href="/database">База данных</a>
                        </nav>
                    </header>
                    <main>%s</main>
                </body>
                </html>
                """.formatted(escape(title), content);
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
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        send(exchange, 200, html);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
