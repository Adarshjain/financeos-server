package com.financeos.domain.dashboard;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the per-user {@link BuiltinAvailability.Facts} for one catalog request and evaluates each
 * entry's check against them. Every fact is an {@code exists} query scoped to the user, run lazily
 * and memoised, so listing the catalog costs at most one query per distinct fact any entry asks for.
 */
@Service
@Transactional(readOnly = true)
public class BuiltinAvailabilityService {

    private final AccountRepository accountRepository;
    private final LoanRepository loanRepository;
    private final HoldingRepository holdingRepository;
    @Nullable
    private final LendingRepository lendingRepository;

    /** Without lendings: {@code hasLending} is always false (unit tests of the other facts). */
    public BuiltinAvailabilityService(AccountRepository accountRepository, LoanRepository loanRepository,
                                      HoldingRepository holdingRepository) {
        this(accountRepository, loanRepository, holdingRepository, null);
    }

    @Autowired
    public BuiltinAvailabilityService(AccountRepository accountRepository, LoanRepository loanRepository,
                                      HoldingRepository holdingRepository,
                                      @Nullable LendingRepository lendingRepository) {
        this.accountRepository = accountRepository;
        this.loanRepository = loanRepository;
        this.holdingRepository = holdingRepository;
        this.lendingRepository = lendingRepository;
    }

    /** Lazily-evaluated facts about {@code userId}; valid for the current request only. */
    public BuiltinAvailability.Facts factsFor(UUID userId) {
        return new LazyFacts(userId);
    }

    /** The entry's unavailable reason for these facts (null = usable). */
    @Nullable
    public String unavailableReason(BuiltinWidgetRegistry.Entry entry, BuiltinAvailability.Facts facts) {
        BuiltinAvailability check = entry.availability();
        return check == null ? null : check.unavailableReason(facts);
    }

    private final class LazyFacts implements BuiltinAvailability.Facts {
        private final UUID userId;
        private final Map<AccountType, Boolean> byType = new EnumMap<>(AccountType.class);
        private Boolean anyAccount;
        private Boolean loan;
        private Boolean holdings;
        private Boolean lending;

        private LazyFacts(UUID userId) {
            this.userId = userId;
        }

        @Override
        public boolean hasAccountOfType(AccountType type) {
            return byType.computeIfAbsent(type, t -> accountRepository.existsByUser_IdAndType(userId, t));
        }

        @Override
        public boolean hasAnyAccount() {
            if (anyAccount == null) {
                anyAccount = accountRepository.existsByUser_Id(userId);
            }
            return anyAccount;
        }

        @Override
        public boolean hasLoan() {
            if (loan == null) {
                loan = loanRepository.existsByUser_Id(userId);
            }
            return loan;
        }

        @Override
        public boolean hasHoldings() {
            if (holdings == null) {
                holdings = holdingRepository.existsByUser_Id(userId);
            }
            return holdings;
        }

        @Override
        public boolean hasLending() {
            if (lending == null) {
                lending = lendingRepository != null && lendingRepository.existsByUser_Id(userId);
            }
            return lending;
        }
    }
}
