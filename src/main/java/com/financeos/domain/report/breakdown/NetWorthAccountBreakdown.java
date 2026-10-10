package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Breakdown of an account row of net worth.
 *
 * <p>Bank, generic and credit card accounts: the balance's base (the anchor statement's closing
 * balance, the opening balance, or nothing) plus the credits and minus the debits that the balance
 * counts — every transaction of the account (excluded ones too) dated after the anchor statement's
 * period end, or all of them without an anchor. The movements come from one aggregate over exactly
 * the balance's transactions, and the base is the account's calculated balance minus those
 * movements: {@code BalanceMath} computes the balance as base + movements over the same rows (with
 * the card sign flip of the statement's closing balance), so this is that base, read back without
 * restating its rules.
 *
 * <p>Brokers: the cash balance plus the market value of each open holding, the same per-holding
 * {@link InvestmentService#calculateHoldingPosition} figures the account's balance sums.
 */
@Component
@Transactional(readOnly = true)
class NetWorthAccountBreakdown implements NetWorthItemBreakdown {

    static final String TRANSACTIONS = "transactions";
    static final String HOLDINGS = "holdings";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<TableData.Column> TRANSACTION_COLUMNS = List.of(
            new TableData.Column("date", "Date", "date", null),
            new TableData.Column("description", "Description", "string", null),
            new TableData.Column("category", "Category", "string", null),
            new TableData.Column("amount", "Amount", "number", "currency"),
            new TableData.Column("excluded", "Excluded", "boolean", null));

    private static final List<TableData.Column> HOLDING_COLUMNS = List.of(
            new TableData.Column("instrument", "Instrument", "string", null),
            new TableData.Column("quantity", "Quantity", "number", "number"),
            new TableData.Column("price", "Price", "number", "currency"),
            new TableData.Column("priceDate", "Price date", "date", null),
            new TableData.Column("value", "Value", "number", "currency"),
            new TableData.Column("valuation", "Valuation", "string", null));

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final InvestmentService investmentService;

    NetWorthAccountBreakdown(AccountService accountService, TransactionRepository transactionRepository,
                             HoldingRepository holdingRepository, InvestmentService investmentService) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.investmentService = investmentService;
    }

    @Override
    public Optional<RowBreakdownResponse> breakdown(UUID id, int size) {
        return ownedAccount(id).map(account -> account.getType() == AccountType.broker
                ? broker(account, size)
                : ledger(account, size));
    }

    @Override
    public Optional<ReportData> section(UUID id, String section, int page, int size) {
        return section(id, section, page, size, null);
    }

    @Override
    public Optional<ReportData> section(UUID id, String section, int page, int size, @Nullable SortClause sort) {
        return ownedAccount(id).map(account -> {
            boolean broker = account.getType() == AccountType.broker;
            if (broker && HOLDINGS.equals(section)) {
                return BreakdownTables.sorted(HOLDING_COLUMNS, holdingRows(openPositions(account), overrides()), sort, page, size);
            }
            if (!broker && TRANSACTIONS.equals(section)) {
                return sort == null
                        ? transactionsTable(account, anchorDate(account), page, size)
                        : sortedTransactionsTable(account, anchorDate(account), sort, page, size);
            }
            throw new ResourceNotFoundException("Breakdown section", section);
        });
    }

    /**
     * The current user's account (another user's or an unknown id is empty → 404). Accounts net
     * worth leaves out today (excluded, closed) are served too, flagged not counted.
     */
    private Optional<Account> ownedAccount(UUID id) {
        return accountService.findOwnedAccount(id);
    }

    /** {@link NetWorthBreakdownProvider#accountResponse} with the account's omission today. */
    private static RowBreakdownResponse response(Account account, NetWorthPlacement placement, String totalLabel,
                                                 List<BreakdownStep> steps, List<BreakdownSectionData> sections,
                                                 List<String> notes) {
        return NetWorthBreakdownProvider.accountResponse(account.getId(), account.getName(),
                kindLabel(account.getType()), placement, totalLabel, steps, sections, notes,
                NetWorthPlacement.omission(account, AppTime.today()), account.getClosedOn());
    }

    // ------------------------------------------------------------------ bank / generic / credit card

    private RowBreakdownResponse ledger(Account account, int size) {
        LocalDate anchor = anchorDate(account);
        TransactionRepository.BalanceMovementsProjection movements =
                transactionRepository.findBalanceMovements(account.getId(), anchor);
        long creditCount = movements.getCreditCount();
        long debitCount = movements.getDebitCount();
        BigDecimal credits = movements.getCreditSum();
        BigDecimal debits = movements.getDebitSum();
        BigDecimal balance = NetWorthPlacement.balance(account);
        BigDecimal base = balance.subtract(credits).add(debits);
        boolean card = account.getType() == AccountType.credit_card;
        String since = anchor != null ? DATE.format(anchor) : null;

        BreakdownChain chain = new BreakdownChain(balance);
        if (anchor != null) {
            chain.start(card
                    ? (base.signum() > 0 ? "In credit on statement ending " : "Owed on statement ending ") + since
                    : "Closing balance on statement ending " + since, base);
        } else if (card) {
            chain.start("Starting balance", base);
        } else if (account.getType() == AccountType.bank_account || base.signum() != 0) {
            chain.start("Opening balance", base);
        }
        if (card) {
            movement(chain, since == null ? "Spends" : "Spends since " + since, debitCount, debits.negate());
            movement(chain, since == null ? "Payments and refunds" : "Payments and refunds since " + since,
                    creditCount, credits);
        } else {
            movement(chain, since == null ? "Credits" : "Credits after " + since, creditCount, credits);
            movement(chain, since == null ? "Debits" : "Debits after " + since, debitCount, debits.negate());
        }
        String totalLabel = card ? (balance.signum() > 0 ? "In credit" : "Outstanding") : "Balance";
        NetWorthPlacement placement = NetWorthPlacement.ofAccount(account);
        List<BreakdownStep> steps = chain.close(totalLabel, placement.value(), "account " + account.getId());

        List<String> notes = new ArrayList<>();
        if (account.getReconciliationGap() != null) {
            notes.add("Opening balance plus all transactions differs from the statement-anchored balance by "
                    + MessageFormat.money(account.getReconciliationGap().abs()) + ".");
        }
        if (movements.getExcludedCount() > 0) {
            notes.add("Excluded transactions still count towards balances.");
        }
        BreakdownSectionData transactions = new BreakdownSectionData(TRANSACTIONS,
                since == null ? "Transactions" : "Transactions after " + since, "transaction", null,
                transactionsTable(account, anchor, 0, size));
        return response(account, placement, totalLabel, steps, List.of(transactions), notes);
    }

    /** Adds a movement of {@code count} transactions, or nothing when there are none. */
    private static void movement(BreakdownChain chain, String label, long count, BigDecimal signedAmount) {
        if (count > 0) {
            chain.term(label + " (" + count + ")", signedAmount);
        }
    }

    /** The anchor statement's period end when the balance is anchored, else null (all transactions count). */
    private static LocalDate anchorDate(Account account) {
        return Boolean.TRUE.equals(account.getBalanceAnchored()) ? account.getAnchorDate() : null;
    }

    private TableData transactionsTable(Account account, LocalDate anchor, int page, int size) {
        Page<Transaction> result = transactionRepository.findBalanceTransactions(account.getId(), anchor,
                PageRequest.of(page, size));
        List<Map<String, Object>> rows = result.getContent().stream().map(NetWorthAccountBreakdown::transactionRow).toList();
        return BreakdownTables.page(TRANSACTION_COLUMNS, rows, page, size, result.getTotalElements());
    }

    /**
     * The section ordered by one of its columns over every listed transaction, then paged. The
     * rows come from two flat queries (the transactions, their category names) with the same
     * WHERE as {@link #transactionsTable}, in its newest-first order, which the stable sort keeps
     * as the tiebreak.
     */
    private TableData sortedTransactionsTable(Account account, LocalDate anchor, SortClause sort, int page, int size) {
        BreakdownTables.requireColumn(TRANSACTION_COLUMNS, sort);
        Map<UUID, List<String>> categories = new HashMap<>();
        for (TransactionRepository.BalanceTransactionCategoryRow c
                : transactionRepository.findBalanceTransactionCategoryNames(account.getId(), anchor)) {
            categories.computeIfAbsent(c.getTransactionId(), k -> new ArrayList<>()).add(c.getName());
        }
        List<Map<String, Object>> rows = transactionRepository.findBalanceTransactionRows(account.getId(), anchor).stream()
                .map(t -> transactionRow(t.getId(), t.getDate(), t.getDescription(), t.getSourcedDescription(),
                        categories.getOrDefault(t.getId(), List.of()), t.getType(), t.getAmount(), t.getExcluded()))
                .toList();
        return BreakdownTables.sorted(TRANSACTION_COLUMNS, rows, sort, page, size);
    }

    private static Map<String, Object> transactionRow(Transaction t) {
        return transactionRow(t.getId(), t.getDate(), t.getDescription(), t.getSourcedDescription(),
                t.getCategories().stream().map(tc -> tc.getCategory().getName()).toList(),
                t.getType(), t.getAmount(), t.isTransactionExcluded());
    }

    /** Amount is signed as the balance counts it: credits positive, every other type negative. */
    private static Map<String, Object> transactionRow(UUID id, LocalDate date, String description,
                                                      String sourcedDescription, List<String> categoryNames,
                                                      TransactionType type, BigDecimal amount, boolean excluded) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id.toString());
        row.put("date", date);
        row.put("description", description != null ? description : sourcedDescription);
        row.put("category", categoryNames.stream().sorted().collect(Collectors.joining(", ")));
        row.put("amount", type == TransactionType.CREDIT ? amount : amount.negate());
        row.put("excluded", excluded);
        return row;
    }

    // ------------------------------------------------------------------ broker

    private RowBreakdownResponse broker(Account account, int size) {
        List<HoldingPosition> open = openPositions(account);
        BigDecimal cash = account.getBrokerDetails() != null && account.getBrokerDetails().getCashBalance() != null
                ? account.getBrokerDetails().getCashBalance()
                : BigDecimal.ZERO;
        BigDecimal holdings = open.stream().map(HoldingPosition::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add);

        BreakdownChain chain = new BreakdownChain(NetWorthPlacement.balance(account)).start("Cash balance", cash);
        if (!open.isEmpty()) {
            chain.term("Holdings at market value (" + open.size() + ")", holdings);
        }
        NetWorthPlacement placement = NetWorthPlacement.ofAccount(account);
        List<BreakdownStep> steps = chain.close("Balance", placement.value(), "broker account " + account.getId());

        BreakdownSectionData section = new BreakdownSectionData(HOLDINGS, "Holdings", "breakdown", "positions",
                BreakdownTables.slice(HOLDING_COLUMNS, holdingRows(open, overrides()), 0, size));
        return response(account, placement, "Balance", steps, List.of(section), List.of());
    }

    /** Holdings that add to the balance: those with a current value (open quantity). */
    private List<HoldingPosition> openPositions(Account account) {
        return holdingRepository.findByBrokerAccountId(account.getId()).stream()
                .map(investmentService::calculateHoldingPosition)
                .filter(Objects::nonNull)
                .filter(p -> p.currentValue() != null)
                .toList();
    }

    /** Largest value first, then by instrument name and holding id for a stable order. */
    /** The current user's instrument overrides (holding rows show their names). */
    private InstrumentOverrides overrides() {
        return InstrumentOverrides.orNone(investmentService.instrumentOverrides());
    }

    private static List<Map<String, Object>> holdingRows(List<HoldingPosition> positions, InstrumentOverrides ov) {
        return positions.stream()
                .sorted(Comparator.comparing(HoldingPosition::currentValue).reversed()
                        .thenComparing(p -> ov.name(p.holding().getInstrument()), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(p -> p.holding().getId()))
                .map(p -> holdingRow(p, ov))
                .toList();
    }

    private static Map<String, Object> holdingRow(HoldingPosition p, InstrumentOverrides ov) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.holding().getId().toString());
        row.put("instrument", ov.name(p.holding().getInstrument()));
        row.put("quantity", p.openQty());
        row.put("price", p.latestPrice());
        row.put("priceDate", p.priceAsOf());
        row.put("value", p.currentValue());
        row.put("valuation", p.latestPrice() == null ? "At cost (no price)" : null);
        return row;
    }

    /** The account type as the net worth Kind column labels it. */
    private static String kindLabel(AccountType type) {
        return type == null ? "Account" : NetWorthDatasource.kindLabel(type.name());
    }
}
