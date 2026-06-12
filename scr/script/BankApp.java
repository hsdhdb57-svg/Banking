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
        System.out.println("РњРёРЅРё-Р±Р°РЅРєРёРЅРі Java + SQL");

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
                        System.out.println("Р“РѕС‚РѕРІРѕ.");
                        return;
                    }
                    default -> System.out.println("РќРµРёР·РІРµСЃС‚РЅР°СЏ РєРѕРјР°РЅРґР°.");
                }
            } catch (Exception e) {
                System.out.println("РћС€РёР±РєР°: " + e.getMessage());
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("1. РџРѕРєР°Р·Р°С‚СЊ СЃС‡РµС‚Р°");
        System.out.println("2. РЎРѕР·РґР°С‚СЊ СЃС‡РµС‚");
        System.out.println("3. РџРѕРїРѕР»РЅРёС‚СЊ СЃС‡РµС‚");
        System.out.println("4. РЎРЅСЏС‚СЊ РґРµРЅСЊРіРё");
        System.out.println("5. РџРµСЂРµРІРµСЃС‚Рё РґРµРЅСЊРіРё");
        System.out.println("6. РџРѕРєР°Р·Р°С‚СЊ С‚СЂР°РЅР·Р°РєС†РёРё");
        System.out.println("7. РџРѕРєР°Р·Р°С‚СЊ РёСЃС‚РѕСЂРёСЋ РёР·РјРµРЅРµРЅРёР№");
        System.out.println("0. Р’С‹С…РѕРґ");
        System.out.print("Р’С‹Р±РµСЂРёС‚Рµ РґРµР№СЃС‚РІРёРµ: ");
    }

    private void showAccounts() throws SQLException {
        List<Account> accounts = bankService.listAccounts();
        if (accounts.isEmpty()) {
            System.out.println("РЎС‡РµС‚РѕРІ РїРѕРєР° РЅРµС‚.");
            return;
        }

        System.out.printf("%-4s %-22s %-12s %-10s %-20s%n", "ID", "Р’Р»Р°РґРµР»РµС†", "Р‘Р°Р»Р°РЅСЃ", "РЎС‚Р°С‚СѓСЃ", "РЎРѕР·РґР°РЅ");
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
        System.out.print("РРјСЏ РІР»Р°РґРµР»СЊС†Р°: ");
        String owner = scanner.nextLine();
        BigDecimal initialBalance = readMoney("РќР°С‡Р°Р»СЊРЅС‹Р№ Р±Р°Р»Р°РЅСЃ: ");
        Account account = bankService.createAccount(owner, initialBalance);
        System.out.println("РЎРѕР·РґР°РЅ СЃС‡РµС‚ #" + account.id() + " СЃ Р±Р°Р»Р°РЅСЃРѕРј " + account.balance());
    }

    private void deposit() throws SQLException {
        long accountId = readLong("ID СЃС‡РµС‚Р°: ");
        BigDecimal amount = readMoney("РЎСѓРјРјР° РїРѕРїРѕР»РЅРµРЅРёСЏ: ");
        bankService.deposit(accountId, amount);
        System.out.println("РЎС‡РµС‚ РїРѕРїРѕР»РЅРµРЅ.");
    }

    private void withdraw() throws SQLException {
        long accountId = readLong("ID СЃС‡РµС‚Р°: ");
        BigDecimal amount = readMoney("РЎСѓРјРјР° СЃРЅСЏС‚РёСЏ: ");
        bankService.withdraw(accountId, amount);
        System.out.println("Р”РµРЅСЊРіРё СЃРЅСЏС‚С‹.");
    }

    private void transfer() throws SQLException {
        long fromAccountId = readLong("РЎРѕ СЃС‡РµС‚Р° ID: ");
        long toAccountId = readLong("РќР° СЃС‡РµС‚ ID: ");
        BigDecimal amount = readMoney("РЎСѓРјРјР° РїРµСЂРµРІРѕРґР°: ");
        long transferId = bankService.transfer(fromAccountId, toAccountId, amount);
        System.out.println("РџРµСЂРµРІРѕРґ РІС‹РїРѕР»РЅРµРЅ. ID РїРµСЂРµРІРѕРґР°: " + transferId);
    }

    private void showTransactions() throws SQLException {
        System.out.print("ID СЃС‡РµС‚Р° РёР»Рё Enter РґР»СЏ РІСЃРµС…: ");
        String value = scanner.nextLine().trim();
        Long accountId = value.isBlank() ? null : Long.parseLong(value);
        List<TransactionRecord> transactions = bankService.listTransactions(accountId);

        if (transactions.isEmpty()) {
            System.out.println("РўСЂР°РЅР·Р°РєС†РёР№ РїРѕРєР° РЅРµС‚.");
            return;
        }

        System.out.printf("%-4s %-8s %-16s %-12s %-8s %-8s %-20s %-30s%n",
                "ID", "РЎС‡РµС‚", "РўРёРї", "РЎСѓРјРјР°", "РЎРІСЏР·СЊ", "РџРµСЂРµРІРѕРґ", "Р”Р°С‚Р°", "РћРїРёСЃР°РЅРёРµ");
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
            System.out.println("РСЃС‚РѕСЂРёСЏ РёР·РјРµРЅРµРЅРёР№ РїРѕРєР° РїСѓСЃС‚Р°СЏ.");
            return;
        }

        System.out.printf("%-4s %-10s %-8s %-18s %-20s %-50s%n",
                "ID", "РЎСѓС‰РЅРѕСЃС‚СЊ", "РќРѕРјРµСЂ", "Р”РµР№СЃС‚РІРёРµ", "Р”Р°С‚Р°", "Р”РµС‚Р°Р»Рё");
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
                System.out.println("Р’РІРµРґРёС‚Рµ С†РµР»РѕРµ С‡РёСЃР»Рѕ.");
            }
        }
    }

    private BigDecimal readMoney(String label) {
        while (true) {
            System.out.print(label);
            try {
                return new BigDecimal(scanner.nextLine().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                System.out.println("Р’РІРµРґРёС‚Рµ СЃСѓРјРјСѓ, РЅР°РїСЂРёРјРµСЂ 100.50.");
            }
        }
    }

    private String valueOrDash(Long value) {
        return value == null ? "-" : String.valueOf(value);
    }
}

