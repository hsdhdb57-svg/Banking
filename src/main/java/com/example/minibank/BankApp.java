package com.example.minibank;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Scanner;

public class BankApp {
    private final Scanner scanner = new Scanner(System.in);
    private final BankService bankService;

    public BankApp(BankService bankService) {
        this.bankService = bankService;
    }

    public static void main(String[] args) {
        Database database = new Database();
        database.initialize();
        BankService bankService = new BankService(database);
        new BankApp(bankService).run();
    }

    private void run() {
        System.out.println("Мини-банкинг Java + SQL");

        while (true) {
            printMenu();
            String choice = scanner.nextLine().trim();

            try {
                switch (choice) {
                    case "1" -> showAccounts();
                    case "2" -> createAccount();
                    case "3" -> deposit();
                    case "4" -> withdraw();
                    case "5" -> transfer();
                    case "6" -> showTransactions();
                    case "7" -> showChangeHistory();
                    case "0" -> {
                        System.out.println("Готово.");
                        return;
                    }
                    default -> System.out.println("Неизвестная команда.");
                }
            } catch (Exception e) {
                System.out.println("Ошибка: " + e.getMessage());
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("1. Показать счета");
        System.out.println("2. Создать счет");
        System.out.println("3. Пополнить счет");
        System.out.println("4. Снять деньги");
        System.out.println("5. Перевести деньги");
        System.out.println("6. Показать транзакции");
        System.out.println("7. Показать историю изменений");
        System.out.println("0. Выход");
        System.out.print("Выберите действие: ");
    }

    private void showAccounts() throws SQLException {
        List<Account> accounts = bankService.listAccounts();
        if (accounts.isEmpty()) {
            System.out.println("Счетов пока нет.");
            return;
        }

        System.out.printf("%-4s %-22s %-12s %-10s %-20s%n", "ID", "Владелец", "Баланс", "Статус", "Создан");
        for (Account account : accounts) {
            System.out.printf("%-4d %-22s %-12s %-10s %-20s%n",
                    account.id(),
                    account.owner(),
                    account.balance(),
                    account.status(),
                    account.createdAt());
        }
    }

    private void createAccount() throws SQLException {
        System.out.print("Имя владельца: ");
        String owner = scanner.nextLine();
        BigDecimal initialBalance = readMoney("Начальный баланс: ");
        Account account = bankService.createAccount(owner, initialBalance);
        System.out.println("Создан счет #" + account.id() + " с балансом " + account.balance());
    }

    private void deposit() throws SQLException {
        long accountId = readLong("ID счета: ");
        BigDecimal amount = readMoney("Сумма пополнения: ");
        bankService.deposit(accountId, amount);
        System.out.println("Счет пополнен.");
    }

    private void withdraw() throws SQLException {
        long accountId = readLong("ID счета: ");
        BigDecimal amount = readMoney("Сумма снятия: ");
        bankService.withdraw(accountId, amount);
        System.out.println("Деньги сняты.");
    }

    private void transfer() throws SQLException {
        long fromAccountId = readLong("Со счета ID: ");
        long toAccountId = readLong("На счет ID: ");
        BigDecimal amount = readMoney("Сумма перевода: ");
        long transferId = bankService.transfer(fromAccountId, toAccountId, amount);
        System.out.println("Перевод выполнен. ID перевода: " + transferId);
    }

    private void showTransactions() throws SQLException {
        System.out.print("ID счета или Enter для всех: ");
        String value = scanner.nextLine().trim();
        Long accountId = value.isBlank() ? null : Long.parseLong(value);
        List<TransactionRecord> transactions = bankService.listTransactions(accountId);

        if (transactions.isEmpty()) {
            System.out.println("Транзакций пока нет.");
            return;
        }

        System.out.printf("%-4s %-8s %-16s %-12s %-8s %-8s %-20s %-30s%n",
                "ID", "Счет", "Тип", "Сумма", "Связь", "Перевод", "Дата", "Описание");
        for (TransactionRecord transaction : transactions) {
            System.out.printf("%-4d %-8d %-16s %-12s %-8s %-8s %-20s %-30s%n",
                    transaction.id(),
                    transaction.accountId(),
                    transaction.type(),
                    transaction.amount(),
                    valueOrDash(transaction.relatedAccountId()),
                    valueOrDash(transaction.transferId()),
                    transaction.createdAt(),
                    transaction.description());
        }
    }

    private void showChangeHistory() throws SQLException {
        List<ChangeHistoryEntry> history = bankService.listChangeHistory();
        if (history.isEmpty()) {
            System.out.println("История изменений пока пустая.");
            return;
        }

        System.out.printf("%-4s %-10s %-8s %-18s %-20s %-50s%n",
                "ID", "Сущность", "Номер", "Действие", "Дата", "Детали");
        for (ChangeHistoryEntry entry : history) {
            System.out.printf("%-4d %-10s %-8d %-18s %-20s %-50s%n",
                    entry.id(),
                    entry.entityType(),
                    entry.entityId(),
                    entry.action(),
                    entry.createdAt(),
                    entry.details());
        }
    }

    private long readLong(String label) {
        while (true) {
            System.out.print(label);
            try {
                return Long.parseLong(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Введите целое число.");
            }
        }
    }

    private BigDecimal readMoney(String label) {
        while (true) {
            System.out.print(label);
            try {
                return new BigDecimal(scanner.nextLine().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                System.out.println("Введите сумму, например 100.50.");
            }
        }
    }

    private String valueOrDash(Long value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
