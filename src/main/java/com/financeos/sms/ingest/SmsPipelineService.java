package com.financeos.sms.ingest;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import com.financeos.domain.transaction.match.AlertMatcher;
import com.financeos.domain.transaction.match.ReferenceNumbers;
import com.financeos.sms.line.SenderCatalogue;
import com.financeos.sms.line.SmsLineRepository;
import com.financeos.sms.read.SmsReadStatus;
import com.financeos.sms.read.SmsReader;
import com.financeos.sms.read.SmsReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a stored message and decides what happens to it: second alerts are removed, then it is matched against the
 * ledger. With posting off nothing is changed (the would-be outcome is recorded); with posting on it attaches to the
 * payment already on the ledger or creates its transaction (source sms_alert). Re-runnable for a message that has not
 * been posted, so a template change can be replayed.
 */
@Service
public class SmsPipelineService {

    private static final Logger log = LoggerFactory.getLogger(SmsPipelineService.class);
    private static final Duration SECOND_ALERT_WINDOW = Duration.ofDays(1);
    /** Messages received this far apart can claim the same transaction (the widest match window is a week). */
    private static final Duration CLAIM_WINDOW = Duration.ofDays(14);
    /** Undecided generic readings received this many days back are matched again after each batch. */
    static final int RECHECK_DAYS = 45;
    static final int RECHECK_CAP = 300;
    /** Messages re-run per flush during a replay. */
    static final int REPLAY_CHUNK = 200;

    private final SmsMessageRepository messages;
    private final SmsLineRepository lines;
    private final AccountRepository accounts;
    private final AlertMatcher matcher;
    private final SmsReader reader;
    private final TransactionRepository transactions;
    private final SenderCatalogue catalogue;
    private final ApplicationEventPublisher events;
    private final boolean postingEnabled;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public SmsPipelineService(SmsMessageRepository messages, SmsLineRepository lines, AccountRepository accounts, AlertMatcher matcher, SmsReader reader,
                              TransactionRepository transactions, SenderCatalogue catalogue, ApplicationEventPublisher events,
                              PlatformTransactionManager txManager,
                              @Value("${financeos.sms.posting-enabled:false}") boolean postingEnabled) {
        this.tx = new TransactionTemplate(txManager);
        this.messages = messages;
        this.lines = lines;
        this.accounts = accounts;
        this.matcher = matcher;
        this.reader = reader;
        this.transactions = transactions;
        this.catalogue = catalogue;
        this.events = events;
        this.postingEnabled = postingEnabled;
    }

    public boolean isPostingEnabled() {
        return postingEnabled;
    }

    /** Runs the reader on the stored text and copies what it found onto the message. */
    public void read(SmsMessage m) {
        SmsReading r;
        try {
            r = reader.read(m.getSender(), m.getBody(), m.getReceivedAt());
        } catch (RuntimeException e) {
            log.warn("sms.read.failed message={} error={}", m.getId(), e.getClass().getSimpleName());
            r = SmsReading.couldNotRead();
        }
        m.setKind(r.kind());
        m.setReadStatus(r.status());
        m.setTemplateKey(r.templateKey());
        m.setAmount(r.amount());
        m.setDirection(r.direction());
        m.setEnding(r.ending());
        m.setRefNo(r.refNo());
        m.setBalanceAfter(r.balanceAfter());
        m.setCounterparty(r.counterparty());
        m.setTxnDate(r.txnDate());
    }

    /** Serialises the user's intake, confirms and replays: held until the transaction ends. */
    public void lockUser(UUID userId) {
        lines.lockUser(userId);
    }

    /** {@link #decide(SmsMessage, Account)} with the account looked up (one query). */
    public void decide(SmsMessage m) {
        Account account = m.getAccountId() == null ? null
                : accounts.findByIdAndUser_Id(m.getAccountId(), m.getUserId()).orElse(null);
        decide(m, account);
    }

    /**
     * Decides the outcome of one message (clearing the old one first). READ messages of an account get one. A
     * NEEDS_CONFIRM message (a generic reading) runs the same checks but never creates: a second notice is a DUPLICATE,
     * a payment already on the ledger turns it into a READ attach (link only), anything else leaves it undecided.
     */
    public void decide(SmsMessage m, Account account) {
        m.setOutcome(null);
        m.setDuplicateOf(null);
        m.setTransactionId(null);
        boolean generic = m.getReadStatus() == SmsReadStatus.NEEDS_CONFIRM;
        if ((m.getReadStatus() != SmsReadStatus.READ && !generic) || account == null || m.getAmount() == null
                || m.getDirection() == null || (generic && m.getTxnDate() == null)) {
            return;
        }
        if (generic && !SmsReader.directionCertain(m.getBody())) {
            // Mixed or unsettled wording: shown to the owner, never judged a second notice and never matched.
            return;
        }
        Optional<UUID> original = findOriginal(m);
        if (original.isPresent()) {
            m.setOutcome(SmsOutcome.DUPLICATE);
            m.setDuplicateOf(original.get());
            if (generic) {
                // A second notice of a payment already read leaves the owner's check list.
                m.setReadStatus(SmsReadStatus.READ);
            }
            return;
        }
        if (generic) {
            attachGeneric(m, account);
            return;
        }
        if (postingEnabled) {
            // Posting a message twice never makes two transactions: one that already has its transaction is not posted again.
            Optional<Transaction> done = transactions.findByUserIdAndSourceMessageId(m.getUserId(), sourceId(m));
            if (done.isPresent()) {
                boolean created = done.get().getSource() == TransactionSource.sms_alert;
                m.setOutcome(created ? SmsOutcome.CREATED : SmsOutcome.ATTACHED);
                m.setTransactionId(done.get().getId());
                return;
            }
        }
        Set<UUID> claimed = claimedBy(m);
        AlertMatcher.Alert alert = alertOf(m, account);
        // A transaction another message already claims cannot be claimed twice: the second equal payment is a new one.
        Optional<Transaction> match = matcher.findMatch(alert, claimed);
        if (match.isPresent()) {
            m.setOutcome(SmsOutcome.SHADOW_ATTACH);
            m.setTransactionId(match.get().getId());
        } else {
            m.setOutcome(SmsOutcome.SHADOW_CREATE);
        }
        if (postingEnabled) {
            postForReal(m, account, claimed);
        }
    }

    /**
     * A generic reading that is a payment already on the ledger becomes READ with SHADOW_ATTACH (posting off) or
     * ATTACHED (posting on). Link only: no field of the transaction changes. No match: nothing changes.
     */
    private void attachGeneric(SmsMessage m, Account account) {
        Set<UUID> claimed = claimedBy(m);
        Optional<Transaction> match = matcher.findMatch(alertOf(m, account), claimed);
        if (match.isEmpty()) {
            return;
        }
        if (postingEnabled) {
            Account managed = accounts.findByIdAndUser_Id(account.getId(), m.getUserId()).orElse(account);
            Optional<AlertMatcher.Attached> attached = matcher.attachLinkOnly(alertOf(m, managed), claimed);
            if (attached.isEmpty()) {
                return;
            }
            m.setOutcome(SmsOutcome.ATTACHED);
            m.setTransactionId(attached.get().txn().getId());
        } else {
            m.setOutcome(SmsOutcome.SHADOW_ATTACH);
            m.setTransactionId(match.get().getId());
        }
        m.setReadStatus(SmsReadStatus.READ);
    }

    /**
     * Re-runs the match for the user's still-undecided generic readings received in the last {@link #RECHECK_DAYS} days
     * (at most {@link #RECHECK_CAP}, newest first), so a message that arrived before its statement or email alert stops
     * asking once the transaction exists. Messages in {@code skip} were just decided and are left out before the cap.
     * Each message is decided in its own transaction that takes the user lock first and then reloads the message, so a
     * confirm, reject or replay that got in between is seen and respected, and one failure rolls back only itself.
     * Returns how many became READ.
     */
    public int recheckUndecided(UUID userId, Set<UUID> skip) {
        Instant since = AppTime.nowInstant().minus(Duration.ofDays(RECHECK_DAYS));
        List<UUID> ids = messages.findUndecidedNeedsConfirmIds(userId, since, PageRequest.of(0, RECHECK_CAP + skip.size())).stream()
                .filter(id -> !skip.contains(id)).limit(RECHECK_CAP).toList();
        int promoted = 0;
        for (UUID id : ids) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> recheckOne(userId, id)))) {
                    promoted++;
                }
            } catch (RuntimeException e) {
                log.warn("sms.recheck.failed message={} error={}", id, e.getClass().getSimpleName());
            }
        }
        return promoted;
    }

    private boolean recheckOne(UUID userId, UUID id) {
        lockUser(userId);
        SmsMessage m = messages.findByIdAndUserId(id, userId).orElse(null);
        if (m == null || m.getReadStatus() != SmsReadStatus.NEEDS_CONFIRM || m.getOutcome() != null || m.getAccountId() == null) {
            return false;
        }
        decide(m);
        messages.save(m);
        return m.getReadStatus() == SmsReadStatus.READ;
    }

    /** Read then decide: the full run for a message of {@code account}. */
    public void process(SmsMessage m, Account account) {
        read(m);
        decide(m, account);
    }

    /**
     * Re-reads and re-decides every stored message of the user, in received order. A message the owner confirmed or
     * rejected keeps what they decided and is only re-decided. Returns how many messages were run. One transaction under
     * the user lock for the whole run; the messages are worked through in chunks that are flushed and then dropped from
     * the persistence context, so no query has to dirty-check every message of the user.
     */
    @Transactional
    public int replay(UUID userId) {
        lockUser(userId);
        // A message that is already posted (CREATED / ATTACHED) keeps its transaction: replay never re-decides it.
        messages.resetUnposted(userId);
        List<UUID> ids = messages.findUnpostedIdsInReceivedOrder(userId);
        for (int from = 0; from < ids.size(); from += REPLAY_CHUNK) {
            List<UUID> part = ids.subList(from, Math.min(ids.size(), from + REPLAY_CHUNK));
            java.util.Map<UUID, SmsMessage> loaded = new java.util.HashMap<>();
            messages.findAllById(part).forEach(m -> loaded.put(m.getId(), m));
            java.util.Map<UUID, Account> byId = new java.util.HashMap<>();
            accounts.findByUserId(userId).forEach(a -> byId.put(a.getId(), a));
            for (UUID id : part) {
                SmsMessage m = loaded.get(id);
                if (m == null) {
                    continue;
                }
                if (m.getUserDecision() == null) {
                    read(m);
                }
                decide(m, m.getAccountId() == null ? null : byId.get(m.getAccountId()));
            }
            messages.flush();
            em.clear();
        }
        return ids.size();
    }

    /** Transactions other messages of the account near this one already point at. */
    private Set<UUID> claimedBy(SmsMessage m) {
        return messages.findClaimedTransactionIds(m.getUserId(), m.getAccountId(), m.getId(),
                m.getReceivedAt().minus(CLAIM_WINDOW), m.getReceivedAt().plus(CLAIM_WINDOW));
    }

    private static String sourceId(SmsMessage m) {
        return "sms:" + m.getId();
    }

    /**
     * Posts for real: attaches to the transaction the matcher finds on the ledger (ATTACHED) or creates the line's
     * transaction with source sms_alert (CREATED), through the matcher the email alerts use. A new row is categorised
     * on arrival and announced to auto-linking like a mail's. The first row of a generic account without an opening
     * balance sets it from the message's balance.
     */
    private void postForReal(SmsMessage m, Account account, Set<UUID> claimed) {
        Account managed = accounts.findByIdAndUser_Id(account.getId(), m.getUserId()).orElse(account);
        AlertMatcher.Outcome o = matcher.matchOrCreate(alertOf(m, managed), claimed);
        if (!o.created()) {
            m.setOutcome(SmsOutcome.ATTACHED);
            m.setTransactionId(o.attached().txn().getId());
            return;
        }
        Transaction txn = o.txn();
        m.setOutcome(SmsOutcome.CREATED);
        m.setTransactionId(txn.getId());
        // After commit and in its own transaction, so categorising can never undo the post.
        events.publishEvent(new SmsArrivalCategoriser.Created(m.getUserId(), txn.getId(), m.getId(), m.getCounterparty(),
                m.getSender(), m.getBody(), m.getTemplateKey()));
        events.publishEvent(TransactionsIngestedEvent.of(m.getUserId(), List.of(txn)));
        setOpeningBalanceFromFirst(m, managed, txn);
    }

    /**
     * A generic account with no opening balance and no other transaction on or before this one's date: the balance after
     * this message minus its signed amount is what the account held before it, so the balance shown equals the message's.
     * A value the user set is never replaced.
     */
    private void setOpeningBalanceFromFirst(SmsMessage m, Account account, Transaction txn) {
        if (account.getType() != AccountType.generic || m.getBalanceAfter() == null || txn.getAmount() == null) {
            return;
        }
        AccountBankDetails details = account.getBankDetails();
        if (details != null && details.getOpeningBalance() != null) {
            return;
        }
        if (transactions.countOtherOnOrBefore(m.getUserId(), account.getId(), txn.getDate(), txn.getId()) > 0) {
            return;
        }
        BigDecimal signed = txn.getType() == TransactionType.CREDIT ? txn.getAmount() : txn.getAmount().negate();
        BigDecimal opening = m.getBalanceAfter().subtract(signed);
        if (details == null) {
            details = new AccountBankDetails(account, opening, null);
            details.setUser(account.getUser());
            account.setBankDetails(details);
        } else {
            details.setOpeningBalance(opening);
        }
        accounts.save(account);
    }

    /**
     * The kept message this one is the second alert of. Same reference and same direction always pairs (a refund quoting
     * the original reference has the other direction). Otherwise same amount and direction within a day, another
     * template, balances that do not disagree, references that are not both present and different, and a partner that
     * is itself kept and has no partner yet (one-to-one: two equal payments are two payments).
     */
    private Optional<UUID> findOriginal(SmsMessage m) {
        String ref = m.getRefNo() == null || m.getRefNo().isBlank() ? "" : m.getRefNo();
        Instant from = m.getReceivedAt().minus(SECOND_ALERT_WINDOW);
        Instant to = m.getReceivedAt().plus(SECOND_ALERT_WINDOW);
        UUID best = null;
        Instant bestAt = null;
        for (Object[] c : messages.findDuplicateCandidates(m.getUserId(), m.getAccountId(), m.getId(), ref, from, to)) {
            UUID id = (UUID) c[0];
            Instant at = (Instant) c[1];
            String candRef = (String) c[2];
            String candTemplate = (String) c[3];
            BigDecimal amount = (BigDecimal) c[4];
            TransactionType direction = (TransactionType) c[5];
            BigDecimal balance = (BigDecimal) c[6];
            long partners = ((Number) c[7]).longValue();
            if (direction != m.getDirection()) {
                continue;
            }
            boolean refMatch = !ref.isEmpty() && ref.equals(candRef);
            if (!refMatch) {
                boolean refsDiffer = !ref.isEmpty() && candRef != null && !candRef.isBlank();
                boolean inWindow = !at.isBefore(from) && !at.isAfter(to);
                boolean sameAmount = amount != null && m.getAmount().compareTo(amount) == 0;
                boolean balancesAgree = m.getBalanceAfter() == null || balance == null || m.getBalanceAfter().compareTo(balance) == 0;
                if (refsDiffer || !inWindow || !sameAmount || Objects.equals(m.getTemplateKey(), candTemplate)
                        || !balancesAgree || partners > 0) {
                    continue;
                }
            }
            if (best == null || at.isBefore(bestAt) || (at.equals(bestAt) && id.compareTo(best) < 0)) {
                best = id;
                bestAt = at;
            }
        }
        return Optional.ofNullable(best);
    }

    private AlertMatcher.Alert alertOf(SmsMessage m, Account account) {
        Set<String> refs = new java.util.LinkedHashSet<>(ReferenceNumbers.extract(m.getBody()));
        if (m.getRefNo() != null && !m.getRefNo().isBlank()) {
            refs.add(m.getRefNo().toUpperCase(java.util.Locale.ROOT));
        }
        LocalDate date = m.getTxnDate() != null ? m.getTxnDate() : m.getReceivedAt().atZone(AppTime.zone()).toLocalDate();
        // Never the message text: it is stored encrypted, a transaction description is not.
        String text = m.getCounterparty() != null && !m.getCounterparty().isBlank() ? m.getCounterparty()
                : catalogue.find(m.getSender()).map(SenderCatalogue.Sender::provider).orElse(m.getSender());
        return new AlertMatcher.Alert(account.getUser(), account, null, m.getDirection(), m.getAmount(), "INR", date,
                text, null, refs, "sms:" + m.getSender(), m.getReceivedAt(), sourceId(m), TransactionSource.sms_alert);
    }
}
