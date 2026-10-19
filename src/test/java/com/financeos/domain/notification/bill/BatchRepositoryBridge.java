package com.financeos.domain.notification.bill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.CardUtilization;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepositoryCustom;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bills list reads its inputs through batch repository methods; these tests stub the per-card ones.
 * This answers each batch method from the per-card stubs, so a test states its card's data once.
 */
final class BatchRepositoryBridge {

    private BatchRepositoryBridge() {
    }

    @SuppressWarnings("unchecked")
    static void install(StatementRepository statements, TransactionRepository transactions, AccountService accountService) {
        lenient().when(statements.findQualifyingCreditCardStatementsIn(any())).thenAnswer(inv -> {
            List<Statement> all = new ArrayList<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                List<Statement> rows = statements.findQualifyingCreditCardStatements(id);
                if (rows != null) {
                    all.addAll(rows);
                }
            }
            return all;
        });
        lenient().when(statements.findByAccountIdInOrderByPeriodEndDescNullsLast(any())).thenAnswer(inv -> {
            List<Statement> all = new ArrayList<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                List<Statement> rows = statements.findByAccountIdOrderByPeriodEndDescNullsLast(id);
                if (rows != null) {
                    all.addAll(rows);
                }
            }
            return all;
        });
        lenient().when(statements.findLastLineDates(any())).thenAnswer(inv -> {
            List<StatementRepository.StatementLastLine> rows = new ArrayList<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                LocalDate last = statements.findLastLineDate(id);
                if (last != null) {
                    rows.add(new StatementRepository.StatementLastLine() {
                        public UUID getStatementId() {
                            return id;
                        }

                        public LocalDate getLastLineDate() {
                            return last;
                        }
                    });
                }
            }
            return rows;
        });
        lenient().when(transactions.findTotalTransactionSumsByAccountIds(any())).thenAnswer(inv -> {
            List<TransactionRepository.AccountSumProjection> rows = new ArrayList<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                BigDecimal total = transactions.findTotalTransactionSumByAccountId(id);
                if (total != null) {
                    rows.add(new TransactionRepository.AccountSumProjection() {
                        public UUID getAccountId() {
                            return id;
                        }

                        public BigDecimal getTotal() {
                            return total;
                        }
                    });
                }
            }
            return rows;
        });
        lenient().when(transactions.sumsAfterCutoffs(any())).thenAnswer(inv -> {
            Map<UUID, TransactionRepositoryCustom.SumsAfter> out = new HashMap<>();
            ((Map<UUID, LocalDate>) inv.getArgument(0)).forEach((id, cutoff) -> {
                BigDecimal credits = transactions.sumCreditsAfter(id, cutoff);
                BigDecimal debits = transactions.sumDebitsAfter(id, cutoff);
                out.put(id, new TransactionRepositoryCustom.SumsAfter(
                        credits == null ? BigDecimal.ZERO : credits, debits == null ? BigDecimal.ZERO : debits));
            });
            return out;
        });
        lenient().doAnswer(inv -> {
            for (Account account : (List<Account>) inv.getArgument(0)) {
                accountService.populateLiveBalance(account);
                if (account.getEffectiveCreditLimit() == null) {
                    account.setEffectiveCreditLimit(CardUtilization.creditLimit(account, statements));
                }
            }
            return null;
        }).when(accountService).populateBalances(any());
    }
}
