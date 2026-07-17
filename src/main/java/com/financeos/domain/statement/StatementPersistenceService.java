package com.financeos.domain.statement;

import com.financeos.domain.account.Account;
import com.financeos.domain.user.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class StatementPersistenceService {

    private final StatementRepository statementRepository;
    private final StatementTransactionRepository statementTransactionRepository;

    public StatementPersistenceService(StatementRepository statementRepository,
            StatementTransactionRepository statementTransactionRepository) {
        this.statementRepository = statementRepository;
        this.statementTransactionRepository = statementTransactionRepository;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Optional<Statement> createIfNew(User user, Account account, StatementSource source, String sourceRef,
            String fileSha256, StatementDraft draft) {
        if (draft.periodStart() != null && draft.periodEnd() != null
                && statementRepository.existsByAccountIdAndPeriodStartAndPeriodEnd(account.getId(),
                        draft.periodStart(), draft.periodEnd())) {
            return Optional.empty();
        }
        if (fileSha256 != null && statementRepository.existsByAccountIdAndFileSha256(account.getId(), fileSha256)) {
            return Optional.empty();
        }

        Statement statement = new Statement();
        statement.setUser(user);
        statement.setAccount(account);
        statement.setSource(source);
        statement.setSourceRef(sourceRef);
        statement.setFileSha256(fileSha256);
        statement.setStatementType(draft.statementType());
        statement.setPeriodStart(draft.periodStart());
        statement.setPeriodEnd(draft.periodEnd());
        statement.setOpeningBalance(draft.openingBalance());
        statement.setClosingBalance(draft.closingBalance());
        statement.setBankName(draft.bankName());
        statement.setAccountNumberMasked(draft.accountNumberMasked());
        statement.setTransactionCount(draft.transactionCount());
        statement.setLinesSkipped(draft.linesSkipped());
        statement.setTotalDebits(draft.totalDebits());
        statement.setTotalCredits(draft.totalCredits());
        statement.setParseMode(draft.parseMode());
        statement.setChainValidationPct(draft.chainValidationPct());
        statement.setChecksumOk(draft.checksumOk());
        statement.setVerdict(draft.verdict());

        Map<String, Object> cardFields = draft.cardFields();
        if ("credit_card".equals(draft.statementType()) && cardFields != null && !cardFields.isEmpty()) {
            StatementCreditCardDetails details = new StatementCreditCardDetails(statement);
            details.setUser(user);
            details.setTotalAmountDue((BigDecimal) cardFields.get("total_amount_due"));
            details.setMinimumAmountDue((BigDecimal) cardFields.get("minimum_amount_due"));
            details.setPaymentDueDate((LocalDate) cardFields.get("payment_due_date"));
            details.setCreditLimit((BigDecimal) cardFields.get("credit_limit"));
            details.setAvailableCreditLimit((BigDecimal) cardFields.get("available_credit_limit"));
            details.setFinanceCharges((BigDecimal) cardFields.get("finance_charges"));
            details.setFeesAndCharges((BigDecimal) cardFields.get("fees_and_charges"));
            details.setPreviousBalance((BigDecimal) cardFields.get("previous_balance"));
            details.setPaymentsReceived((BigDecimal) cardFields.get("payments_received"));
            details.setTotalPurchases((BigDecimal) cardFields.get("total_purchases"));
            details.setRewardPointsBalance((BigDecimal) cardFields.get("reward_points_balance"));
            details.setRewardPointsEarned((BigDecimal) cardFields.get("reward_points_earned"));
            statement.setCreditCardDetails(details);
        }

        Statement saved = statementRepository.save(statement);
        return Optional.of(saved);
    }

    public record TxnLink(UUID transactionId, int lineIndex, BigDecimal balanceAfter, Boolean chainValid) {
    }

    @Transactional
    public void linkTransactions(UUID statementId, List<TxnLink> links) {
        List<StatementTransaction> rows = links.stream()
                .map(link -> new StatementTransaction(statementId, link.transactionId(), link.lineIndex(),
                        link.balanceAfter(), link.chainValid()))
                .toList();
        statementTransactionRepository.saveAll(rows);
    }
}
