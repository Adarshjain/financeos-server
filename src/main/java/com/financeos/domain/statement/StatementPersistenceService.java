package com.financeos.domain.statement;

import com.financeos.domain.notification.StatementCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;

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
import java.util.Set;
import java.util.UUID;

@Service
public class StatementPersistenceService {

    private final StatementRepository statementRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StatementPersistenceService(StatementRepository statementRepository,
            StatementTransactionRepository statementTransactionRepository,
            ApplicationEventPublisher eventPublisher) {
        this.statementRepository = statementRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.eventPublisher = eventPublisher;
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
        // Consumed after commit (bill digest push); nothing happens if the transaction rolls back.
        eventPublisher.publishEvent(new StatementCreatedEvent(saved.getId(), user.getId()));
        return Optional.of(saved);
    }

    /**
     * A re-upload of a card statement that already exists (same file, else same account and period
     * end) while that statement still lacks figures: fills only its empty fields from the new parse.
     * Never overwrites a present value and never touches lines. Empty when the draft is not a card
     * statement or no matching statement has a gap (the caller then takes the normal duplicate path);
     * otherwise the fields filled (empty list = nothing new in this file).
     */
    @Transactional
    public Optional<List<String>> fillMissingDetails(UUID accountId, String fileSha256, StatementDraft draft) {
        if (!"credit_card".equals(draft.statementType())) {
            return Optional.empty();
        }
        Statement target = null;
        if (fileSha256 != null) {
            target = firstWithGap(statementRepository.findByAccountIdAndFileSha256(accountId, fileSha256));
        }
        if (target == null && draft.periodEnd() != null) {
            target = firstWithGap(statementRepository.findByAccountIdAndPeriodEnd(accountId, draft.periodEnd()));
        }
        if (target == null) {
            return Optional.empty();
        }
        Map<String, Object> card = draft.cardFields() == null ? Map.of() : draft.cardFields();
        List<String> filled = new java.util.ArrayList<>();
        // A parsed period is trustworthy (label/range or labelled statement date), the stored one may have been guessed: replace it.
        boolean hadEnd = target.getPeriodEnd() != null;
        boolean periodChanged = false;
        // The whole period is replaced, so a guessed start never survives beside a parsed end.
        if (draft.periodEnd() != null && (!draft.periodEnd().equals(target.getPeriodEnd())
                || !java.util.Objects.equals(draft.periodStart(), target.getPeriodStart()))) {
            target.setPeriodStart(draft.periodStart());
            target.setPeriodEnd(draft.periodEnd());
            periodChanged = true;
        }
        if (periodChanged) {
            filled.add(hadEnd ? MissingDetails.PERIOD_UPDATED : MissingDetails.PERIOD_END);
        }
        LocalDate due = (LocalDate) card.get("payment_due_date");
        BigDecimal total = (BigDecimal) card.get("total_amount_due");
        BigDecimal min = (BigDecimal) card.get("minimum_amount_due");
        StatementCreditCardDetails d = target.getCreditCardDetails();
        boolean needsDetails = (due != null || total != null || min != null)
                && (d == null || d.getPaymentDueDate() == null || d.getTotalAmountDue() == null || d.getMinimumAmountDue() == null);
        if (needsDetails) {
            if (d == null) {
                d = new StatementCreditCardDetails(target);
                d.setUser(target.getUser());
                target.setCreditCardDetails(d);
            }
            if (d.getPaymentDueDate() == null && due != null) {
                d.setPaymentDueDate(due);
                filled.add(MissingDetails.DUE_DATE);
            }
            if (d.getTotalAmountDue() == null && total != null) {
                d.setTotalAmountDue(total);
                if (target.getClosingBalance() == null) {
                    target.setClosingBalance(total); // a card statement's closing balance is its total due
                }
                filled.add(MissingDetails.TOTAL_DUE);
            }
            if (d.getMinimumAmountDue() == null && min != null) {
                d.setMinimumAmountDue(min);
                filled.add(MissingDetails.MINIMUM_DUE);
            }
        }
        if (!filled.isEmpty()) {
            statementRepository.save(target);
        }
        return Optional.of(List.copyOf(filled));
    }

    private static Statement firstWithGap(List<Statement> candidates) {
        for (Statement s : candidates) {
            if (!MissingDetails.of(s).isEmpty()) {
                return s;
            }
        }
        return null;
    }

    public record TxnLink(UUID transactionId, int lineIndex, BigDecimal balanceAfter, Boolean chainValid) {
    }

    /** Ids of the account's transactions in the date range that some statement already links to. */
    @Transactional(readOnly = true)
    public Set<UUID> findStatementLinkedTransactionIds(UUID accountId, LocalDate startDate, LocalDate endDate) {
        return statementTransactionRepository.findLinkedTransactionIds(accountId, startDate, endDate);
    }

    @Transactional
    public void linkTransactions(UUID statementId, List<TxnLink> links) {
        Statement statement = statementRepository.getReferenceById(statementId);
        for (TxnLink link : links) {
            StatementTransaction st = new StatementTransaction(
                    statement.getId(),
                    link.transactionId(),
                    link.lineIndex(),
                    link.balanceAfter(),
                    link.chainValid()
            );
            statementTransactionRepository.save(st);
        }
    }
}
