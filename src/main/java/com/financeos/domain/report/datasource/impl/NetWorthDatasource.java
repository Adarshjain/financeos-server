package com.financeos.domain.report.datasource.impl;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.core.time.AppTime;
import com.financeos.core.tx.SectionRunner;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.underlying.UnderlyingExcludedItem;
import com.financeos.domain.report.underlying.UnderlyingExtras;
import com.financeos.domain.report.underlying.UnderlyingSummaryLine;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Computed report datasource: one row per thing that counts towards net worth, as of today.
 *
 * <ul>
 *   <li>Every open account not excluded from net assets: its calculated balance (brokers:
 *       holdings at market plus cash). Side comes from the account's financial position when
 *       set; otherwise a credit card is a liability and everything else an asset. A negative
 *       balance flips the side to liability and is shown as a positive value; a positive credit
 *       card balance is an asset (overpaid).</li>
 *   <li>Every active loan: its outstanding principal, a liability.</li>
 *   <li>Every counterparty with a non-zero net position: they owe you (asset) or you owe
 *       them (liability).</li>
 * </ul>
 * {@code signedValue} is positive for assets and negative for liabilities, so its sum is the
 * net worth. A row that fails to compute is logged and skipped; the datasource never fails as
 * a whole because of one account. The side/value rules live in {@link NetWorthPlacement}, shared
 * with the net worth row breakdown.
 *
 * <p>KPI underlying data lists name, kind and side, assets first then the largest values, grouped
 * by side, with "Assets"/"Liabilities" totals and the accounts left out ({@link #notCounted()}).
 */
@Component
public class NetWorthDatasource implements ComputedReportDatasource, UnderlyingExtras {

    private static final Logger log = LoggerFactory.getLogger(NetWorthDatasource.class);

    /** '<domain>.<action>.<outcome>' event names (see com.financeos.core.observability.Events). */
    static final String EVENT_ROW_SKIPPED = com.financeos.core.observability.Events.REPORT_NET_WORTH_ROW_SKIPPED;
    static final String EVENT_SECTION_FAILED = com.financeos.core.observability.Events.REPORT_NET_WORTH_SECTION_FAILED;

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

    /** Reason code of a row that failed to compute (see {@link #notCounted()}). */
    private static final String REASON_ERROR = "error";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static final String KIND_LOAN = "loan";
    public static final String KIND_LENDING = "lending";
    static final List<String> KIND_VALUES = List.of(
            AccountType.bank_account.name(), AccountType.credit_card.name(), AccountType.broker.name(),
            AccountType.generic.name(), KIND_LOAN, KIND_LENDING);
    static final List<String> SIDE_VALUES = List.of(FinancialPosition.asset.name(), FinancialPosition.liability.name());

    private final AccountService accountService;
    private final LoanService loanService;
    private final LendingService lendingService;
    private final SectionRunner sectionRunner;
    private final List<FieldDef> fields;

    @Autowired
    public NetWorthDatasource(AccountService accountService, LoanService loanService, LendingService lendingService,
                              SectionRunner sectionRunner) {
        this.accountService = accountService;
        this.loanService = loanService;
        this.lendingService = lendingService;
        this.sectionRunner = sectionRunner;
        this.fields = buildCatalog();
    }

    /** Runs sections inline, without their own transactions (plain unit tests). */
    public NetWorthDatasource(AccountService accountService, LoanService loanService, LendingService lendingService) {
        this(accountService, loanService, lendingService, SectionRunner.DIRECT);
    }

    @Override
    public String name() {
        return "net_worth";
    }

    @Override
    public String label() {
        return "Net worth";
    }

    @Override
    public List<FieldDef> fields() {
        return fields;
    }

    @Override
    public List<Map<String, Object>> rows() {
        return snapshot().rows();
    }

    // ------------------------------------------------------------------ KPI underlying data

    @Override
    public List<String> underlyingColumns() {
        return List.of("name", "kind", "side");
    }

    @Override
    public List<SortClause> underlyingDefaultSort() {
        return List.of(new SortClause("side", SortDirection.ASC), new SortClause("value", SortDirection.DESC));
    }

    @Override
    public String underlyingGroupField() {
        return "side";
    }

    /** "Assets" and "Liabilities": the {@code value} totals of the listed rows on each side. */
    @Override
    public List<UnderlyingSummaryLine> summaryLines(List<Map<String, Object>> listedRows) {
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        for (Map<String, Object> row : listedRows) {
            BigDecimal value = decimal(row.get("value"));
            if (value == null) {
                continue;
            }
            if (FinancialPosition.asset.name().equals(row.get("side"))) {
                assets = assets.add(value);
            } else if (FinancialPosition.liability.name().equals(row.get("side"))) {
                liabilities = liabilities.add(value);
            }
        }
        return List.of(new UnderlyingSummaryLine("Assets", assets, "currency"),
                new UnderlyingSummaryLine("Liabilities", liabilities, "currency"));
    }

    /**
     * Accounts left out on purpose (excluded from net assets, closed) and every account, loan or
     * counterparty whose row failed to compute. A whole failed section has no items to name.
     */
    @Override
    public List<UnderlyingExcludedItem> notCounted() {
        return snapshot().notCounted();
    }

    // ------------------------------------------------------------------ sections

    /** One pass over every section, yielding the rows and what was left out of them. */
    private Snapshot snapshot() {
        LocalDate today = AppTime.today();
        Snapshot snapshot = new Snapshot(new ArrayList<>(), new ArrayList<>());
        accountRows(today, snapshot);
        loanRows(today, snapshot);
        lendingRows(today, snapshot);
        return snapshot;
    }

    private record Snapshot(List<Map<String, Object>> rows, List<UnderlyingExcludedItem> notCounted) {
    }

    private void accountRows(LocalDate today, Snapshot snapshot) {
        List<Account> accounts = guarded("accounts", accountService::getAllAccounts);
        for (Account account : accounts) {
            String id = id(account.getId());
            try {
                NetWorthPlacement.Omission omission = NetWorthPlacement.omission(account, today);
                if (omission != null) {
                    snapshot.notCounted().add(new UnderlyingExcludedItem(id, account.getName(), NetWorthPlacement.kind(account),
                            omission.reason(), omissionLabel(omission, account), account.getCalculatedBalance()));
                    continue;
                }
                snapshot.rows().add(row(id, account.getName(), NetWorthPlacement.kind(account),
                        NetWorthPlacement.ofAccount(account), today));
            } catch (RuntimeException e) {
                skipped("account", id, e);
                snapshot.notCounted().add(failed(id, account.getName(), NetWorthPlacement.kind(account)));
            }
        }
    }

    private void loanRows(LocalDate today, Snapshot snapshot) {
        List<LoanResponse> loans = guarded("loans",
                () -> loanService.getLoans(LoanStatus.active, Pageable.unpaged()).getContent());
        for (LoanResponse loan : loans) {
            String id = id(loan.id());
            try {
                snapshot.rows().add(row(id, loan.name(), KIND_LOAN, NetWorthPlacement.ofLoan(loan), today));
            } catch (RuntimeException e) {
                skipped("loan", id, e);
                snapshot.notCounted().add(failed(id, loan.name(), KIND_LOAN));
            }
        }
    }

    private void lendingRows(LocalDate today, Snapshot snapshot) {
        List<CounterpartyResponse> counterparties = guarded("lendings",
                () -> lendingService.getCounterparties(null, Pageable.unpaged()).getContent());
        for (CounterpartyResponse cp : counterparties) {
            String id = id(cp.id());
            try {
                NetWorthPlacement placement = NetWorthPlacement.ofLending(cp);
                if (placement != null) {
                    snapshot.rows().add(row(id, cp.name(), KIND_LENDING, placement, today));
                }
            } catch (RuntimeException e) {
                skipped("lending", id, e);
                snapshot.notCounted().add(failed(id, cp.name(), KIND_LENDING));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> row(String id, String name, String kind, NetWorthPlacement placement,
                                           LocalDate asOf) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("kind", kind);
        row.put("side", placement.side().name());
        row.put("value", placement.value());
        row.put("signedValue", placement.signedValue());
        row.put("asOf", asOf);
        return row;
    }

    private static String id(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String omissionLabel(NetWorthPlacement.Omission omission, Account account) {
        return switch (omission) {
            case EXCLUDED -> "Excluded from net worth";
            case CLOSED -> "Closed on " + DATE.format(account.getClosedOn());
        };
    }

    private static UnderlyingExcludedItem failed(String id, String name, String kind) {
        return new UnderlyingExcludedItem(id, name, kind, REASON_ERROR, "Couldn't be calculated", null);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        return value instanceof Number n ? new BigDecimal(n.toString()) : null;
    }

    /**
     * Runs one section's load in its own transaction ({@link SectionRunner}); a failure rolls back only
     * that section, logs, and yields no rows rather than failing the datasource.
     */
    private <T> List<T> guarded(String section, Supplier<List<T>> loader) {
        try {
            List<T> out = sectionRunner.run(loader);
            return out != null ? out : List.of();
        } catch (RuntimeException e) {
            log.warn("Net worth section {} failed: {}", section, e.getMessage(),
                    StructuredArguments.keyValue("event", EVENT_SECTION_FAILED),
                    StructuredArguments.keyValue("section", section), e);
            return List.of();
        }
    }

    private static void skipped(String kind, String id, RuntimeException e) {
        log.warn("Net worth row skipped: kind={}, id={}: {}", kind, id, e.getMessage(),
                StructuredArguments.keyValue("event", EVENT_ROW_SKIPPED),
                StructuredArguments.keyValue("kind", kind),
                StructuredArguments.keyValue("id", String.valueOf(id)), e);
    }

    private static List<FieldDef> buildCatalog() {
        return List.of(
                new FieldDef("id", "ID", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY).notFilterable(),
                new FieldDef("name", "Name", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
                new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, KIND_VALUES, null, CHART_TABLE),
                new FieldDef("side", "Side", FieldType.ENUM, FieldRole.DIMENSION, null, SIDE_VALUES, null, CHART_TABLE),
                new FieldDef("value", "Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM), null, null,
                        KPI_CHART_TABLE, "currency"),
                new FieldDef("signedValue", "Net value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM), null,
                        null, KPI_CHART_TABLE, "currency"),
                new FieldDef("asOf", "As of", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE)
        );
    }
}
