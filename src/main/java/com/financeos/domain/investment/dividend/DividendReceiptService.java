package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.ConfirmDividendMatchesRequest;
import com.financeos.api.investment.dto.ConfirmDividendMatchesResponse;
import com.financeos.api.investment.dto.DividendReceiptSummaryResponse;
import com.financeos.api.investment.dto.DividendReconciliationResponse;
import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.api.investment.dto.UnrecordedDividendCreditsResponse;
import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Did the payout a dividend row predicts actually land in the bank?
 *
 * <p>Link / unlink / manual override live here, plus the reconciliation engine: unresolved rows are
 * scored against CREDITs in their expected window ({@link DividendMatcher}), each credit is handed to
 * the single dividend that scores it highest, and the user confirms. The reverse scan finds credits
 * that look like dividends but have no row at all. Nothing here mutates {@code amount} — P&amp;L and
 * XIRR keep reading the gross expectation; the only write-back is TDS, and only on request.
 */
@Service
@Transactional
public class DividendReceiptService {

    private static final Logger log = LoggerFactory.getLogger(DividendReceiptService.class);

    /** TDS write-back only when the gap is at most this share of gross (10% standard, 20% without PAN). */
    public static final BigDecimal MAX_IMPLIED_TDS_RATE = new BigDecimal("0.25");
    public static final int UNRECORDED_SCAN_DEFAULT_DAYS = 365;
    public static final int MAX_HOLDING_HINTS = 3;
    /** Payouts land in bank accounts (or a manually kept cash/wallet account), never on a card or broker ledger. */
    public static final List<AccountType> RECEIVING_ACCOUNT_TYPES = List.of(AccountType.bank_account, AccountType.generic);

    private final DividendRepository dividendRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionLinkRepository transactionLinkRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionReferenceValidator transactionValidator;
    private final DividendReceiptStatusResolver resolver;

    public DividendReceiptService(DividendRepository dividendRepository,
                                  TransactionRepository transactionRepository,
                                  TransactionLinkRepository transactionLinkRepository,
                                  HoldingRepository holdingRepository,
                                  TransactionReferenceValidator transactionValidator,
                                  DividendReceiptStatusResolver resolver) {
        this.dividendRepository = dividendRepository;
        this.transactionRepository = transactionRepository;
        this.transactionLinkRepository = transactionLinkRepository;
        this.holdingRepository = holdingRepository;
        this.transactionValidator = transactionValidator;
        this.resolver = resolver;
    }

    // --- link / unlink / manual override ------------------------------------------------------

    /** Attach (or replace) the bank credit. Re-sending the current credit is a no-op. */
    public DividendResponse linkTransaction(UUID dividendId, UUID transactionId, boolean updateTds) {
        if (transactionId == null) {
            throw new ValidationException("transactionId is required");
        }
        Dividend dividend = getOwned(dividendId);
        if (dividend.getTransaction() != null && transactionId.equals(dividend.getTransaction().getId())) {
            return resolver.toResponse(dividend);
        }
        Transaction transaction = transactionValidator.validateForDividend(transactionId);
        dividend.setTransaction(transaction);
        dividend.setReceiptStatus(null); // a linked row IS received; any manual note is stale
        boolean tdsWritten = updateTds && applyTdsFromReceipt(dividend, transaction);
        Dividend saved = dividendRepository.save(dividend);
        logLinkEvent(Events.DIVIDEND_LINKED, saved, transactionId, tdsWritten);
        return resolver.toResponse(saved);
    }

    /** Detach the linked credit. Idempotent: an unlinked row is left as is. */
    public void unlinkTransaction(UUID dividendId) {
        Dividend dividend = getOwned(dividendId);
        if (dividend.getTransaction() == null) {
            return;
        }
        UUID previous = dividend.getTransaction().getId();
        dividend.setTransaction(null);
        dividendRepository.save(dividend);
        logLinkEvent(Events.DIVIDEND_UNLINKED, dividend, previous, false);
    }

    /** Set or clear ({@code null}) the manual receipt note. Only the manual values are accepted. */
    public DividendResponse setReceiptStatus(UUID dividendId, DividendReceiptStatus status) {
        Dividend dividend = getOwned(dividendId);
        if (status != null && !status.isManual()) {
            throw new ValidationException("Only received_untracked or not_received can be set manually; "
                    + "the other statuses are derived");
        }
        if (status != null && dividend.getTransaction() != null) {
            throw new ValidationException("This dividend is linked to a bank transaction; unlink it before changing its receipt status");
        }
        dividend.setReceiptStatus(status);
        Dividend saved = dividendRepository.save(dividend);
        log.info("Dividend receipt status set: dividendId={}, status={}", saved.getId(), status,
                StructuredArguments.keyValue("event", Events.DIVIDEND_RECEIPT_STATUS_SET),
                StructuredArguments.keyValue("dividendId", String.valueOf(saved.getId())),
                StructuredArguments.keyValue("status", status != null ? status.name() : ""));
        return resolver.toResponse(saved);
    }

    /**
     * Write {@code tds = gross − received} when no TDS is recorded and the gap is a plausible
     * deduction (0 &lt; gap ≤ {@value #MAX_IMPLIED_TDS_RATE} × gross). Package-private for tests.
     */
    static boolean applyTdsFromReceipt(Dividend dividend, Transaction transaction) {
        if (dividend.getTds() != null || dividend.getAmount() == null || transaction.getAmount() == null) {
            return false;
        }
        BigDecimal gap = dividend.getAmount().subtract(transaction.getAmount());
        if (gap.signum() <= 0) {
            return false;
        }
        if (gap.compareTo(dividend.getAmount().multiply(MAX_IMPLIED_TDS_RATE)) > 0) {
            return false;
        }
        dividend.setTds(gap.setScale(2, RoundingMode.HALF_UP));
        return true;
    }

    // --- reconciliation ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DividendReconciliationResponse getReconciliation(UUID brokerAccountId, LocalDate from, LocalDate to) {
        DividendReceiptStatusResolver.Context ctx = resolver.context();
        List<Dividend> unresolved = dividendRepository.findUnresolvedForReconciliation(brokerAccountId, from, to);
        if (unresolved.isEmpty()) {
            return new DividendReconciliationResponse(List.of(), ctx.coverageEnd(), 0, 0);
        }

        Set<UUID> referenced = transactionValidator.getAllReferencedTransactionIds();
        Map<UUID, List<DividendMatcher.Scored>> scoredByDividend = new LinkedHashMap<>();
        Map<UUID, Transaction> candidateTxns = new HashMap<>();

        for (Dividend d : unresolved) {
            List<DividendMatcher.Scored> scored = new ArrayList<>();
            if (d.getAmount() != null && d.getAmount().signum() > 0) {
                DividendReceiptWindows.Window window = DividendReceiptWindows.of(d);
                LocalDate base = DividendReceiptWindows.baseDate(d);
                List<Transaction> candidates = transactionRepository.findCreditCandidates(
                        RECEIVING_ACCOUNT_TYPES,
                        DividendMatcher.bandLow(d.getAmount()), DividendMatcher.bandHigh(d.getAmount()),
                        window.from(), window.to());
                for (Transaction t : candidates) {
                    if (referenced.contains(t.getId())) {
                        continue;
                    }
                    DividendMatcher.score(d, t, base).ifPresent(s -> {
                        scored.add(s);
                        candidateTxns.put(t.getId(), t);
                    });
                }
            }
            scoredByDividend.put(d.getId(), scored);
        }

        Set<UUID> inLinkGroups = linkGroupMembers(candidateTxns.keySet());

        // Greedy: each credit goes to the dividend that scores it highest; ties → nearer base date →
        // older row (unresolved is newest-first, so compare createdAt explicitly).
        Map<UUID, Dividend> winnerByTxn = new HashMap<>();
        Map<UUID, DividendMatcher.Scored> winningScore = new HashMap<>();
        for (Dividend d : unresolved) {
            for (DividendMatcher.Scored s : scoredByDividend.get(d.getId())) {
                UUID txId = s.transaction().getId();
                if (inLinkGroups.contains(txId)) {
                    continue;
                }
                Dividend current = winnerByTxn.get(txId);
                if (current == null || beats(s, d, winningScore.get(txId), current)) {
                    winnerByTxn.put(txId, d);
                    winningScore.put(txId, s);
                }
            }
        }

        List<DividendReconciliationResponse.DividendReconciliationItem> items = new ArrayList<>();
        for (Dividend d : unresolved) {
            List<DividendMatcher.Scored> all = scoredByDividend.get(d.getId());
            List<DividendMatcher.Scored> assigned = all.stream()
                    .filter(s -> d == winnerByTxn.get(s.transaction().getId()))
                    .sorted(Comparator.comparingInt(DividendMatcher.Scored::score).reversed()
                            .thenComparingLong(s -> dateDistance(s, d)))
                    .toList();
            logMatchAttempt(d, all.size(), assigned.size());
            if (assigned.isEmpty()) {
                continue;
            }
            items.add(new DividendReconciliationResponse.DividendReconciliationItem(
                    resolver.toResponse(d, ctx),
                    assigned.stream().map(DividendReceiptService::toCandidate).toList()));
        }
        return new DividendReconciliationResponse(items, ctx.coverageEnd(), unresolved.size(), items.size());
    }

    /** Partial success is reported, not hidden: each item ends up in {@code linked} or {@code skipped}. */
    public ConfirmDividendMatchesResponse confirmMatches(ConfirmDividendMatchesRequest request) {
        List<DividendResponse> linked = new ArrayList<>();
        List<ConfirmDividendMatchesResponse.SkippedDividendMatch> skipped = new ArrayList<>();
        for (ConfirmDividendMatchesRequest.ConfirmDividendMatchItem item : request.items()) {
            try {
                linked.add(linkTransaction(item.dividendId(), item.transactionId(), item.updateTds()));
            } catch (ValidationException | ResourceNotFoundException e) {
                skipped.add(new ConfirmDividendMatchesResponse.SkippedDividendMatch(item.dividendId(), e.getMessage()));
            }
        }
        return new ConfirmDividendMatchesResponse(linked, skipped);
    }

    @Transactional(readOnly = true)
    public DividendReceiptSummaryResponse getReceiptSummary(UUID holdingId, UUID brokerAccountId, UUID instrumentId, DividendType type) {
        DividendReceiptStatusResolver.Context ctx = resolver.context();
        List<Object[]> rows = dividendRepository.findReceiptRowsForSummary(holdingId, brokerAccountId, instrumentId, type);

        Map<DividendReceiptStatus, long[]> counts = new EnumMap<>(DividendReceiptStatus.class);
        Map<DividendReceiptStatus, BigDecimal> expected = new EnumMap<>(DividendReceiptStatus.class);
        Map<DividendReceiptStatus, BigDecimal> received = new EnumMap<>(DividendReceiptStatus.class);
        for (DividendReceiptStatus s : DividendReceiptStatus.values()) {
            counts.put(s, new long[1]);
            expected.put(s, BigDecimal.ZERO);
            received.put(s, BigDecimal.ZERO);
        }

        for (Object[] row : rows) {
            String source = (String) row[0];
            LocalDate exDate = (LocalDate) row[1];
            LocalDate payDate = (LocalDate) row[2];
            DividendReceiptStatus manual = (DividendReceiptStatus) row[3];
            BigDecimal amount = row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO;
            BigDecimal tds = row[5] != null ? (BigDecimal) row[5] : BigDecimal.ZERO;
            boolean linkedRow = row[6] != null;
            BigDecimal txnAmount = row[7] != null ? (BigDecimal) row[7] : null;

            DividendReceiptStatus status = DividendReceiptWindows.derive(source, exDate, payDate, linkedRow, manual,
                    ctx.today(), ctx.coverageEnd());
            counts.get(status)[0]++;
            expected.put(status, expected.get(status).add(amount.subtract(tds)));
            if (txnAmount != null) {
                received.put(status, received.get(status).add(txnAmount));
            }
        }

        List<DividendReceiptSummaryResponse.DividendReceiptBucket> buckets = new ArrayList<>();
        for (DividendReceiptStatus s : DividendReceiptStatus.values()) {
            buckets.add(new DividendReceiptSummaryResponse.DividendReceiptBucket(s, counts.get(s)[0], expected.get(s), received.get(s)));
        }
        return new DividendReceiptSummaryResponse(buckets, ctx.coverageEnd(), rows.size());
    }

    /** Bank credits that look like dividends (keyword in the narration) but reference nothing. */
    @Transactional(readOnly = true)
    public UnrecordedDividendCreditsResponse scanUnrecordedCredits(LocalDate from, LocalDate to) {
        LocalDate today = AppTime.today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(UNRECORDED_SCAN_DEFAULT_DAYS);
        if (start.isAfter(end)) {
            throw new ValidationException("'from' must be on or before 'to'");
        }

        List<Transaction> credits = transactionRepository.findDividendLikeCredits(RECEIVING_ACCOUNT_TYPES, start, end);
        Set<UUID> referenced = transactionValidator.getAllReferencedTransactionIds();
        List<Transaction> candidates = credits.stream()
                .filter(t -> !referenced.contains(t.getId()))
                .filter(t -> DividendMatcher.hasKeyword(DividendMatcher.effectiveDescription(t), DividendType.dividend))
                .sorted(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Set<UUID> inLinkGroups = linkGroupMembers(candidates.stream().map(Transaction::getId).collect(Collectors.toSet()));

        List<Holding> holdings = holdingRepository.findAllWithDetails();
        List<UnrecordedDividendCreditsResponse.UnrecordedDividendCredit> items = new ArrayList<>();
        for (Transaction t : candidates) {
            if (inLinkGroups.contains(t.getId())) {
                continue;
            }
            String desc = DividendMatcher.effectiveDescription(t);
            List<UnrecordedDividendCreditsResponse.DividendHoldingHint> hints = holdings.stream()
                    .filter(h -> h.getInstrument() != null && h.getBrokerAccount() != null)
                    .map(h -> new UnrecordedDividendCreditsResponse.DividendHoldingHint(
                            h.getId(),
                            h.getBrokerAccount().getId(),
                            h.getBrokerAccount().getName(),
                            h.getInstrument().getId(),
                            h.getInstrument().getName(),
                            h.getInstrument().getSymbol(),
                            DividendMatcher.symbolMatches(h.getInstrument(), desc) ? 1.0 : DividendMatcher.nameScore(h.getInstrument(), desc)))
                    .filter(hint -> hint.nameScore() >= DividendMatcher.NAME_SCORE_THRESHOLD)
                    .sorted(Comparator.comparingDouble(UnrecordedDividendCreditsResponse.DividendHoldingHint::nameScore).reversed()
                            .thenComparing(UnrecordedDividendCreditsResponse.DividendHoldingHint::instrumentName,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(MAX_HOLDING_HINTS)
                    .toList();
            items.add(new UnrecordedDividendCreditsResponse.UnrecordedDividendCredit(TransactionResponse.from(t), hints));
        }
        return new UnrecordedDividendCreditsResponse(items, start, end);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Dividend getOwned(UUID dividendId) {
        Dividend dividend = dividendRepository.findById(dividendId)
                .orElseThrow(() -> new ResourceNotFoundException("Dividend", dividendId));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (dividend.getUser() == null || !dividend.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Dividend", dividendId);
        }
        return dividend;
    }

    /** Candidate ids that already sit in a transaction_links group (a transfer leg is never a payout). */
    private Set<UUID> linkGroupMembers(Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> ids = candidateIds instanceof Set<UUID> set ? set : Set.copyOf(candidateIds);
        return transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(ids).stream()
                .flatMap(link -> link.getMembers().stream())
                .map(member -> member.getTransaction().getId())
                .filter(ids::contains)
                .collect(Collectors.toSet());
    }

    private static boolean beats(DividendMatcher.Scored candidate, Dividend candidateOwner,
                                 DividendMatcher.Scored incumbent, Dividend incumbentOwner) {
        if (candidate.score() != incumbent.score()) {
            return candidate.score() > incumbent.score();
        }
        long dNew = dateDistance(candidate, candidateOwner);
        long dOld = dateDistance(incumbent, incumbentOwner);
        if (dNew != dOld) {
            return dNew < dOld;
        }
        Instant cNew = candidateOwner.getCreatedAt();
        Instant cOld = incumbentOwner.getCreatedAt();
        return cNew != null && cOld != null && cNew.isBefore(cOld);
    }

    private static long dateDistance(DividendMatcher.Scored s, Dividend d) {
        LocalDate txnDate = s.transaction().getDate();
        LocalDate base = DividendReceiptWindows.baseDate(d);
        if (txnDate == null || base == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(ChronoUnit.DAYS.between(base, txnDate));
    }

    private static DividendReconciliationResponse.DividendMatchCandidate toCandidate(DividendMatcher.Scored s) {
        return new DividendReconciliationResponse.DividendMatchCandidate(
                TransactionResponse.from(s.transaction()), s.tier(), s.score(), s.reasons(), s.impliedTds(), s.variance());
    }

    private void logMatchAttempt(Dividend d, int candidateCount, int matchedCount) {
        log.info("Dividend match attempted: dividendId={}, candidates={}, matched={}", d.getId(), candidateCount, matchedCount,
                StructuredArguments.keyValue("event", Events.DIVIDEND_MATCH_ATTEMPTED),
                StructuredArguments.keyValue("dividendId", String.valueOf(d.getId())),
                StructuredArguments.keyValue("source", DividendReceiptWindows.sourceOf(d.getSource())),
                StructuredArguments.keyValue("candidateCount", candidateCount),
                StructuredArguments.keyValue("matchedCount", matchedCount));
    }

    private void logLinkEvent(String event, Dividend dividend, UUID transactionId, boolean tdsWritten) {
        log.info("Dividend {}: dividendId={}, txnId={}", event, dividend.getId(), transactionId,
                StructuredArguments.keyValue("event", event),
                StructuredArguments.keyValue("dividendId", String.valueOf(dividend.getId())),
                StructuredArguments.keyValue("holdingId", dividend.getHolding() != null ? String.valueOf(dividend.getHolding().getId()) : ""),
                StructuredArguments.keyValue("txnId", String.valueOf(transactionId)),
                StructuredArguments.keyValue("tdsWritten", tdsWritten));
    }
}
