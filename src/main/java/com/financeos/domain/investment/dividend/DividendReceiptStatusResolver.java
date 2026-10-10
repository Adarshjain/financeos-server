package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.transaction.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Resolves the derived receipt status for dividend responses. "Coverage" is how far the user's
 * tracked bank data reaches (latest transaction date on any bank account): an unresolved dividend
 * whose window ends after that is <em>unverifiable</em>, not overdue — a statement that has not
 * been imported yet is not a missing payout.
 */
@Component
public class DividendReceiptStatusResolver {

    /** One read's settings: the day, the bank-data coverage, and the reader's instrument overrides. */
    public record Context(LocalDate today, LocalDate coverageEnd, InstrumentOverrides overrides) {
        public Context(LocalDate today, LocalDate coverageEnd) {
            this(today, coverageEnd, InstrumentOverrides.NONE);
        }
    }

    private final TransactionRepository transactionRepository;
    /** The reader's instrument overrides; null in unit tests (then nobody has any). */
    @Nullable
    private final AssetClassOverrideService overrideService;

    /** Without instrument overrides (unit tests). */
    public DividendReceiptStatusResolver(TransactionRepository transactionRepository) {
        this(transactionRepository, null);
    }

    @Autowired
    public DividendReceiptStatusResolver(TransactionRepository transactionRepository,
                                         @Nullable AssetClassOverrideService overrideService) {
        this.transactionRepository = transactionRepository;
        this.overrideService = overrideService;
    }

    /** The current user's instrument overrides ({@link InstrumentOverrides#NONE} without the service). */
    public InstrumentOverrides overrides() {
        return overrideService == null
                ? InstrumentOverrides.NONE : InstrumentOverrides.orNone(overrideService.overridesForCurrentUser());
    }

    public LocalDate coverageEnd() {
        return transactionRepository.findMaxDateByAccountType(AccountType.bank_account);
    }

    public Context context() {
        return new Context(AppTime.today(), coverageEnd(), overrides());
    }

    public DividendReceiptStatus statusOf(Dividend dividend, Context context) {
        return DividendReceiptWindows.derive(dividend, context.today(), context.coverageEnd());
    }

    public DividendResponse toResponse(Dividend dividend) {
        return toResponse(dividend, context());
    }

    public DividendResponse toResponse(Dividend dividend, Context context) {
        return DividendResponse.from(dividend, context.today(), context.coverageEnd(), context.overrides());
    }
}
