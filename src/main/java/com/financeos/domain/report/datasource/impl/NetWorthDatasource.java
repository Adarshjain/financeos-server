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
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * a whole because of one account.
 */
@Component
public class NetWorthDatasource implements ComputedReportDatasource {

    private static final Logger log = LoggerFactory.getLogger(NetWorthDatasource.class);

    /** '<domain>.<action>.<outcome>' event names (see com.financeos.core.observability.Events). */
    static final String EVENT_ROW_SKIPPED = com.financeos.core.observability.Events.REPORT_NET_WORTH_ROW_SKIPPED;
    static final String EVENT_SECTION_FAILED = com.financeos.core.observability.Events.REPORT_NET_WORTH_SECTION_FAILED;

    private static final List<ReportType> KPI_CHART_TABLE = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);

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
        LocalDate today = AppTime.today();
        List<Map<String, Object>> rows = new ArrayList<>();
        accountRows(today, rows);
        loanRows(today, rows);
        lendingRows(today, rows);
        return rows;
    }

    // ------------------------------------------------------------------ sections

    private void accountRows(LocalDate today, List<Map<String, Object>> rows) {
        List<Account> accounts = guarded("accounts", accountService::getAllAccounts);
        for (Account account : accounts) {
            try {
                Map<String, Object> row = accountRow(account, today);
                if (row != null) {
                    rows.add(row);
                }
            } catch (RuntimeException e) {
                skipped("account", account.getId() == null ? null : account.getId().toString(), e);
            }
        }
    }

    private Map<String, Object> accountRow(Account account, LocalDate today) {
        if (Boolean.TRUE.equals(account.getExcludeFromNetAsset())) {
            return null;
        }
        if (account.getClosedOn() != null && !account.getClosedOn().isAfter(today)) {
            return null; // closed on or before today
        }
        BigDecimal balance = account.getCalculatedBalance() != null ? account.getCalculatedBalance() : BigDecimal.ZERO;
        FinancialPosition side = account.getFinancialPosition();
        if (side == null) {
            side = account.getType() == AccountType.credit_card ? FinancialPosition.liability : FinancialPosition.asset;
        }
        BigDecimal value;
        if (balance.signum() < 0) {
            // Owed money: a card's bill, an overdrawn bank account, a negative generic balance.
            side = FinancialPosition.liability;
            value = balance.negate();
        } else if (balance.signum() > 0 && account.getType() == AccountType.credit_card) {
            side = FinancialPosition.asset; // overpaid card
            value = balance;
        } else {
            value = balance;
        }
        String kind = account.getType() != null ? account.getType().name() : AccountType.generic.name();
        return row(account.getId() == null ? null : account.getId().toString(), account.getName(), kind, side, value, today);
    }

    private void loanRows(LocalDate today, List<Map<String, Object>> rows) {
        List<LoanResponse> loans = guarded("loans",
                () -> loanService.getLoans(LoanStatus.active, Pageable.unpaged()).getContent());
        for (LoanResponse loan : loans) {
            try {
                BigDecimal outstanding = loan.outstandingPrincipal() != null ? loan.outstandingPrincipal() : BigDecimal.ZERO;
                rows.add(row(loan.id() == null ? null : loan.id().toString(), loan.name(), KIND_LOAN,
                        FinancialPosition.liability, outstanding.abs(), today));
            } catch (RuntimeException e) {
                skipped("loan", loan.id() == null ? null : loan.id().toString(), e);
            }
        }
    }

    private void lendingRows(LocalDate today, List<Map<String, Object>> rows) {
        List<CounterpartyResponse> counterparties = guarded("lendings",
                () -> lendingService.getCounterparties(null, Pageable.unpaged()).getContent());
        for (CounterpartyResponse cp : counterparties) {
            try {
                BigDecimal net = cp.netPosition();
                if (net == null || net.signum() == 0) {
                    continue;
                }
                FinancialPosition side = net.signum() > 0 ? FinancialPosition.asset : FinancialPosition.liability;
                rows.add(row(cp.id() == null ? null : cp.id().toString(), cp.name(), KIND_LENDING, side, net.abs(), today));
            } catch (RuntimeException e) {
                skipped("lending", cp.id() == null ? null : cp.id().toString(), e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> row(String id, String name, String kind, FinancialPosition side,
                                           BigDecimal value, LocalDate asOf) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("kind", kind);
        row.put("side", side.name());
        row.put("value", value);
        row.put("signedValue", side == FinancialPosition.asset ? value : value.negate());
        row.put("asOf", asOf);
        return row;
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
