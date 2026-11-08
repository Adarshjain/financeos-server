package com.financeos.domain.notification.bill;

import com.financeos.api.transaction.dto.CreateTransactionRequest;
import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.api.transactionlink.dto.MemberRef;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.BalanceMath;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionService;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkCandidateLoader;
import com.financeos.domain.transaction.autolink.AutoLinkStore;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.transaction.link.TransactionLinkService;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays a credit-card bill from a bank payment, for both entry points: the auto matcher ({@link BillPaymentOrigin#AUTO})
 * and "Mark paid" ({@link BillPaymentOrigin#USER}). A payment is a card-side CREDIT on the bill's card (source manual,
 * card_bill), optionally the bank DEBIT it came from (an existing one, or one created here), the CC_PAYMENT link
 * between them, and a {@code bill_payments} row. The bill is paid by those credits (never by the manual mark), so it
 * and the card balance agree.
 */
@Service
@Transactional
public class BillPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    /** How far past the due date the candidates endpoint looks for the bank debit. */
    static final int CANDIDATE_DAYS_AFTER_DUE = 10;
    private static final String CARD_BILL = "card_bill";
    private static final String CARD_BILL_PAID = "card_bill.credit_card";

    /** Where the money came from. */
    public sealed interface Source {
        /** An existing bank debit (its amount and date are the payment's). */
        record ExistingDebit(UUID bankTxnId, BigDecimal expectedAmount) implements Source {
            public ExistingDebit(UUID bankTxnId) {
                this(bankTxnId, null);
            }
        }

        /** A bank debit to create on {@code fromAccountId}; {@code paidOn} null = today. */
        record NewDebit(UUID fromAccountId, BigDecimal amount, LocalDate paidOn) implements Source {
        }

        /** Only the card-side credit; {@code paidOn} null = today. */
        record CardOnly(BigDecimal amount, LocalDate paidOn) implements Source {
        }
    }

    /** One payment of a bill as the bill response shows it. */
    public record PaymentView(UUID transactionId, UUID bankTransactionId, String bankAccountName, LocalDate date,
                              BigDecimal amount, BillPaymentOrigin origin) {
    }

    /** A bank debit that could be the payment of a bill. */
    public record CandidateView(UUID transactionId, UUID accountId, String accountName, LocalDate date, BigDecimal amount,
                                String description, boolean payeeSignal) {
    }

    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionService transactionService;
    private final TransactionLinkService linkService;
    private final TransactionLinkRepository linkRepository;
    private final BillPaymentStore store;
    private final BillPaymentCandidates candidates;
    private final AutoLinkCandidateLoader loader;
    private final AutoLinkStore autoLinkStore;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final BillPaymentMetrics metrics;

    public BillPaymentService(StatementRepository statementRepository, TransactionRepository transactionRepository,
                              AccountRepository accountRepository, TransactionService transactionService,
                              TransactionLinkService linkService, TransactionLinkRepository linkRepository,
                              BillPaymentStore store, BillPaymentCandidates candidates, AutoLinkCandidateLoader loader,
                              AutoLinkStore autoLinkStore, GmailProcessedMessageRepository processedMessageRepository,
                              BillPaymentMetrics metrics) {
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionService = transactionService;
        this.linkService = linkService;
        this.linkRepository = linkRepository;
        this.store = store;
        this.candidates = candidates;
        this.loader = loader;
        this.autoLinkStore = autoLinkStore;
        this.processedMessageRepository = processedMessageRepository;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- record

    /**
     * Records one payment of the bill (see the class comment). One DB transaction; the ingest event is published by the creates.
     * Returns null, recording nothing, when an AUTO payment finds the bill already settled once the card is locked.
     */
    public BillPaymentStore.Row record(UUID userId, UUID statementId, Source source, BillPaymentOrigin origin) {
        return UserContextHelper.callAs(userId, () -> doRecord(userId, statementId, source, origin));
    }

    private BillPaymentStore.Row doRecord(UUID userId, UUID statementId, Source source, BillPaymentOrigin origin) {
        Statement statement = loadOwnedCardStatement(userId, statementId);
        Account card = statement.getAccount();
        accountRepository.lockById(card.getId());   // the lock the statement matcher takes: two payers cannot both pass the checks below
        LocalDate cutoff = cutoff(statement);
        if (cutoff == null) {
            throw new ValidationException("This statement has no period end yet, so a payment cannot be placed after it. Fill in the statement date first.");
        }
        if (origin == BillPaymentOrigin.AUTO && settled(userId, statementId, card.getId(), cutoff)) {
            log.info("Bill payment skipped, bill already settled: statementId={}", statementId);
            return null;
        }
        LocalDate today = AppTime.today();

        Transaction existing = null;
        Account from = null;
        BigDecimal amount;
        LocalDate date;
        if (source instanceof Source.ExistingDebit e) {
            existing = loadFreeBankDebit(userId, e.bankTxnId());
            if (e.expectedAmount() != null && existing.getAmount().compareTo(e.expectedAmount()) != 0) {
                throw new ValidationException("The amount must equal the selected debit");
            }
            amount = existing.getAmount();
            date = existing.getDate();
            from = existing.getAccount();
        } else if (source instanceof Source.NewDebit n) {
            from = loadOwnBankAccount(userId, n.fromAccountId());
            amount = n.amount();
            date = n.paidOn() != null ? n.paidOn() : today;
        } else if (source instanceof Source.CardOnly c) {
            amount = c.amount();
            date = c.paidOn() != null ? c.paidOn() : today;
        } else {
            throw new ValidationException("Unknown payment source");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Paid amount must be greater than zero");
        }
        if (date.isAfter(today)) {
            throw new ValidationException("Paid date cannot be in the future");
        }
        if (!date.isAfter(cutoff)) {
            throw new ValidationException("The payment must be dated after the statement date (" + cutoff + ")");
        }

        String bankName = from != null ? from.getName() : null;
        Transaction credit = transactionService.createTransaction(new CreateTransactionRequest(card.getId(), null, date, amount,
                "Card bill payment" + (bankName != null && !bankName.isBlank() ? " from " + bankName : ""), null,
                CARD_BILL, null, null, null, null, null, null));
        Transaction debit = existing;
        boolean debitCreated = false;
        if (debit == null && source instanceof Source.NewDebit) {
            debit = transactionService.createTransaction(new CreateTransactionRequest(from.getId(), null, date, amount.negate(),
                    "Card bill payment to " + card.getName(), null, CARD_BILL, CARD_BILL_PAID, null, null, null, null, null));
            debitCreated = true;
        }
        UUID linkId = null;
        if (debit != null) {
            linkId = linkService.createLink(new CreateTransactionLinkRequest(LinkType.CC_PAYMENT, null, null,
                    List.of(new MemberRef(debit.getId(), true), new MemberRef(credit.getId(), false))),
                    origin == BillPaymentOrigin.AUTO ? LinkOrigin.AUTO : LinkOrigin.USER).id();
        }
        BillPaymentStore.Row row = new BillPaymentStore.Row(UUID.randomUUID(), userId, statementId, credit.getId(),
                debit == null ? null : debit.getId(), debitCreated, origin, linkId, OffsetDateTime.now(AppTime.zone()));
        transactionRepository.flush();   // bill_payments is written over JDBC: the new card credit (and debit) must be in the DB first (FK)
        store.insert(row);
        metrics.matched(origin);
        log.info("Bill payment recorded: statementId={}, origin={}, amount={}, cardTxnId={}, bankTxnId={}", statementId, origin,
                amount, credit.getId(), row.bankTxnId());
        return row;
    }

    /** Read fresh (the card is locked): a manual mark, a recorded payment or credits after the last billed day settle the bill. */
    private boolean settled(UUID userId, UUID statementId, UUID cardAccountId, LocalDate cutoff) {
        if (store.paidMarkedOn(statementId) != null || !store.byStatement(userId, statementId).isEmpty()) {
            return true;
        }
        BigDecimal credits = transactionRepository.sumCreditsAfter(cardAccountId, cutoff);
        return credits != null && credits.signum() > 0;
    }

    private Transaction loadFreeBankDebit(UUID userId, UUID id) {
        // user-scoped: someone else's id is indistinguishable from a missing one
        Transaction t = transactionRepository.findAllByIdInAndUserId(List.of(id), userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        if (t.getType() != TransactionType.DEBIT) {
            throw new ValidationException("A bill is paid from a debit.");
        }
        if (t.getAccount() == null || t.getAccount().getType() == AccountType.credit_card) {
            throw new ValidationException("A bill is paid from a bank account, not from a credit card.");
        }
        if (!store.bankTxnIdsUsed(userId, List.of(id)).isEmpty()) {
            throw new ValidationException("That debit already pays a bill.");
        }
        if (!loader.freeIds(userId, List.of(id)).contains(id)) {
            throw new ValidationException("That transaction is already linked to something else.");
        }
        return t;
    }

    private Account loadOwnBankAccount(UUID userId, UUID accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (account.getUser() == null || !account.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to use that account.");
        }
        if (account.getType() == AccountType.credit_card) {
            throw new ValidationException("A bill is paid from a bank account, not from a credit card.");
        }
        return account;
    }

    // ---------------------------------------------------------------- undo

    /**
     * Takes back every payment recorded for the bill: the card credit goes unless a statement (or an alert) has merged
     * into it, the bank debit goes only when the app created it and nothing merged into it; the link goes. An AUTO payment
     * is remembered so the matcher never re-adds it.
     */
    public void undo(UUID userId, UUID statementId) {
        UserContextHelper.runAs(userId, () -> {
            for (BillPaymentStore.Row row : store.byStatement(userId, statementId)) {
                remove(userId, row, true, true);
            }
        });
    }

    /**
     * A CC_PAYMENT link was removed by the user. Origin AUTO: the created card credit goes (unless a statement merged
     * into it) and the pairing is remembered. Origin USER: only the payment record goes, both transactions stay.
     */
    public void onLinkRemoved(UUID userId, UUID linkId, Collection<UUID> memberTransactionIds) {
        Optional<BillPaymentStore.Row> row = store.findByLinkOrTransactions(userId, linkId, memberTransactionIds);
        row.ifPresent(r -> remove(userId, r, false, r.origin() == BillPaymentOrigin.AUTO));
    }

    private void remove(UUID userId, BillPaymentStore.Row row, boolean deleteLink, boolean deleteTransactions) {
        store.delete(userId, row.id());
        if (deleteLink) {
            findLinkOf(row).ifPresent(link -> linkService.deleteLink(link.getId()));
        }
        List<UUID> ids = new ArrayList<>(List.of(row.cardTxnId()));
        if (row.bankTxnId() != null) {
            ids.add(row.bankTxnId());
        }
        Set<UUID> merged = new HashSet<>(store.statementAttached(ids));
        merged.addAll(processedMessageRepository.findTransactionIdsIn(ids));

        boolean cardKept = true;
        boolean bankKept = row.bankTxnId() != null;
        if (deleteTransactions) {
            if (!merged.contains(row.cardTxnId())) {
                cardKept = !deleteOwned(userId, row.cardTxnId());
            }
            if (row.bankTxnCreated() && row.bankTxnId() != null && !merged.contains(row.bankTxnId())) {
                bankKept = !deleteOwned(userId, row.bankTxnId());
            }
        }
        List<AutoLinkStore.Rejection> rejections = new ArrayList<>();
        if (row.bankTxnId() != null && bankKept && (row.origin() == BillPaymentOrigin.AUTO || deleteTransactions)) {
            // the debit stays free: the matcher must not pay this bill from it again
            store.addRejection(userId, row.bankTxnId(), row.statementId());
        }
        if (deleteTransactions && cardKept && bankKept) {
            // Both rows stay: the transfer linker must not pair them again behind the undo.
            rejections.add(new AutoLinkStore.Rejection(SuggestionKind.CC_PAYMENT, row.bankTxnId(), row.cardTxnId()));
            rejections.add(new AutoLinkStore.Rejection(SuggestionKind.CC_PAYMENT, row.cardTxnId(), row.bankTxnId()));
        }
        if (!rejections.isEmpty()) {
            autoLinkStore.addRejections(userId, rejections);
        }
        log.info("Bill payment removed: statementId={}, origin={}, cardTxnKept={}, bankTxnKept={}", row.statementId(), row.origin(),
                cardKept, bankKept);
    }

    private Optional<TransactionLink> findLinkOf(BillPaymentStore.Row row) {
        if (row.linkId() != null) {
            Optional<TransactionLink> byId = linkRepository.findById(row.linkId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return linkRepository.findAllByMembers_Transaction_Id(row.cardTxnId()).stream()
                .filter(l -> l.getType() == LinkType.CC_PAYMENT).findFirst();
    }

    /** Deletes the user's transaction (its link first); true when it was deleted. */
    private boolean deleteOwned(UUID userId, UUID transactionId) {
        Optional<Transaction> t = transactionRepository.findById(transactionId);
        if (t.isEmpty() || t.get().getUser() == null || !t.get().getUser().getId().equals(userId)) {
            return false;
        }
        linkService.autoDissolveLinksForDeletedTransactions(List.of(transactionId));
        transactionRepository.delete(t.get());
        return true;
    }

    // ---------------------------------------------------------------- reads

    /** The payments of each bill, in the order they were recorded; a bill with none has no entry. */
    @Transactional(readOnly = true)
    public Map<UUID, List<PaymentView>> paymentsFor(UUID userId, Collection<UUID> statementIds) {
        if (statementIds == null || statementIds.isEmpty()) {
            return Map.of();
        }
        List<BillPaymentStore.Row> rows = store.byStatements(userId, statementIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<UUID> txnIds = new ArrayList<>();
        for (BillPaymentStore.Row r : rows) {
            txnIds.add(r.cardTxnId());
            if (r.bankTxnId() != null) {
                txnIds.add(r.bankTxnId());
            }
        }
        Map<UUID, Transaction> byId = new HashMap<>();
        for (int i = 0; i < txnIds.size(); i += 500) {
            transactionRepository.findAllWithAccountByIdInAndUserId(txnIds.subList(i, Math.min(i + 500, txnIds.size())), userId)
                    .forEach(t -> byId.put(t.getId(), t));
        }
        Map<UUID, List<PaymentView>> out = new LinkedHashMap<>();
        for (BillPaymentStore.Row r : rows) {
            Transaction credit = byId.get(r.cardTxnId());
            if (credit == null) {
                continue;
            }
            Transaction debit = r.bankTxnId() == null ? null : byId.get(r.bankTxnId());
            out.computeIfAbsent(r.statementId(), k -> new ArrayList<>()).add(new PaymentView(credit.getId(),
                    debit == null ? null : debit.getId(), debit == null || debit.getAccount() == null ? null : debit.getAccount().getName(),
                    credit.getDate(), credit.getAmount(), r.origin()));
        }
        return out;
    }

    /** Free bank debits of exactly {@code amount} that could be this bill's payment: payee signal first, then nearest to the due date. */
    @Transactional(readOnly = true)
    public List<CandidateView> candidates(UUID userId, UUID statementId, BigDecimal amount) {
        Statement statement = loadOwnedCardStatement(userId, statementId);
        LocalDate cutoff = cutoff(statement);
        if (cutoff == null || amount == null) {
            return List.of();
        }
        LocalDate due = statement.getCreditCardDetails().getPaymentDueDate();
        LocalDate base = due != null ? due : statement.getPeriodEnd().plusDays(30);
        List<CandidateView> out = new ArrayList<>();
        for (BillPaymentCandidates.Debit d : candidates.load(userId, cutoff, base.plusDays(CANDIDATE_DAYS_AFTER_DUE))) {
            if (d.amount().compareTo(amount) == 0) {
                out.add(new CandidateView(d.id(), d.accountId(), d.accountName(), d.date(), d.amount(), d.description(),
                        candidates.payeeSignal(d)));
            }
        }
        out.sort(Comparator.comparing((CandidateView c) -> !c.payeeSignal())
                .thenComparingLong(c -> Math.abs(ChronoUnit.DAYS.between(base, c.date())))
                .thenComparing(CandidateView::date).thenComparing(CandidateView::transactionId));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** The statement's last billed day ({@link BalanceMath#cutoff}); null without a period end. */
    LocalDate cutoff(Statement statement) {
        LocalDate periodEnd = statement.getPeriodEnd();
        return BalanceMath.cutoff(periodEnd, periodEnd == null ? null : statementRepository.findLastLineDate(statement.getId()));
    }

    private Statement loadOwnedCardStatement(UUID userId, UUID statementId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new ResourceNotFoundException("Statement", statementId));
        if (statement.getUser() == null || !statement.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to access this statement.");
        }
        if (!"credit_card".equals(statement.getStatementType()) || statement.getCreditCardDetails() == null) {
            throw new ValidationException("Only credit card statements carry a bill.");
        }
        return statement;
    }
}
