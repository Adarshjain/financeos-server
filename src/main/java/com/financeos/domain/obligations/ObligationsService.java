package com.financeos.domain.obligations;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.api.obligations.dto.ObligationsResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycles;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Everything the user has to pay or act on soon, in one list: loan EMIs, lending returns,
 * credit-card bills and statements that should have arrived (or are about to). Each item
 * carries a stable reference, a display title, a deep link and a status:
 * {@code overdue} (date passed), {@code due_soon} (within 7 days) or {@code upcoming}.
 *
 * <p>Overdue items come first (oldest first), then everything else by date; items without a
 * date (a bill whose due date the parser missed) go last.
 */
@Service
@Transactional(readOnly = true)
public class ObligationsService {

    private static final Logger log = LoggerFactory.getLogger(ObligationsService.class);

    public static final String KIND_EMI = "emi";
    public static final String KIND_LENDING_DUE = "lending_due";
    public static final String KIND_CARD_BILL = "card_bill";
    public static final String KIND_STATEMENT_EXPECTED = "statement_expected";
    /** Every kind, in display order. */
    public static final List<String> KINDS = List.of(KIND_EMI, KIND_LENDING_DUE, KIND_CARD_BILL, KIND_STATEMENT_EXPECTED);
    public static final Set<String> ALL_KINDS = Set.copyOf(KINDS);

    public static final String STATUS_OVERDUE = "overdue";
    public static final String STATUS_DUE_SOON = "due_soon";
    public static final String STATUS_UPCOMING = "upcoming";

    public static final int MIN_MONTHS = 1;
    public static final int MAX_MONTHS = 12;
    public static final int DEFAULT_MONTHS = 3;

    /** "Due soon" = due within this many days (0..7). */
    static final int DUE_SOON_DAYS = 7;
    /** A statement is only "overdue" this many days after the cycle it covers closed. */
    static final int STATEMENT_GRACE_DAYS = 5;

    /** Bill statuses that still want a payment (or the details to know whether one is due). */
    private static final Set<BillStatus> BILL_STATUSES =
            EnumSet.of(BillStatus.OPEN, BillStatus.PARTIAL, BillStatus.OVERDUE, BillStatus.DUE_UNKNOWN);

    /** Overdue first (oldest first), then by date ascending; undated items last. */
    private static final Comparator<ObligationItemDto> ORDER = Comparator
            .comparingInt((ObligationItemDto i) -> STATUS_OVERDUE.equals(i.status()) ? 0 : 1)
            .thenComparing(ObligationItemDto::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ObligationItemDto::type, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ObligationItemDto::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private final LoanService loanService;
    private final LendingService lendingService;
    private final CardBillService cardBillService;
    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;

    public ObligationsService(LoanService loanService,
                              LendingService lendingService,
                              CardBillService cardBillService,
                              AccountRepository accountRepository,
                              StatementRepository statementRepository) {
        this.loanService = loanService;
        this.lendingService = lendingService;
        this.cardBillService = cardBillService;
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
    }

    /**
     * The user's obligations due within {@code months} (clamped to 1..12) plus everything
     * already overdue, limited to {@code kinds} (null or empty = all).
     */
    public ObligationsResponse upcoming(UUID userId, int months, @Nullable Set<String> kinds) {
        int windowMonths = clampMonths(months);
        Set<String> wanted = kinds == null || kinds.isEmpty() ? ALL_KINDS : kinds;
        LocalDate today = AppTime.today();
        LocalDate maxDate = today.plusMonths(windowMonths);

        List<ObligationItemDto> items = new ArrayList<>();
        if (wanted.contains(KIND_EMI)) {
            items.addAll(section(KIND_EMI, () -> emis(today, maxDate)));
        }
        if (wanted.contains(KIND_LENDING_DUE)) {
            items.addAll(section(KIND_LENDING_DUE, () -> lendingDues(today, maxDate)));
        }
        if (userId != null) {
            if (wanted.contains(KIND_CARD_BILL)) {
                items.addAll(section(KIND_CARD_BILL, () -> cardBills(userId, today, maxDate)));
            }
            if (wanted.contains(KIND_STATEMENT_EXPECTED)) {
                items.addAll(section(KIND_STATEMENT_EXPECTED, () -> statementsExpected(userId, today, maxDate)));
            }
        }
        items.sort(ORDER);
        return new ObligationsResponse(items);
    }

    /**
     * One kind's rows, or none if computing them fails: a broken section (say a malformed loan schedule)
     * must not take the whole Upcoming list or widget down. A failure inside another transactional
     * service can still mark the shared transaction rollback-only, so this degrades only failures that
     * happen in this service's own code paths.
     */
    private static List<ObligationItemDto> section(String kind, Supplier<List<ObligationItemDto>> rows) {
        try {
            return rows.get();
        } catch (RuntimeException e) {
            log.warn("Obligations section failed: kind={}", kind, e,
                    StructuredArguments.keyValue("event", Events.OBLIGATIONS_SECTION_FAILED),
                    StructuredArguments.keyValue("kind", kind));
            return List.of();
        }
    }

    public static int clampMonths(int months) {
        return Math.max(MIN_MONTHS, Math.min(MAX_MONTHS, months));
    }

    /** Parses the {@code kinds} CSV query parameter; blank = all; an unknown kind is a 400. */
    public static Set<String> parseKinds(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return ALL_KINDS;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String kind = raw.trim().toLowerCase(Locale.ROOT);
            if (kind.isEmpty()) {
                continue;
            }
            if (!ALL_KINDS.contains(kind)) {
                throw new ValidationException("Unknown obligation kind: " + raw.trim());
            }
            out.add(kind);
        }
        return out.isEmpty() ? ALL_KINDS : out;
    }

    // ------------------------------------------------------------------ kinds

    /** Unsettled installments of active loans, due by {@code maxDate} or already past. */
    private List<ObligationItemDto> emis(LocalDate today, LocalDate maxDate) {
        List<ObligationItemDto> items = new ArrayList<>();
        List<LoanResponse> activeLoans = loanService.getLoans(LoanStatus.active, Pageable.unpaged()).getContent();
        for (LoanResponse loan : activeLoans) {
            List<InstallmentDto> installments = loanService.getLoanSchedule(loan.id());
            for (InstallmentDto inst : installments) {
                if ("settled".equals(inst.status())) {
                    continue;
                }
                LocalDate due = inst.dueDate();
                if (due == null || due.isAfter(maxDate)) {
                    continue;
                }
                String seq = inst.seq() != null ? " #" + inst.seq() : "";
                String href = "/loans/" + loan.id() + (inst.seq() != null ? "?installment=" + inst.seq() : "");
                items.add(new ObligationItemDto(
                        KIND_EMI, due, inst.emi(), status(today, due, 0),
                        loan.id(), loan.name(), inst.seq(),
                        null, null, null, null,
                        loan.name() + " EMI" + seq, null, loan.name(), null, href, daysUntil(today, due)));
            }
        }
        return items;
    }

    /** One item per counterparty with a net balance and an expected return date in the window (existing logic). */
    private List<ObligationItemDto> lendingDues(LocalDate today, LocalDate maxDate) {
        List<ObligationItemDto> items = new ArrayList<>();
        for (ObligationItemDto raw : lendingService.getUpcomingLendingObligations(today, maxDate)) {
            LocalDate date = raw.date();
            String name = raw.counterpartyName();
            String title = raw.direction() == LendingDirection.borrowed ? "You owe " + name : name + " owes you";
            String href = raw.counterpartyId() != null ? "/loans/lendings/" + raw.counterpartyId() : "/loans/lendings";
            items.add(new ObligationItemDto(
                    KIND_LENDING_DUE, date, raw.amount(), status(today, date, 0),
                    null, null, null,
                    raw.lendingId(), raw.counterpartyId(), name, raw.direction(),
                    title, null, name, null, href, daysUntil(today, date)));
        }
        return items;
    }

    /** Card bills still wanting a payment: open, partly paid, overdue, or missing their due details. */
    private List<ObligationItemDto> cardBills(UUID userId, LocalDate today, LocalDate maxDate) {
        List<ObligationItemDto> items = new ArrayList<>();
        for (CardBill bill : cardBillService.listBills(userId)) {
            if (bill.status() == null || !BILL_STATUSES.contains(bill.status()) || bill.statementId() == null) {
                continue;
            }
            LocalDate due = bill.paymentDueDate(); // null when the parser missed it (DUE_UNKNOWN)
            if (due != null && due.isAfter(maxDate)) {
                continue;
            }
            BigDecimal amount = bill.remainingAmount() != null ? bill.remainingAmount() : bill.totalAmountDue();
            String last4 = bill.last4() != null && !bill.last4().isBlank() ? " ••" + bill.last4() : "";
            items.add(new ObligationItemDto(
                    KIND_CARD_BILL, due, amount, status(today, due, 0),
                    null, null, null, null, null, null, null,
                    bill.accountName() + last4 + " bill", bill.accountId(), bill.accountName(), bill.statementId(),
                    "/upcoming?bill=" + bill.statementId(), daysUntil(today, due)));
        }
        return items;
    }

    /**
     * For every open credit card with statements: the day its next statement cycle closes (the
     * statement is expected right after). Overdue only once the grace period has passed.
     */
    private List<ObligationItemDto> statementsExpected(UUID userId, LocalDate today, LocalDate maxDate) {
        List<ObligationItemDto> items = new ArrayList<>();
        for (Account account : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (account.getClosedOn() != null && !account.getClosedOn().isAfter(today)) {
                continue;
            }
            List<Statement> statements = statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId());
            BillingCycles cycles = BillingCycles.fromStatements(statements);
            if (!cycles.hasStatements()) {
                continue;
            }
            LocalDate latestPeriodEnd = latestPeriodEnd(statements);
            if (latestPeriodEnd == null) {
                continue;
            }
            LocalDate expectedOn = cycles.containing(latestPeriodEnd.plusDays(1)).end();
            if (expectedOn.isAfter(maxDate)) {
                continue;
            }
            items.add(new ObligationItemDto(
                    KIND_STATEMENT_EXPECTED, expectedOn, null, status(today, expectedOn, STATEMENT_GRACE_DAYS),
                    null, null, null, null, null, null, null,
                    account.getName() + " statement expected", account.getId(), account.getName(), null,
                    "/transactions/import", daysUntil(today, expectedOn)));
        }
        return items;
    }

    /** The latest period end among the statements {@link BillingCycles} counts (not rejected, both dates set). */
    @Nullable
    private static LocalDate latestPeriodEnd(List<Statement> statements) {
        LocalDate latest = null;
        for (Statement s : statements) {
            if (s.getVerdict() == StatementVerdict.REJECTED || s.getPeriodStart() == null || s.getPeriodEnd() == null
                    || s.getPeriodEnd().isBefore(s.getPeriodStart())) {
                continue;
            }
            if (latest == null || s.getPeriodEnd().isAfter(latest)) {
                latest = s.getPeriodEnd();
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ status

    /**
     * {@code overdue} once the date is more than {@code graceDays} in the past, {@code due_soon}
     * from then up to 7 days ahead, else {@code upcoming}; an unknown date is {@code upcoming}.
     */
    static String status(LocalDate today, @Nullable LocalDate date, int graceDays) {
        if (date == null) {
            return STATUS_UPCOMING;
        }
        long days = ChronoUnit.DAYS.between(today, date);
        if (days < -graceDays) {
            return STATUS_OVERDUE;
        }
        if (days <= DUE_SOON_DAYS) {
            return STATUS_DUE_SOON;
        }
        return STATUS_UPCOMING;
    }

    @Nullable
    static Long daysUntil(LocalDate today, @Nullable LocalDate date) {
        return date == null ? null : ChronoUnit.DAYS.between(today, date);
    }
}
