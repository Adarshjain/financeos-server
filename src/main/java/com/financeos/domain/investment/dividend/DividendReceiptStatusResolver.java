package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionRepository;
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

    public record Context(LocalDate today, LocalDate coverageEnd) {
    }

    private final TransactionRepository transactionRepository;

    public DividendReceiptStatusResolver(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public LocalDate coverageEnd() {
        return transactionRepository.findMaxDateByAccountType(AccountType.bank_account);
    }

    public Context context() {
        return new Context(AppTime.today(), coverageEnd());
    }

    public DividendReceiptStatus statusOf(Dividend dividend, Context context) {
        return DividendReceiptWindows.derive(dividend, context.today(), context.coverageEnd());
    }

    public DividendResponse toResponse(Dividend dividend) {
        return toResponse(dividend, context());
    }

    public DividendResponse toResponse(Dividend dividend, Context context) {
        return DividendResponse.from(dividend, context.today(), context.coverageEnd());
    }
}
