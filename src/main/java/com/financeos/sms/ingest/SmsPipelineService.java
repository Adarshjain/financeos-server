package com.financeos.sms.ingest;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.match.AlertMatcher;
import com.financeos.domain.transaction.match.ReferenceNumbers;
import com.financeos.sms.line.SmsLineRepository;
import com.financeos.sms.read.SmsReadStatus;
import com.financeos.sms.read.SmsReader;
import com.financeos.sms.read.SmsReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
 * ledger without changing anything (shadow). Re-runnable for a message, so a template change can be replayed.
 */
@Service
public class SmsPipelineService {

    private static final Logger log = LoggerFactory.getLogger(SmsPipelineService.class);
    private static final Duration SECOND_ALERT_WINDOW = Duration.ofDays(1);

    private final SmsMessageRepository messages;
    private final SmsLineRepository lines;
    private final AccountRepository accounts;
    private final AlertMatcher matcher;
    private final SmsReader reader;
    private final boolean postingEnabled;

    public SmsPipelineService(SmsMessageRepository messages, SmsLineRepository lines, AccountRepository accounts, AlertMatcher matcher, SmsReader reader,
                              @Value("${financeos.sms.posting-enabled:false}") boolean postingEnabled) {
        this.messages = messages;
        this.lines = lines;
        this.accounts = accounts;
        this.matcher = matcher;
        this.reader = reader;
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
            log.warn("sms.read.failed message={} error={}", m.getId(), e.toString());
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

    /** Decides the outcome of one message (clearing the old one first). Only READ messages of an account get one. */
    public void decide(SmsMessage m, Account account) {
        m.setOutcome(null);
        m.setDuplicateOf(null);
        m.setTransactionId(null);
        if (m.getReadStatus() != SmsReadStatus.READ || account == null || m.getAmount() == null
                || m.getDirection() == null) {
            return;
        }
        Optional<UUID> original = findOriginal(m);
        if (original.isPresent()) {
            m.setOutcome(SmsOutcome.DUPLICATE);
            m.setDuplicateOf(original.get());
            return;
        }
        Optional<Transaction> match = matcher.findMatch(alertOf(m, account));
        // A transaction another message already claims cannot be claimed twice: the second equal payment is a new one.
        if (match.isPresent() && !messages.existsByUserIdAndTransactionIdAndIdNot(m.getUserId(), match.get().getId(), m.getId())) {
            m.setOutcome(SmsOutcome.SHADOW_ATTACH);
            m.setTransactionId(match.get().getId());
        } else {
            m.setOutcome(SmsOutcome.SHADOW_CREATE);
        }
        if (postingEnabled) {
            postForReal(m, account);
        }
    }

    /** Read then decide: the full run for a message of {@code account}. */
    public void process(SmsMessage m, Account account) {
        read(m);
        decide(m, account);
    }

    /**
     * Re-reads and re-decides every stored message of the user, in received order. A message the owner confirmed or
     * rejected keeps what they decided and is only re-decided. Returns how many messages were run.
     */
    @Transactional
    public int replay(UUID userId) {
        lockUser(userId);
        List<SmsMessage> all = messages.findByUserIdOrderByReceivedAtAscIdAsc(userId);
        for (SmsMessage m : all) {
            m.setOutcome(null);
            m.setDuplicateOf(null);
            m.setTransactionId(null);
        }
        messages.saveAllAndFlush(all);
        java.util.Map<UUID, Account> byId = new java.util.HashMap<>();
        accounts.findByUserId(userId).forEach(a -> byId.put(a.getId(), a));
        for (SmsMessage m : all) {
            if (m.getUserDecision() == null) {
                read(m);
            }
            decide(m, m.getAccountId() == null ? null : byId.get(m.getAccountId()));
            messages.saveAndFlush(m);
        }
        return all.size();
    }

    /** The seam where posting for real goes: create or attach the transaction and record CREATED / ATTACHED. Next phase. */
    private void postForReal(SmsMessage m, Account account) {
        // Intentionally empty in this phase: shadow outcomes are recorded either way.
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

    private static AlertMatcher.Alert alertOf(SmsMessage m, Account account) {
        Set<String> refs = new java.util.LinkedHashSet<>(ReferenceNumbers.extract(m.getBody()));
        if (m.getRefNo() != null && !m.getRefNo().isBlank()) {
            refs.add(m.getRefNo().toUpperCase(java.util.Locale.ROOT));
        }
        LocalDate date = m.getTxnDate() != null ? m.getTxnDate() : m.getReceivedAt().atZone(AppTime.zone()).toLocalDate();
        return new AlertMatcher.Alert(account.getUser(), account, null, m.getDirection(), m.getAmount(), "INR", date,
                m.getBody(), null, refs, "sms:" + m.getSender(), m.getReceivedAt(), null);
    }
}
