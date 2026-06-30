package com.financeos.core.data;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.*;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final Random random = new Random();

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Checking if data seeding is required...");

        Optional<User> adminUserOpt = userRepository.findByEmail("asd@asd.asd");
        if (adminUserOpt.isEmpty()) {
            log.warn("Admin user not found. Skipping data seeding.");
            return;
        }

        User admin = adminUserOpt.get();

        log.info("Starting robust data seeding for user: {}", admin.getEmail());

        // 1. Seed Categories
        Map<String, Category> categories = seedCategories(admin);

        // 2. Seed Accounts
        List<Account> accounts = seedAccounts(admin);

        // 3. Seed Transactions (2 years history)
        seedTransactions(admin, accounts, categories);

        log.info("Data seeding completed successfully!");
    }

    private Map<String, Category> seedCategories(User user) {
        Map<String, Category> categoryMap = new HashMap<>();
        List<String> categoryNames = List.of(
                "Salary", "Freelance", "Interest", // Income
                "Rent", "Utilities", "Groceries", "Healthcare", // Essential
                "Dining", "Shopping", "Entertainment", "Travel", "Subscriptions", // Lifestyle
                "Fuel", "Vehicle Maintenance", // Automotive
                "Investment", "Insurance", "Taxes" // Financial
        );

        for (String name : categoryNames) {
            Category category = new Category(name, user);
            categoryMap.put(name, categoryRepository.save(category));
        }
        return categoryMap;
    }

    private List<Account> seedAccounts(User user) {
        List<Account> accounts = new ArrayList<>();

        accounts.add(createAccount(user, "HDFC Bank", AccountType.bank_account, FinancialPosition.asset, false));
        accounts.add(createAccount(user, "ICICI Bank", AccountType.bank_account, FinancialPosition.asset, false));
        accounts.add(createAccount(user, "SBI Bank", AccountType.bank_account, FinancialPosition.asset, false));
        accounts.add(
                createAccount(user, "ICICI Credit card", AccountType.credit_card, FinancialPosition.liability, false));
        accounts.add(createAccount(user, "Amazon Pay Credit card", AccountType.credit_card, FinancialPosition.liability,
                false));
        accounts.add(createAccount(user, "Cash", AccountType.generic, FinancialPosition.asset, true));
        accounts.add(createAccount(user, "Petty Cash", AccountType.generic, FinancialPosition.asset, true));

        return accounts;
    }

    private Account createAccount(User user, String name, AccountType type, FinancialPosition position,
            boolean exclude) {
        Account account = new Account(name, type);
        account.setUser(user);
        account.setFinancialPosition(position);
        account.setExcludeFromNetAsset(exclude);
        return accountRepository.save(account);
    }

    private void seedTransactions(User user, List<Account> accounts, Map<String, Category> categories) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(5);

        List<Transaction> allTransactions = new ArrayList<>();

        for (Account account : accounts) {
            LocalDate current = start;
            while (current.isBefore(end)) {
                // Approximate 3-5 transactions per week for active accounts
                int transactionsThisWeek = 2 + random.nextInt(4);

                for (int i = 0; i < transactionsThisWeek; i++) {
                    LocalDate txDate = current.plusDays(random.nextInt(7));
                    if (txDate.isAfter(end))
                        break;

                    allTransactions.add(generateRandomTransaction(user, account, txDate, categories));
                }
                current = current.plusWeeks(1);
            }
        }

        transactionRepository.saveAll(allTransactions);
        log.info("Seeded {} transactions across 5 years.", allTransactions.size());
    }

    private Transaction generateRandomTransaction(User user, Account account, LocalDate date,
            Map<String, Category> categories) {
        boolean isAutomated = random.nextDouble() < 0.9;
        TransactionSource source = isAutomated ? TransactionSource.gmail_transaction_alert : TransactionSource.manual;

        TransactionType type = TransactionType.DEBIT;
        BigDecimal amount;
        String description;
        String sourcedDescription = null;
        Category category;

        String tempDescription;
        if (random.nextDouble() < 0.05) { // 5% chance of being income
            type = TransactionType.CREDIT;
            amount = BigDecimal.valueOf(1000 + random.nextInt(50000)).setScale(2, RoundingMode.HALF_UP);
            List<String> incomeCats = List.of("Salary", "Freelance", "Interest");
            category = categories.get(incomeCats.get(random.nextInt(incomeCats.size())));
            tempDescription = category.getName();
        } else {
            amount = BigDecimal.valueOf(10 + random.nextInt(2000)).setScale(2, RoundingMode.HALF_UP);
            List<String> expenseCats = List.of("Dining", "Shopping", "Groceries", "Fuel", "Utilities", "Travel",
                    "Subscriptions");
            category = categories.get(expenseCats.get(random.nextInt(expenseCats.size())));
            tempDescription = category.getName() + " spending";
        }

        if (isAutomated) {
            sourcedDescription = "SIMULATED_GMAIL_ALERT: " + tempDescription + " of amount " + amount + " on " + date;
        }
        description = tempDescription;

        boolean isMonitoring = random.nextDouble() < 0.10;
        boolean isExcluded = random.nextDouble() < 0.05;

        Transaction transaction = new Transaction(
                account,
                date,
                amount,
                description,
                source,
                type,
                isMonitoring,
                isExcluded);
        transaction.setUser(user);
        transaction.setSourcedDescription(sourcedDescription);

        Set<Category> cats = new HashSet<>();
        cats.add(category);
        transaction.setCategories(cats);

        return transaction;
    }
}
