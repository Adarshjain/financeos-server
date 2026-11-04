package com.financeos.domain.statement.skip;

import com.financeos.api.account.dto.StatementSkipResponse;
import com.financeos.api.account.dto.StatementSkipsResponse;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycles;
import com.financeos.domain.notification.statement.StatementExpectedNotificationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "No statement this period": some banks send nothing for a cycle without transactions. The owner marks one projected
 * cycle of a card as having no statement; it then counts as covered everywhere ({@link SkippedPeriods}) and the next
 * cycle is checked as usual. Skips move only the projection's cursor, never the cycle shape (that stays derived from
 * real statements). A real statement imported for a skipped cycle wins: the skip is simply ignored by the max.
 */
@Service
public class StatementSkipService {

    private static final int MAX_CYCLES_FORWARD = 24;

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final StatementSkippedPeriodRepository skipRepository;

    public StatementSkipService(AccountRepository accountRepository, StatementRepository statementRepository,
                                StatementSkippedPeriodRepository skipRepository) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.skipRepository = skipRepository;
    }

    @Transactional
    public StatementSkipResponse skip(UUID accountId, LocalDate periodEnd) {
        Account account = ownedAccount(accountId);
        LocalDate today = AppTime.today();
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Only a credit card has statements to skip");
        }
        if (account.isClosed(today)) {
            throw new ValidationException("This card is closed");
        }
        if (periodEnd == null) {
            throw new ValidationException("periodEnd is required");
        }
        if (!periodEnd.isBefore(today)) {
            throw new ValidationException("This period has not ended yet");
        }
        StatementSkippedPeriod existing = skipRepository.findByAccountIdAndPeriodEnd(accountId, periodEnd).orElse(null);
        if (existing != null) {
            return StatementSkipResponse.from(existing);
        }
        List<Statement> statements = statementRepository.findQualifyingCreditCardStatements(accountId);
        LocalDate covered = covered(statements, accountId);
        if (covered == null) {
            throw new ValidationException("Import a statement first: this card has no statement history to project from");
        }
        if (!periodEnd.isAfter(covered)) {
            if (hasStatementCovering(statements, periodEnd)) {
                throw new DuplicateResourceException("This period already has a statement");
            }
            throw new ValidationException("That date is not the end of a billing cycle that is waiting for a statement");
        }
        BillingCycles cycles = BillingCycles.fromStatements(statements);
        for (BillingCycles.Cycle cycle : projectedAfter(cycles, covered)) {
            if (cycle.end().equals(periodEnd)) {
                StatementSkippedPeriod row = new StatementSkippedPeriod();
                row.setUser(account.getUser());
                row.setAccount(account);
                row.setPeriodStart(cycle.start());
                row.setPeriodEnd(cycle.end());
                return StatementSkipResponse.from(skipRepository.save(row));
            }
        }
        throw new ValidationException("That date is not the end of a billing cycle that is waiting for a statement");
    }

    @Transactional
    public void undo(UUID accountId, UUID skipId) {
        ownedAccount(accountId);
        StatementSkippedPeriod row = skipRepository.findByIdAndAccountId(skipId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("StatementSkip", skipId));
        skipRepository.delete(row);
    }

    @Transactional(readOnly = true)
    public StatementSkipsResponse state(UUID accountId) {
        Account account = ownedAccount(accountId);
        LocalDate today = AppTime.today();
        if (account.getType() != AccountType.credit_card) {
            return new StatementSkipsResponse(List.of(), null);
        }
        List<Statement> statements = statementRepository.findQualifyingCreditCardStatements(accountId);
        List<StatementSkipResponse> skipped = skipRepository.findByAccountIdOrderByPeriodEndDesc(accountId).stream()
                .filter(s -> !hasStatementCovering(statements, s.getPeriodEnd()))
                .map(StatementSkipResponse::from)
                .toList();
        StatementSkipsResponse.Missing missing = null;
        LocalDate covered = covered(statements, accountId);
        if (covered != null && !account.isClosed(today)) {
            for (BillingCycles.Cycle cycle : projectedAfter(BillingCycles.fromStatements(statements), covered)) {
                if (cycle.end().isBefore(today)) {
                    missing = new StatementSkipsResponse.Missing(cycle.start(), cycle.end(),
                            cycle.end().plusDays(StatementExpectedNotificationService.GRACE_DAYS));
                }
                break; // only the earliest cycle after the cursor matters
            }
        }
        return new StatementSkipsResponse(skipped, missing);
    }

    // ---------------------------------------------------------------- pure helpers

    /** Latest statement end advanced by the card's skips; null when there is no dated statement. */
    private LocalDate covered(List<Statement> statements, UUID accountId) {
        LocalDate latest = statements.stream().map(Statement::getPeriodEnd).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        return SkippedPeriods.coveredEnd(latest, SkippedPeriods.byAccount(skipRepository, List.of(accountId)).get(accountId));
    }

    private static boolean hasStatementCovering(List<Statement> statements, LocalDate date) {
        return statements.stream().anyMatch(s -> s.getPeriodEnd() != null
                && !s.getPeriodEnd().isBefore(date)
                && (s.getPeriodStart() == null ? s.getPeriodEnd().equals(date) : !s.getPeriodStart().isAfter(date)));
    }

    /** The projected cycles after {@code covered}, in order (bounded; a cycle that does not move forward ends the walk). */
    private static List<BillingCycles.Cycle> projectedAfter(BillingCycles cycles, LocalDate covered) {
        List<BillingCycles.Cycle> out = new java.util.ArrayList<>();
        LocalDate cursor = covered;
        for (int i = 0; i < MAX_CYCLES_FORWARD; i++) {
            BillingCycles.Cycle cycle = cycles.containing(cursor.plusDays(1));
            if (!cycle.end().isAfter(cursor)) {
                break;
            }
            out.add(cycle);
            cursor = cycle.end();
        }
        return out;
    }

    private Account ownedAccount(UUID accountId) {
        UUID userId = UserContext.getCurrentUserId();
        return accountRepository.findById(accountId)
                .filter(a -> a.getUser() != null && a.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }
}
