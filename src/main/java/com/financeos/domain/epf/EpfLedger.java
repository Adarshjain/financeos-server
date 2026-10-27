package com.financeos.domain.epf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure calculator over a user's EPF entries (no Spring, no I/O). Per employer the entries apply in
 * order of {@link #ledgerDate} (EPFO can post many months late in one go, so a contribution belongs to its due month, not its posting date), then {@link EpfEntryType} declaration order (opening balance, contribution,
 * transfer in, interest, transfer out, withdrawal), then creation time and id.
 *
 * <p>Employee and employer parts of the balance are tracked separately. An opening balance resets the
 * balance to its own amounts; contributions, interest and transfers in add; transfers out and EPF
 * withdrawals (partial, final settlement) subtract. A pension withdrawal and every {@code pensionAmount}
 * never touch the balance: pension money is not in the EPF account.
 */
public final class EpfLedger {

    /** Employee and employer parts of a balance. */
    public record Balance(BigDecimal employee, BigDecimal employer) {
        public static final Balance ZERO = new Balance(BigDecimal.ZERO, BigDecimal.ZERO);

        public BigDecimal total() {
            return employee.add(employer);
        }

        Balance plus(BigDecimal e, BigDecimal r) {
            return new Balance(employee.add(e), employer.add(r));
        }

        Balance minus(BigDecimal e, BigDecimal r) {
            return new Balance(employee.subtract(e), employer.subtract(r));
        }
    }

    /**
     * The date an entry takes its place in the ledger and its financial year: a contribution's due month
     * (wage month + 1, first day), otherwise {@code creditedOn}.
     */
    public static LocalDate ledgerDate(EpfEntry e) {
        if (e.getType() == EpfEntryType.CONTRIBUTION && e.getWageMonth() != null) {
            return e.getWageMonth().plusMonths(1);
        }
        return e.getCreditedOn();
    }

    static final Comparator<EpfEntry> ORDER = Comparator
            .comparing(EpfLedger::ledgerDate)
            .thenComparing(e -> e.getType().ordinal())
            .thenComparing(EpfEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(EpfEntry::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final List<EpfEntry> ordered;
    private final Map<UUID, List<EpfEntry>> byEmployer = new LinkedHashMap<>();
    private final Map<EpfEntry, Balance> afterEntry = new IdentityHashMap<>();
    private final Map<UUID, Balance> finalByEmployer = new HashMap<>();

    private EpfLedger(Collection<EpfEntry> entries) {
        this.ordered = new ArrayList<>(entries);
        this.ordered.sort(ORDER);
        for (EpfEntry entry : ordered) {
            byEmployer.computeIfAbsent(entry.getEmployer().getId(), k -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<UUID, List<EpfEntry>> group : byEmployer.entrySet()) {
            Balance balance = Balance.ZERO;
            for (EpfEntry entry : group.getValue()) {
                balance = apply(balance, entry);
                afterEntry.put(entry, balance);
            }
            finalByEmployer.put(group.getKey(), balance);
        }
    }

    public static EpfLedger of(Collection<EpfEntry> entries) {
        return new EpfLedger(entries);
    }

    /** One entry's effect on a balance. */
    static Balance apply(Balance balance, EpfEntry entry) {
        BigDecimal e = nz(entry.getEmployeeAmount());
        BigDecimal r = nz(entry.getEmployerAmount());
        return switch (entry.getType()) {
            case OPENING_BALANCE -> new Balance(e, r);
            case CONTRIBUTION, INTEREST, TRANSFER_IN -> balance.plus(e, r);
            case TRANSFER_OUT -> balance.minus(e, r);
            case WITHDRAWAL -> entry.getWithdrawalKind() == EpfWithdrawalKind.PENSION ? balance : balance.minus(e, r);
        };
    }

    /** Every entry in ledger order (oldest first). */
    public List<EpfEntry> ordered() {
        return List.copyOf(ordered);
    }

    /** One employer's entries in ledger order (oldest first). */
    public List<EpfEntry> ordered(UUID employerId) {
        return List.copyOf(byEmployer.getOrDefault(employerId, List.of()));
    }

    /** The employer's balance right after {@code entry} applied. */
    public Balance runningBalance(EpfEntry entry) {
        return afterEntry.getOrDefault(entry, Balance.ZERO);
    }

    public Balance balance(UUID employerId) {
        return finalByEmployer.getOrDefault(employerId, Balance.ZERO);
    }

    /** The balances of all employers added up. */
    public Balance total() {
        Balance total = Balance.ZERO;
        for (Balance b : finalByEmployer.values()) {
            total = total.plus(b.employee(), b.employer());
        }
        return total;
    }

    /** The employer's balance after every entry credited up to and including the last day of {@code month}. */
    public Balance balanceAtEndOfMonth(UUID employerId, YearMonth month) {
        LocalDate end = month.atEndOfMonth();
        Balance balance = Balance.ZERO;
        for (EpfEntry entry : byEmployer.getOrDefault(employerId, List.of())) {
            if (ledgerDate(entry).isAfter(end)) {
                break;
            }
            balance = apply(balance, entry);
        }
        return balance;
    }

    /** Employee plus employer part of a withdrawal; for a pension withdrawal the pension amount. */
    public static BigDecimal gross(EpfEntry withdrawal) {
        return withdrawal.getWithdrawalKind() == EpfWithdrawalKind.PENSION
                ? nz(withdrawal.getPensionAmount())
                : nz(withdrawal.getEmployeeAmount()).add(nz(withdrawal.getEmployerAmount()));
    }

    /** What reached the bank: gross minus TDS (no TDS recorded counts as none). */
    public static BigDecimal received(EpfEntry withdrawal) {
        return gross(withdrawal).subtract(nz(withdrawal.getTdsAmount()));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
