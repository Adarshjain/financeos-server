package com.financeos.domain.report.breakdown;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanEventResponse;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.loan.LoanEventType;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanService.LoanScheduleDetail;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Breakdown of an active loan's row of net worth: its outstanding principal as the schedule
 * computes it, i.e. the closing balance of the last EMI due by today (the principal when none is
 * due yet, zero once foreclosed). Read as the principal minus the principal part of every EMI due so
 * far, minus the prepayments the schedule folded in before that EMI (a prepayment counts from the
 * first EMI due after it). Payments recorded against EMIs do not enter the figure.
 */
@Component
@Transactional(readOnly = true)
class NetWorthLoanBreakdown implements NetWorthItemBreakdown {

    static final String INSTALLMENTS = "installments";
    static final String PREPAYMENTS = "prepayments";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<TableData.Column> INSTALLMENT_COLUMNS = List.of(
            new TableData.Column("seq", "EMI #", "number", "number"),
            new TableData.Column("dueDate", "Due date", "date", null),
            new TableData.Column("emi", "EMI", "number", "currency"),
            new TableData.Column("interest", "Interest", "number", "currency"),
            new TableData.Column("principal", "Principal", "number", "currency"),
            new TableData.Column("closingBalance", "Closing balance", "number", "currency"));

    private static final List<TableData.Column> PREPAYMENT_COLUMNS = List.of(
            new TableData.Column("date", "Date", "date", null),
            new TableData.Column("amount", "Amount", "number", "currency"),
            new TableData.Column("counted", "Counted", "boolean", null));

    private final LoanService loanService;

    NetWorthLoanBreakdown(LoanService loanService) {
        this.loanService = loanService;
    }

    @Override
    public Optional<RowBreakdownResponse> breakdown(UUID id, int size) {
        return activeLoan(id).map(detail -> breakdown(new LoanView(detail, AppTime.today()), size));
    }

    @Override
    public Optional<ReportData> section(UUID id, String section, int page, int size) {
        return section(id, section, page, size, null);
    }

    @Override
    public Optional<ReportData> section(UUID id, String section, int page, int size, @Nullable SortClause sort) {
        return activeLoan(id).map(detail -> {
            LoanView view = new LoanView(detail, AppTime.today());
            if (INSTALLMENTS.equals(section)) {
                return BreakdownTables.sorted(INSTALLMENT_COLUMNS, installmentRows(view), sort, page, size);
            }
            if (PREPAYMENTS.equals(section) && !view.prepayments().isEmpty()) {
                return BreakdownTables.sorted(PREPAYMENT_COLUMNS, prepaymentRows(view), sort, page, size);
            }
            throw new ResourceNotFoundException("Breakdown section", section);
        });
    }

    /** The user's loan when it is a row of net worth (only active loans are). */
    private Optional<LoanScheduleDetail> activeLoan(UUID id) {
        return loanService.findOwnedLoanSchedule(id).filter(detail -> detail.loan().status() == LoanStatus.active);
    }

    private RowBreakdownResponse breakdown(LoanView view, int size) {
        LoanResponse loan = view.detail().loan();
        BreakdownChain chain = new BreakdownChain(NetWorthPlacement.outstanding(loan))
                .start("Loan principal", loan.principal());
        if (!view.due().isEmpty()) {
            BigDecimal repaid = view.due().stream().map(InstallmentDto::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
            chain.term("Principal repaid through " + view.due().size() + " EMIs due so far", repaid.negate());
        }
        List<LoanEventResponse> counted = view.prepayments().stream().filter(view::counted).toList();
        if (!counted.isEmpty()) {
            chain.term("Prepayments (" + counted.size() + ")", sum(counted).negate());
        }

        String totalLabel;
        List<String> notes = new ArrayList<>();
        LoanEventResponse foreclosure = view.foreclosure();
        if (foreclosure != null) {
            // What the foreclosure paid off: the last due EMI's closing balance (the principal when
            // none was due) less the prepayments made after it, which the schedule applied first.
            BigDecimal base = view.lastDue() != null ? view.lastDue().closingBalance() : loan.principal();
            List<LoanEventResponse> later = view.prepayments().stream().filter(p -> !view.reflected(p)).toList();
            chain.term("Principal cleared at foreclosure", base.subtract(sum(later)).negate())
                    .info("Foreclosed on " + DATE.format(foreclosure.effectiveDate()), null, foreclosure.amount(),
                            BreakdownChain.CURRENCY);
            totalLabel = "Outstanding";
        } else {
            InstallmentDto last = view.lastDue();
            totalLabel = last != null
                    ? "Outstanding after EMI #" + last.seq() + " (" + DATE.format(last.dueDate()) + ")"
                    : "Outstanding";
            if (view.prepayments().stream().anyMatch(p -> !view.counted(p))) {
                notes.add("Prepayments made after the last EMI due so far count from the next EMI.");
            }
        }
        NetWorthPlacement placement = NetWorthPlacement.ofLoan(loan);
        List<BreakdownStep> steps = chain.close(totalLabel, placement.value(), "loan " + loan.id());

        List<BreakdownSectionData> sections = new ArrayList<>();
        sections.add(new BreakdownSectionData(INSTALLMENTS, "EMIs due so far", null, null,
                BreakdownTables.slice(INSTALLMENT_COLUMNS, installmentRows(view), 0, size)));
        if (!view.prepayments().isEmpty()) {
            sections.add(new BreakdownSectionData(PREPAYMENTS, "Prepayments", null, null,
                    BreakdownTables.slice(PREPAYMENT_COLUMNS, prepaymentRows(view), 0, size)));
        }
        return NetWorthBreakdownProvider.response(loan.id(), loan.name(),
                NetWorthDatasource.kindLabel(NetWorthDatasource.KIND_LOAN), placement, totalLabel, steps,
                sections, notes);
    }

    /** Newest EMI first. */
    private static List<Map<String, Object>> installmentRows(LoanView view) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = view.due().size() - 1; i >= 0; i--) {
            InstallmentDto inst = view.due().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(inst.seq()));
            row.put("seq", inst.seq());
            row.put("dueDate", inst.dueDate());
            row.put("emi", inst.emi());
            row.put("interest", inst.interest());
            row.put("principal", inst.principal());
            row.put("closingBalance", inst.closingBalance());
            rows.add(row);
        }
        return rows;
    }

    /** Newest first; {@code counted} says whether the outstanding already reflects it. */
    private static List<Map<String, Object>> prepaymentRows(LoanView view) {
        return view.prepayments().stream()
                .sorted(Comparator.comparing(LoanEventResponse::effectiveDate)
                        .thenComparing(LoanEventResponse::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .map(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", p.id().toString());
                    row.put("date", p.effectiveDate());
                    row.put("amount", p.amount());
                    row.put("counted", view.counted(p));
                    return row;
                })
                .toList();
    }

    private static BigDecimal sum(List<LoanEventResponse> events) {
        return events.stream().map(LoanEventResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The loan's schedule as of a day: the EMIs due by then (schedule order), the prepayment
     * events, and the foreclosure that has taken effect, if any.
     */
    private static final class LoanView {

        private final LoanScheduleDetail detail;
        private final List<InstallmentDto> due;
        private final InstallmentDto lastDue;
        private final List<LoanEventResponse> prepayments;
        private final LoanEventResponse foreclosure;

        LoanView(LoanScheduleDetail detail, LocalDate today) {
            this.detail = detail;
            this.due = detail.installments().stream().filter(i -> !i.dueDate().isAfter(today)).toList();
            this.lastDue = due.isEmpty() ? null : due.get(due.size() - 1);
            this.prepayments = detail.events().stream().filter(e -> e.eventType() == LoanEventType.prepayment).toList();
            this.foreclosure = detail.events().stream()
                    .filter(e -> e.eventType() == LoanEventType.foreclosure && !e.effectiveDate().isAfter(today))
                    .findFirst()
                    .orElse(null);
        }

        LoanScheduleDetail detail() {
            return detail;
        }

        List<InstallmentDto> due() {
            return due;
        }

        InstallmentDto lastDue() {
            return lastDue;
        }

        List<LoanEventResponse> prepayments() {
            return prepayments;
        }

        LoanEventResponse foreclosure() {
            return foreclosure;
        }

        /**
         * Whether the last due EMI's closing balance reflects the prepayment: the schedule folds an
         * event into the first EMI due after it.
         */
        boolean reflected(LoanEventResponse prepayment) {
            return lastDue != null && prepayment.effectiveDate().isBefore(lastDue.dueDate());
        }

        /**
         * Whether the prepayment is part of the outstanding today: once reflected by a due EMI, or
         * always after a foreclosure (no event may follow one, so the schedule applied them all).
         */
        boolean counted(LoanEventResponse prepayment) {
            return foreclosure != null || reflected(prepayment);
        }
    }
}
