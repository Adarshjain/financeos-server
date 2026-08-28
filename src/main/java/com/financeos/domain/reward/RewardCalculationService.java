package com.financeos.domain.reward;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.AccountCard;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure compute-on-read rewards calculator. No persisted reward state: every request
 * re-evaluates the account's rules over the transactions, so edits, refund links and
 * rule changes are always reflected.
 *
 * Correctness invariant: caps are evaluated over FULL cap windows intersecting the
 * requested display range (a filter starting mid-month must still see the cap headroom
 * consumed earlier in that month). Only lines whose effective date falls inside the
 * display range are summed into the report.
 */
@Service
@Slf4j
public class RewardCalculationService {

    private static final Set<LinkType> NEVER_EARN_LINK_TYPES =
            Set.of(LinkType.TRANSFER, LinkType.CC_PAYMENT, LinkType.REVERSAL);

    static final String UNIT_RUPEES = "RUPEES";
    static final String UNIT_POINTS = "POINTS";

    private final RewardRuleRepository rewardRuleRepository;
    private final RewardRuleService rewardRuleService;
    private final RewardMilestoneRepository rewardMilestoneRepository;
    private final RewardMilestoneService rewardMilestoneService;
    private final TransactionRepository transactionRepository;
    private final TransactionLinkRepository transactionLinkRepository;
    private final StatementRepository statementRepository;
    private final AccountRepository accountRepository;
    private final com.financeos.domain.account.card.AccountCardRepository cardRepository;

    public RewardCalculationService(RewardRuleRepository rewardRuleRepository,
                                    RewardRuleService rewardRuleService,
                                    RewardMilestoneRepository rewardMilestoneRepository,
                                    RewardMilestoneService rewardMilestoneService,
                                    TransactionRepository transactionRepository,
                                    TransactionLinkRepository transactionLinkRepository,
                                    StatementRepository statementRepository,
                                    AccountRepository accountRepository,
                                    com.financeos.domain.account.card.AccountCardRepository cardRepository) {
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardRuleService = rewardRuleService;
        this.rewardMilestoneRepository = rewardMilestoneRepository;
        this.rewardMilestoneService = rewardMilestoneService;
        this.transactionRepository = transactionRepository;
        this.transactionLinkRepository = transactionLinkRepository;
        this.statementRepository = statementRepository;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
    }

    // ---------- public API ----------

    @Transactional(readOnly = true)
    public RewardReportResponse report(UUID accountId, LocalDate from, LocalDate to) {
        long startMs = System.currentTimeMillis();
        Evaluation eval = evaluate(accountId, from, to, true, "manual");
        RewardReportResponse response = buildReport(eval, from, to);
        long durationMs = System.currentTimeMillis() - startMs;

        log.info("Reward report viewed: cardId={}, durationMs={}", accountId, durationMs,
                StructuredArguments.keyValue("event", Events.REWARD_REPORT_VIEWED),
                StructuredArguments.keyValue("cardId", accountId != null ? accountId.toString() : ""),
                StructuredArguments.keyValue("cycleStart", from != null ? from.toString() : ""),
                StructuredArguments.keyValue("cycleEnd", to != null ? to.toString() : ""),
                StructuredArguments.keyValue("txnCount", eval.lines.size()),
                StructuredArguments.keyValue("durationMs", durationMs));

        return response;
    }

    @Transactional(readOnly = true)
    public List<RewardLineResponse> lines(UUID accountId, LocalDate from, LocalDate to, UUID ruleId) {
        // Lines never use milestone results — skip their loading and window expansion
        // (a CALENDAR_YEAR milestone would otherwise force a whole-year fetch per page click).
        Evaluation eval = evaluate(accountId, from, to, false);
        return eval.lines.stream()
                .filter(l -> !l.effectiveDate().isBefore(from) && !l.effectiveDate().isAfter(to))
                .filter(l -> ruleId == null || ruleId.equals(l.ruleId()))
                .sorted(Comparator.comparing(RewardLineResponse::effectiveDate).reversed()
                        .thenComparing(l -> l.transactionId().toString()))
                .toList();
    }

    // ---------- evaluation ----------

    record Window(LocalDate start, LocalDate end, boolean cycleFallback) {
    }

    record CounterKey(String owner, UUID cardId, LocalDate windowStart) {
    }

    record MilestoneWithEligibility(RewardMilestone milestone, MilestoneEligibility eligibility) {
    }

    static final class Evaluation {
        final List<RewardRule> rules;
        final List<MilestoneWithEligibility> milestones;
        final List<RewardLineResponse> lines = new ArrayList<>();
        /** capKey (owner, cardId, windowStart) -> used amount in the rule's output unit. */
        final Map<CounterKey, BigDecimal> capUsed = new HashMap<>();
        /** tiered rules: (ruleId, cardId, tierWindowStart) -> running matched basis in the window. */
        final Map<CounterKey, BigDecimal> tierProgress = new HashMap<>();
        /** parsed tier schedules per tiered rule. */
        final Map<UUID, List<RewardTier>> tierSchedules = new HashMap<>();
        /** eligible debit txn id -> netted basis, display-range membership decided later. */
        final List<EligibleTxn> eligible = new ArrayList<>();
        final Map<UUID, String> cardLabels = new HashMap<>();
        /**
         * Every card on the account, open AND closed. byCard must iterate this: a transaction
         * on a closed card carries that card's id, so iterating only open cards would drop it
         * from the breakdown without putting it in Unattributed either — silently breaking the
         * partition invariant (Σ byCard == summary).
         */
        List<AccountCard> allCards = List.of();
        int cardCount = 0;
        /**
         * Distinct transactions whose missing attribution affected a card-scoped decision.
         * A Set, not a counter: capKey runs in both clamp() and consumeCap(), tierKey in both
         * recordTierProgress() and accrueTiered(), and matches() runs once per rule — so a
         * plain ++ would report six-plus "transactions" for a single one, and the UI renders
         * this number as a transaction count.
         */
        final Set<UUID> perCardAttributionIncompleteTxnIds = new HashSet<>();
        /** Scratch flag for the transaction currently being evaluated; collected by evaluateTransaction. */
        boolean currentTxnAttributionIncomplete = false;
        boolean cycleFallback = false;
        boolean anniversaryFallback = false;
        List<Statement> statements = List.of();
        LocalDate anniversaryDate;
        UUID primaryCardId;
        UUID soleCardId;

        Evaluation(List<RewardRule> rules, List<MilestoneWithEligibility> milestones) {
            this.rules = rules;
            this.milestones = milestones;
        }
    }

    private record EligibleTxn(UUID id, LocalDate effectiveDate, BigDecimal basis,
                               BigDecimal instantDiscount, BigDecimal convenienceFee,
                               BigDecimal amount, String mcc, Set<UUID> categoryIds,
                               UUID cardId) {
    }

    // ---------- predicate input abstraction ----------

    record TxnFacts(
            LocalDate date,
            LocalDate effectiveDate,
            BigDecimal amount,
            BigDecimal basis,
            /** Labeled surcharge inside {@code amount}; null when none was recorded. */
            BigDecimal convenienceFee,
            String mcc,
            TransactionChannel channel,
            Set<UUID> categoryIds,
            String description,
            String sourcedDescription,
            boolean isEmi,
            boolean isIntl,
            UUID cardId) {

        static TxnFacts from(Transaction txn, BigDecimal basis, LocalDate effectiveDate) {
            Set<UUID> catIds = txn.getCategories() != null ? txn.getCategories().stream()
                    .map(tc -> tc.getCategory().getId())
                    .collect(java.util.stream.Collectors.toSet()) : Set.of();
            UUID cardId = txn.getCard() != null ? txn.getCard().getId() : null;
            return new TxnFacts(
                    txn.getDate(),
                    effectiveDate,
                    txn.getAmount(),
                    basis,
                    txn.getConvenienceFee(),
                    txn.getMcc(),
                    txn.getChannel(),
                    catIds,
                    txn.getDescription(),
                    txn.getSourcedDescription(),
                    Boolean.TRUE.equals(txn.getIsEmi()),
                    Boolean.TRUE.equals(txn.getIsInternational()),
                    cardId);
        }
    }

    record TxnRuleResolution(
            RewardRule rule,
            BigDecimal earned,
            RewardLineReason reason,
            BigDecimal basis) {
    }

    Evaluation evaluate(UUID accountId, LocalDate from, LocalDate to, boolean includeMilestones) {
        return evaluate(accountId, from, to, includeMilestones, "manual");
    }

    Evaluation evaluate(UUID accountId, LocalDate from, LocalDate to, boolean includeMilestones, String trigger) {
        long startMs = System.currentTimeMillis();
        if (from == null || to == null || from.isAfter(to)) {
            throw new ValidationException("A valid from/to date range is required.");
        }
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to view rewards for this account.");
        }

        List<RewardRule> rules = rewardRuleRepository.findByAccountIdOrderByPriorityDesc(accountId);
        // Only milestones whose active range intersects the display range matter here.
        List<MilestoneWithEligibility> milestones = !includeMilestones ? List.of()
                : rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(accountId).stream()
                        .filter(m -> (m.getActiveFrom() == null || !m.getActiveFrom().isAfter(to))
                                && (m.getActiveTo() == null || m.getActiveTo().isAfter(from)))
                        .map(m -> new MilestoneWithEligibility(m, rewardMilestoneService.parseEligibility(m)))
                        .toList();
        // Tiered rules with a broken configuration (unparseable tiers JSON or a null
        // tier window) are excluded from evaluation entirely: earning zero while still
        // terminating the exclusive chain would silently suppress lower-priority rules.
        List<RewardRule> usableRules = new ArrayList<>();
        Map<UUID, List<RewardTier>> schedules = new HashMap<>();
        for (RewardRule rule : rules) {
            if (rule.isTiered()) {
                List<RewardTier> tiers = rewardRuleService.parseTiers(rule);
                if (tiers.isEmpty() || rule.getTierWindow() == null) {
                    log.warn("Reward rule {} has a broken tier configuration; skipping it in evaluation", rule.getId());
                    log.debug("Reward rule skipped: ruleId={}, reason=broken-tier-config", rule.getId(),
                            StructuredArguments.keyValue("event", Events.REWARD_RULE_SKIPPED),
                            StructuredArguments.keyValue("ruleId", rule.getId().toString()),
                            StructuredArguments.keyValue("reason", "broken-tier-config"));
                    continue;
                }
                schedules.put(rule.getId(), tiers);
            }
            usableRules.add(rule);
        }
        Evaluation eval = new Evaluation(usableRules, milestones);
        eval.tierSchedules.putAll(schedules);
        eval.anniversaryDate = account.getRewardAnniversaryDate();
        // ALL cards, not just open ones: a card closed mid-period still owns the transactions
        // made on it, and both the byCard breakdown and the "is this a multi-card account?"
        // question must account for it (a closed add-on must not make the section vanish).
        List<AccountCard> allCards = cardRepository != null
                ? cardRepository.findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(accountId)
                : List.of();
        eval.allCards = allCards;
        eval.cardCount = allCards.size();
        // Headroom folds into the OPEN primary; a closed primary is only a last resort.
        AccountCard primaryCard = allCards.stream()
                .filter(c -> c.isPrimary() && c.getClosedOn() == null)
                .findFirst()
                .orElseGet(() -> allCards.stream().filter(AccountCard::isPrimary).findFirst().orElse(null));
        eval.primaryCardId = primaryCard != null ? primaryCard.getId() : null;
        // "Exactly one card" means one card total — with a closed card in the picture an
        // unattributed transaction could have been made on either, so it stays unattributed.
        eval.soleCardId = allCards.size() == 1 ? allCards.get(0).getId() : null;
        for (AccountCard c : allCards) {
            String lbl = c.getLabel() != null && !c.getLabel().isBlank()
                    ? c.getLabel()
                    : (c.getHolderName() != null && !c.getHolderName().isBlank()
                            ? c.getHolderName()
                            : ("•••• " + c.getLast4()));
            eval.cardLabels.put(c.getId(), lbl);
        }
        rules = usableRules;
        eval.statements = statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId).stream()
                .filter(s -> s.getCard() == null || s.getCard().isPrimary())
                .filter(s -> s.getVerdict() != StatementVerdict.REJECTED)
                .filter(s -> s.getPeriodStart() != null && s.getPeriodEnd() != null)
                .toList();

        // Expand to full cap windows so headroom at the range edges is correct.
        // Expansion probes must not set the report's fallback flag (markFallback=false):
        // only windows actually used by reward lines count as real fallbacks.
        LocalDate expandedFrom = from;
        LocalDate expandedTo = to;
        List<CapWindow> windowTypes = new ArrayList<>();
        for (RewardRule rule : rules) {
            if (rule.hasPeriodCap()) {
                windowTypes.add(effectiveCapWindow(rule));
            }
            // Tiered rules need full-window running totals just like caps do.
            if (rule.isTiered() && rule.getTierWindow() != null) {
                windowTypes.add(rule.getTierWindow());
            }
        }
        for (MilestoneWithEligibility milestone : milestones) {
            MilestoneWindow milestoneWindow = milestone.milestone().getWindowType();
            if (milestoneWindow != MilestoneWindow.ONE_TIME) {
                windowTypes.add(milestoneWindow.asCapWindow());
            }
        }
        // A one-time milestone's single window IS its active range — cover it fully so
        // progress reflects the whole offer period, not just the display slice.
        for (MilestoneWithEligibility entry : milestones) {
            RewardMilestone milestone = entry.milestone();
            if (milestone.getWindowType() == MilestoneWindow.ONE_TIME) {
                if (milestone.getActiveFrom().isBefore(expandedFrom)) {
                    expandedFrom = milestone.getActiveFrom();
                }
                LocalDate oneTimeEnd = milestone.getActiveTo().minusDays(1);
                if (oneTimeEnd.isAfter(expandedTo)) {
                    expandedTo = oneTimeEnd;
                }
            }
        }
        for (CapWindow windowType : windowTypes) {
            Window first = windowContaining(windowType, from, eval, false);
            Window last = windowContaining(windowType, to, eval, false);
            if (first.start().isBefore(expandedFrom)) {
                expandedFrom = first.start();
            }
            if (last.end().isAfter(expandedTo)) {
                expandedTo = last.end();
            }
            // A statement gap mid-range falls back to a calendar-month window that can
            // start before the statement-based expansion — cover those months too, so
            // cap/milestone consumption in the uncovered days is never missed.
            if (windowType == CapWindow.STATEMENT_CYCLE) {
                Window monthFirst = windowContaining(CapWindow.CALENDAR_MONTH, from, eval, false);
                Window monthLast = windowContaining(CapWindow.CALENDAR_MONTH, to, eval, false);
                if (monthFirst.start().isBefore(expandedFrom)) {
                    expandedFrom = monthFirst.start();
                }
                if (monthLast.end().isAfter(expandedTo)) {
                    expandedTo = monthLast.end();
                }
            }
        }

        List<Transaction> transactions = transactionRepository.findForRewardEvaluation(accountId, expandedFrom, expandedTo);
        transactions = new ArrayList<>(transactions);
        transactions.sort(Comparator.comparing(RewardCalculationService::effectiveDate)
                .thenComparing(t -> t.getId().toString()));

        List<UUID> ids = transactions.stream().map(Transaction::getId).toList();
        Map<UUID, BigDecimal> refundTotals = new HashMap<>();
        Set<UUID> neverEarnIds = new HashSet<>();
        if (!ids.isEmpty()) {
            // Two-step load: refund members can sit outside the evaluated window, and the
            // fetch-join variant would silently drop them from the members collection.
            List<UUID> linkIds = transactionLinkRepository.findLinkIdsByMemberTransactionIds(ids);
            List<TransactionLink> links = linkIds.isEmpty()
                    ? List.of()
                    : transactionLinkRepository.findWithMembersByIdIn(linkIds);
            for (TransactionLink link : links) {
                if (NEVER_EARN_LINK_TYPES.contains(link.getType())) {
                    for (TransactionLinkMember member : link.getMembers()) {
                        neverEarnIds.add(member.getTransaction().getId());
                    }
                } else if (link.getType() == LinkType.REFUND) {
                    UUID anchorId = null;
                    BigDecimal creditSum = BigDecimal.ZERO;
                    for (TransactionLinkMember member : link.getMembers()) {
                        if (member.isAnchor()) {
                            anchorId = member.getTransaction().getId();
                        } else if (member.getTransaction().getType() == TransactionType.CREDIT) {
                            creditSum = creditSum.add(member.getTransaction().getAmount());
                        }
                    }
                    if (anchorId != null && creditSum.signum() > 0) {
                        refundTotals.merge(anchorId, creditSum, BigDecimal::add);
                    }
                }
            }
        }

        Map<UUID, Pattern> regexCache = new HashMap<>();
        for (Transaction txn : transactions) {
            if (txn.getType() != TransactionType.DEBIT) {
                continue;
            }
            evaluateTransaction(txn, refundTotals, neverEarnIds, eval, regexCache);
        }
        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Reward recompute completed: cardId={}, trigger={}, durationMs={}", accountId, trigger, durationMs,
                StructuredArguments.keyValue("event", Events.REWARD_RECOMPUTE_COMPLETED),
                StructuredArguments.keyValue("trigger", trigger != null ? trigger : "manual"),
                StructuredArguments.keyValue("cardId", accountId != null ? accountId.toString() : ""),
                StructuredArguments.keyValue("cycleStart", from != null ? from.toString() : ""),
                StructuredArguments.keyValue("cycleEnd", to != null ? to.toString() : ""),
                StructuredArguments.keyValue("txnCount", eval.lines.size()),
                StructuredArguments.keyValue("rulesEvaluated", eval.rules.size()),
                StructuredArguments.keyValue("durationMs", durationMs));
        return eval;
    }

    private void evaluateTransaction(Transaction txn, Map<UUID, BigDecimal> refundTotals,
                                     Set<UUID> neverEarnIds, Evaluation eval, Map<UUID, Pattern> regexCache) {
        LocalDate effectiveDate = effectiveDate(txn);

        if (neverEarnIds.contains(txn.getId())) {
            eval.lines.add(zeroLine(txn, effectiveDate, txn.getAmount(), RewardLineReason.TRANSFER_OR_PAYMENT, eval));
            return;
        }
        if (txn.isTransactionExcluded()) {
            eval.lines.add(zeroLine(txn, effectiveDate, txn.getAmount(), RewardLineReason.TXN_EXCLUDED, eval));
            return;
        }

        BigDecimal basis = txn.getAmount().subtract(refundTotals.getOrDefault(txn.getId(), BigDecimal.ZERO));
        if (basis.signum() <= 0) {
            eval.lines.add(zeroLine(txn, effectiveDate, BigDecimal.ZERO, RewardLineReason.FULLY_REFUNDED, eval));
            return;
        }
        Set<UUID> categoryIds = txn.getCategories().stream()
                .map(tc -> tc.getCategory().getId())
                .collect(java.util.stream.Collectors.toSet());
        UUID matchedCardId = matchCardId(txn.getCard() != null ? txn.getCard().getId() : null, eval);
        eval.eligible.add(new EligibleTxn(txn.getId(), effectiveDate, basis,
                txn.getInstantDiscount(), txn.getConvenienceFee(),
                txn.getAmount(), txn.getMcc(), categoryIds,
                matchedCardId));

        TxnFacts facts = TxnFacts.from(txn, basis, effectiveDate);
        eval.currentTxnAttributionIncomplete = false;
        List<TxnRuleResolution> resolutions = resolveTxnFacts(facts, eval, regexCache);
        if (eval.currentTxnAttributionIncomplete) {
            eval.perCardAttributionIncompleteTxnIds.add(txn.getId());
            eval.currentTxnAttributionIncomplete = false;
        }
        for (TxnRuleResolution res : resolutions) {
            if (res.rule() == null) {
                eval.lines.add(zeroLine(txn, effectiveDate, res.basis(), res.reason(), eval));
            } else {
                eval.lines.add(line(txn, effectiveDate, res.basis(), res.rule(), res.earned(), res.reason(), eval));
            }
        }
    }

    List<TxnRuleResolution> resolveTxnFacts(TxnFacts facts, Evaluation eval, Map<UUID, Pattern> regexCache) {
        List<RewardRule> matching = eval.rules.stream()
                .filter(r -> r.isActiveOn(facts.effectiveDate()))
                .filter(r -> matches(r, facts, regexCache, eval))
                .toList();
        if (matching.isEmpty()) {
            return List.of(new TxnRuleResolution(null, BigDecimal.ZERO, RewardLineReason.NO_RULE, facts.basis()));
        }

        List<TxnRuleResolution> results = new ArrayList<>();

        // 1. EXCLUSIVE chain: first rule (by priority) that can still pay; cap fall-through.
        List<RewardRule> exclusives = matching.stream().filter(r -> r.getStacking() == RuleStacking.EXCLUSIVE).toList();
        boolean emitted = false;
        RewardRule lastCapExhausted = null;
        for (RewardRule rule : exclusives) {
            BigDecimal ruleBasis = basisFor(rule, facts);
            BigDecimal raw = accrue(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
            if (raw.signum() == 0) {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, zeroAccrualReason(rule, ruleBasis), ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
                emitted = true;
                break;
            }
            BigDecimal award = clamp(rule, raw, facts.effectiveDate(), eval, facts.cardId());
            if (award.signum() > 0) {
                consumeCap(rule, award, facts.effectiveDate(), eval, facts.cardId());
                RewardLineReason reason = award.compareTo(raw) < 0 ? RewardLineReason.PARTIAL_CAP : RewardLineReason.MATCHED;
                results.add(new TxnRuleResolution(rule, award, reason, ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
                emitted = true;
                break;
            }
            lastCapExhausted = rule;
            if (rule.getOnCapExhausted() == CapExhaustedBehavior.STOP) {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, RewardLineReason.CAP_EXHAUSTED, ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
                emitted = true;
                break;
            }
            // FALL_THROUGH: try the next matching exclusive rule.
        }
        if (!emitted) {
            if (lastCapExhausted != null) {
                BigDecimal ruleBasis = basisFor(lastCapExhausted, facts);
                results.add(new TxnRuleResolution(lastCapExhausted, BigDecimal.ZERO, RewardLineReason.CAP_EXHAUSTED, ruleBasis));
                recordTierProgress(lastCapExhausted, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
            } else if (exclusives.isEmpty()) {
                // Only additive rules matched; note the absence of a base rule explicitly.
                results.add(new TxnRuleResolution(null, BigDecimal.ZERO, RewardLineReason.NO_RULE, facts.basis()));
            }
        }

        // 2. ADDITIVE rules: each pays independently with its own caps.
        for (RewardRule rule : matching) {
            if (rule.getStacking() != RuleStacking.ADDITIVE) {
                continue;
            }
            BigDecimal ruleBasis = basisFor(rule, facts);
            BigDecimal raw = accrue(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
            // Matched spend always advances the tier threshold, even when it earns 0.
            recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardId());
            if (raw.signum() == 0) {
                continue;
            }
            BigDecimal award = clamp(rule, raw, facts.effectiveDate(), eval, facts.cardId());
            if (award.signum() > 0) {
                consumeCap(rule, award, facts.effectiveDate(), eval, facts.cardId());
                RewardLineReason reason = award.compareTo(raw) < 0 ? RewardLineReason.PARTIAL_CAP : RewardLineReason.MATCHED;
                results.add(new TxnRuleResolution(rule, award, reason, ruleBasis));
            } else {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, RewardLineReason.CAP_EXHAUSTED, ruleBasis));
            }
        }

        return results;
    }

    /**
     * Effective card for rule matching and milestone aggregation (Axis 1 & Axis 3).
     * <p>
     * On a single-card account, unattributed transactions cleanly attribute to the sole card.
     * On a multi-card account, unattributed transactions stay null so they never match card-scoped rules.
     * <p>
     * "Ambiguity never creates money. It may consume headroom."
     */
    UUID matchCardId(UUID txnCardId, Evaluation eval) {
        return txnCardId != null ? txnCardId : eval.soleCardId;
    }

    /**
     * Effective card for cap and tier headroom consumption (Axis 2).
     * <p>
     * Under PER_CARD counter scope, unattributed transactions fold into the primary card counter
     * (or sole card counter on single-card accounts) so they do not invent headroom.
     * <p>
     * "Ambiguity never creates money. It may consume headroom."
     */
    UUID counterCardId(UUID txnCardId, Evaluation eval) {
        if (txnCardId != null) {
            return txnCardId;
        }
        return eval.soleCardId != null ? eval.soleCardId : eval.primaryCardId;
    }

    /**
     * Per-rule earning basis: the transaction's netted basis, less the labeled surcharge
     * when this rule excludes fees. Floored at zero — a fee larger than what survives a
     * partial refund nets to nothing rather than going negative, and tier progress is
     * recorded on this same reduced number so a non-earning fee never advances a threshold.
     */
    private BigDecimal basisFor(RewardRule rule, TxnFacts facts) {
        if (rule.getFeeTreatment() != FeeTreatment.EXCLUDE_FEE || facts.convenienceFee() == null) {
            return facts.basis();
        }
        return facts.basis().subtract(facts.convenienceFee()).max(BigDecimal.ZERO);
    }

    /**
     * Tier semantics: the running total is the basis of transactions this rule was
     * RESOLVED for (exclusive winner or matching additive) within its tier window —
     * rule predicates define what counts toward the threshold.
     */
    private void recordTierProgress(RewardRule rule, BigDecimal basis, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        if (!rule.isTiered() || rule.getTierWindow() == null) {
            return;
        }
        eval.tierProgress.merge(tierKey(rule, effectiveDate, eval, txnCardId), basis, BigDecimal::add);
    }

    /**
     * Same (owner, cardId, windowStart) shape as capKey — reads and writes must share it.
     * Note: tierKey keeps using the rule's own counterScope — tiers have no bucket, and conflating them would be a bug.
     */
    private CounterKey tierKey(RewardRule rule, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        Window window = windowContaining(rule.getTierWindow(), effectiveDate, eval, rule.getCard(), true);
        if (rule.getCounterScope() == CounterScope.PER_CARD) {
            UUID effectiveCardId = counterCardId(txnCardId, eval);
            if (txnCardId == null && eval.cardCount >= 2) {
                eval.currentTxnAttributionIncomplete = true;
            }
            return new CounterKey(rule.getId().toString(), effectiveCardId, window.start());
        }
        return new CounterKey(rule.getId().toString(), null, window.start());
    }

    // ---------- predicates ----------

    private boolean matches(RewardRule rule, TxnFacts facts, Map<UUID, Pattern> regexCache, Evaluation eval) {
        // Axis 1: Rule-level card scoping
        if (rule.getCard() != null) {
            UUID effectiveCardId = matchCardId(facts.cardId(), eval);
            if (effectiveCardId == null) {
                if (eval.cardCount >= 2) {
                    eval.currentTxnAttributionIncomplete = true;
                }
                return false;
            }
            if (!rule.getCard().getId().equals(effectiveCardId)) {
                return false;
            }
        }

        // (categories OR mccs) — union, because MCC is often missing.
        boolean hasCategoryPredicate = !rule.getCategories().isEmpty();
        boolean hasMccPredicate = !rule.getMccs().isEmpty();
        if (hasCategoryPredicate || hasMccPredicate) {
            boolean categoryHit = hasCategoryPredicate && facts.categoryIds().stream()
                    .anyMatch(catId -> rule.getCategories().stream()
                            .anyMatch(c -> c.getId().equals(catId)));
            boolean mccHit = hasMccPredicate && facts.mcc() != null && rule.getMccs().contains(facts.mcc());
            if (!categoryHit && !mccHit) {
                return false;
            }
        }

        if (rule.getMerchantPattern() != null && !matchesMerchant(rule, facts, regexCache)) {
            return false;
        }

        if (!rule.getChannels().isEmpty()
                && (facts.channel() == null || !rule.getChannels().contains(facts.channel()))) {
            return false;
        }

        // Day-of-week is a purchase-day concept — always the transaction date, not settlement.
        if (!rule.getDaysOfWeek().isEmpty() && !rule.getDaysOfWeek().contains(facts.date().getDayOfWeek())) {
            return false;
        }

        // Amount band applies to the gross charged amount (bank behavior), not the netted basis.
        if (rule.getMinAmount() != null && facts.amount().compareTo(rule.getMinAmount()) < 0) {
            return false;
        }
        if (rule.getMaxAmount() != null && facts.amount().compareTo(rule.getMaxAmount()) > 0) {
            return false;
        }

        boolean isEmi = facts.isEmi();
        if (rule.getEmiTreatment() == EmiTreatment.EXCLUDE_EMI && isEmi) {
            return false;
        }
        if (rule.getEmiTreatment() == EmiTreatment.ONLY_EMI && !isEmi) {
            return false;
        }

        boolean isIntl = facts.isIntl();
        if (rule.getIntlTreatment() == IntlTreatment.EXCLUDE_INTL && isIntl) {
            return false;
        }
        return rule.getIntlTreatment() != IntlTreatment.ONLY_INTL || isIntl;
    }

    private boolean matchesMerchant(RewardRule rule, TxnFacts facts, Map<UUID, Pattern> regexCache) {
        return matchesMerchantText(rule, facts.description(), regexCache)
                || matchesMerchantText(rule, facts.sourcedDescription(), regexCache);
    }

    private boolean matchesMerchantText(RewardRule rule, String text, Map<UUID, Pattern> regexCache) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = rule.getMerchantPattern().toLowerCase(Locale.ROOT);
        return switch (rule.getMerchantMatch()) {
            case CONTAINS -> haystack.contains(needle);
            case STARTS_WITH -> haystack.startsWith(needle);
            case EXACT -> haystack.trim().equals(needle.trim());
            case REGEX -> {
                try {
                    Pattern pattern = regexCache.computeIfAbsent(rule.getId(),
                            id -> Pattern.compile(rule.getMerchantPattern(), Pattern.CASE_INSENSITIVE));
                    yield pattern.matcher(text).find();
                } catch (Exception e) {
                    log.warn("Reward rule {} has an invalid regex; treating as non-match", rule.getId());
                    yield false;
                }
            }
        };
    }

    // ---------- accrual + caps ----------

    private BigDecimal accrue(RewardRule rule, BigDecimal basis, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        if (rule.isTiered()) {
            return accrueTiered(rule, basis, effectiveDate, eval, txnCardId);
        }
        if (rule.getAccrualType() == AccrualType.PERCENT) {
            BigDecimal raw = basis.multiply(rule.getPercentRate())
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            return roundCashback(rule, raw);
        }
        // SLAB: floor(basis / slabSize) whole slabs, then points at the configured precision.
        BigDecimal slabs = basis.divide(rule.getSlabSize(), 0, RoundingMode.FLOOR);
        BigDecimal points = slabs.multiply(rule.getPointsPerSlab());
        return floorPoints(rule, points);
    }

    /**
     * Marginal (tranche-by-tranche) tiered accrual over the rule's running matched
     * basis in the tier window. A transaction crossing a breakpoint is split: each
     * tranche earns at its own tier's rate (SLAB tranches floor independently —
     * the documented crossing convention).
     */
    private BigDecimal accrueTiered(RewardRule rule, BigDecimal basis, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        List<RewardTier> tiers = eval.tierSchedules.getOrDefault(rule.getId(), List.of());
        if (tiers.isEmpty() || rule.getTierWindow() == null) {
            return BigDecimal.ZERO; // unreachable for evaluated rules (broken configs are skipped), belt only
        }
        BigDecimal position = eval.tierProgress.getOrDefault(tierKey(rule, effectiveDate, eval, txnCardId), BigDecimal.ZERO);
        BigDecimal remaining = basis;
        BigDecimal total = BigDecimal.ZERO;
        for (RewardTier tier : tiers) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal tranche;
            if (tier.upTo() == null) {
                tranche = remaining;
            } else {
                BigDecimal headroom = tier.upTo().subtract(position);
                if (headroom.signum() <= 0) {
                    continue;
                }
                tranche = remaining.min(headroom);
            }
            if (rule.getAccrualType() == AccrualType.PERCENT) {
                total = total.add(tranche.multiply(tier.rate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            } else {
                BigDecimal slabs = tranche.divide(rule.getSlabSize(), 0, RoundingMode.FLOOR);
                total = total.add(slabs.multiply(tier.rate()));
            }
            position = position.add(tranche);
            remaining = remaining.subtract(tranche);
        }
        return rule.getAccrualType() == AccrualType.PERCENT ? roundCashback(rule, total) : floorPoints(rule, total);
    }

    /** Unit-agnostic: FLOOR/NEAREST round to a whole rupee for CASH rules, a whole point for POINTS rules. */
    private BigDecimal roundCashback(RewardRule rule, BigDecimal raw) {
        CashbackRounding rounding = rule.getRounding() != null ? rule.getRounding() : CashbackRounding.NONE;
        return switch (rounding) {
            case NONE -> raw.setScale(2, RoundingMode.HALF_UP);
            case FLOOR_RUPEE -> raw.setScale(0, RoundingMode.FLOOR);
            case NEAREST_RUPEE -> raw.setScale(0, RoundingMode.HALF_UP);
        };
    }

    private BigDecimal floorPoints(RewardRule rule, BigDecimal points) {
        int precision = rule.getPointPrecision() != null ? rule.getPointPrecision() : 0;
        return points.setScale(precision, RoundingMode.FLOOR);
    }

    private boolean isZeroRate(RewardRule rule) {
        if (rule.isTiered()) {
            return false; // tiered zero-earn is a below-slab/rounding outcome, not an exclusion
        }
        if (rule.getAccrualType() == AccrualType.PERCENT) {
            return rule.getPercentRate().signum() == 0;
        }
        return rule.getPointsPerSlab().signum() == 0;
    }

    /** Why a matched rule accrued zero: explicit exclusion, tier level, sub-slab basis, or rounding. */
    private RewardLineReason zeroAccrualReason(RewardRule rule, BigDecimal ruleBasis) {
        // Distinguish "the surcharge was the whole charge" from a rounding/slab zero.
        if (rule.getFeeTreatment() == FeeTreatment.EXCLUDE_FEE && ruleBasis.signum() <= 0) {
            return RewardLineReason.FEE_ONLY;
        }
        if (rule.isTiered()) {
            return RewardLineReason.TIER_ZERO;
        }
        if (isZeroRate(rule)) {
            return RewardLineReason.EXCLUDED_BY_RULE;
        }
        return rule.getAccrualType() == AccrualType.SLAB
                ? RewardLineReason.BELOW_SLAB
                : RewardLineReason.ROUNDED_TO_ZERO;
    }

    /** Effective period-cap limit: the shared bucket's cap when set, else the rule's own. */
    BigDecimal effectiveCap(RewardRule rule) {
        return rule.getCapBucket() != null ? rule.getCapBucket().getCap() : rule.getPeriodCap();
    }

    CapWindow effectiveCapWindow(RewardRule rule) {
        return rule.getCapBucket() != null ? rule.getCapBucket().getWindowType() : rule.getCapWindow();
    }

    /** Bucket-backed rules take the BUCKET's scope, so members can never split a shared ceiling. */
    CounterScope effectiveCounterScope(RewardRule rule) {
        return rule.getCapBucket() != null ? rule.getCapBucket().getCounterScope() : rule.getCounterScope();
    }

    private BigDecimal clamp(RewardRule rule, BigDecimal raw, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        BigDecimal award = raw;
        if (rule.getPerTxnCap() != null && award.compareTo(rule.getPerTxnCap()) > 0) {
            award = rule.getPerTxnCap();
        }
        if (rule.hasPeriodCap()) {
            BigDecimal used = eval.capUsed.getOrDefault(capKey(rule, effectiveDate, eval, txnCardId), BigDecimal.ZERO);
            BigDecimal remaining = effectiveCap(rule).subtract(used);
            if (remaining.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            if (award.compareTo(remaining) > 0) {
                award = remaining;
            }
        }
        return award;
    }

    private void consumeCap(RewardRule rule, BigDecimal award, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        if (rule.hasPeriodCap()) {
            eval.capUsed.merge(capKey(rule, effectiveDate, eval, txnCardId), award, BigDecimal::add);
        }
    }

    /** Bucket-backed rules share the bucket's key so they drain one ceiling together. */
    private CounterKey capKey(RewardRule rule, LocalDate effectiveDate, Evaluation eval, UUID txnCardId) {
        Window window = windowContaining(effectiveCapWindow(rule), effectiveDate, eval, rule.getCard(), true);
        if (effectiveCounterScope(rule) == CounterScope.PER_CARD) {
            UUID effectiveCardId = counterCardId(txnCardId, eval);
            if (txnCardId == null && eval.cardCount >= 2) {
                eval.currentTxnAttributionIncomplete = true;
            }
            return new CounterKey(capOwner(rule), effectiveCardId, window.start());
        }
        return new CounterKey(capOwner(rule), null, window.start());
    }

    /** Cap-counter owner: the shared bucket if any, else the rule itself. */
    static String capOwner(RewardRule rule) {
        return rule.getCapBucket() != null ? "bucket|" + rule.getCapBucket().getId() : rule.getId().toString();
    }

    // ---------- windows ----------

    /** markFallback: only real per-transaction cap lookups may raise the report's fallback flag. */
    Window windowContaining(CapWindow capWindow, LocalDate date, Evaluation eval, boolean markFallback) {
        return windowContaining(capWindow, date, eval, null, markFallback);
    }

    /** Overload accepting an optional rule-scoped card to anchor ANNIVERSARY_YEAR correctly. */
    Window windowContaining(CapWindow capWindow, LocalDate date, Evaluation eval, AccountCard ruleCard, boolean markFallback) {
        return switch (capWindow) {
            case DAY -> new Window(date, date, false);
            case CALENDAR_MONTH -> new Window(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), false);
            case QUARTER -> {
                int month = date.getMonthValue();
                int qStartMonth = ((month - 1) / 3) * 3 + 1;
                LocalDate start = LocalDate.of(date.getYear(), qStartMonth, 1);
                LocalDate end = start.plusMonths(3).minusDays(1);
                yield new Window(start, end, false);
            }
            case CALENDAR_YEAR -> new Window(LocalDate.of(date.getYear(), 1, 1), LocalDate.of(date.getYear(), 12, 31), false);
            case ANNIVERSARY_YEAR -> {
                LocalDate anniversary = (ruleCard != null && ruleCard.getIssuedOn() != null)
                        ? ruleCard.getIssuedOn()
                        : eval.anniversaryDate;
                if (anniversary == null) {
                    if (markFallback) {
                        eval.anniversaryFallback = true;
                    }
                    yield new Window(LocalDate.of(date.getYear(), 1, 1), LocalDate.of(date.getYear(), 12, 31), true);
                }
                LocalDate start = anniversaryOnYear(anniversary, date.getYear());
                if (start.isAfter(date)) {
                    start = anniversaryOnYear(anniversary, date.getYear() - 1);
                }
                LocalDate end = anniversaryOnYear(anniversary, start.getYear() + 1).minusDays(1);
                yield new Window(start, end, false);
            }
            case STATEMENT_CYCLE -> {
                for (Statement statement : eval.statements) {
                    if (!date.isBefore(statement.getPeriodStart()) && !date.isAfter(statement.getPeriodEnd())) {
                        yield new Window(statement.getPeriodStart(), statement.getPeriodEnd(), false);
                    }
                }
                // No statement covers this date — calendar-month fallback, flagged on the report
                // only when an actual reward line lands in this window.
                if (markFallback) {
                    eval.cycleFallback = true;
                }
                yield new Window(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), true);
            }
        };
    }

    /** The anniversary's month/day placed in the given year, clamping Feb 29 to the month's length. */
    private static LocalDate anniversaryOnYear(LocalDate anniversary, int year) {
        int day = Math.min(anniversary.getDayOfMonth(),
                java.time.YearMonth.of(year, anniversary.getMonth()).lengthOfMonth());
        return LocalDate.of(year, anniversary.getMonth(), day);
    }

    // ---------- line + report building ----------

    private static LocalDate effectiveDate(Transaction txn) {
        return txn.getSettlementDate() != null ? txn.getSettlementDate() : txn.getDate();
    }

    private RewardLineResponse zeroLine(Transaction txn, LocalDate effectiveDate, BigDecimal basis, RewardLineReason reason, Evaluation eval) {
        UUID effectiveCardId = matchCardId(txn.getCard() != null ? txn.getCard().getId() : null, eval);
        String label = effectiveCardId != null ? eval.cardLabels.getOrDefault(effectiveCardId, "Card") : (txn.getCard() != null ? txn.getCard().getLabel() : null);
        return new RewardLineResponse(txn.getId(), txn.getDate(), effectiveDate,
                txn.getDescription(), txn.getSourcedDescription(), txn.getMcc(), txn.getChannel(),
                txn.getAmount(), basis,
                null, null, null, null,
                BigDecimal.ZERO, "RUPEES", reason, effectiveCardId, label);
    }

    private RewardLineResponse line(Transaction txn, LocalDate effectiveDate, BigDecimal basis,
                                    RewardRule rule, BigDecimal earned, RewardLineReason reason, Evaluation eval) {
        String unit = unitOf(rule);
        UUID effectiveCardId = matchCardId(txn.getCard() != null ? txn.getCard().getId() : null, eval);
        String label = effectiveCardId != null ? eval.cardLabels.getOrDefault(effectiveCardId, "Card") : (txn.getCard() != null ? txn.getCard().getLabel() : null);
        return new RewardLineResponse(txn.getId(), txn.getDate(), effectiveDate,
                txn.getDescription(), txn.getSourcedDescription(), txn.getMcc(), txn.getChannel(),
                txn.getAmount(), basis,
                rule.getId(), rule.getName(), rule.getStacking(), rule.getAccrualType(),
                earned, unit, reason, effectiveCardId, label);
    }

    /** The line/breakdown unit is the rule's reward currency, independent of accrual math. */
    static String unitOf(RewardRule rule) {
        return rule.getRewardType() == RewardType.POINTS ? UNIT_POINTS : UNIT_RUPEES;
    }

    private RewardReportResponse buildReport(Evaluation eval, LocalDate from, LocalDate to) {
        List<RewardLineResponse> displayLines = eval.lines.stream()
                .filter(l -> !l.effectiveDate().isBefore(from) && !l.effectiveDate().isAfter(to))
                .toList();
        List<EligibleTxn> displayEligible = eval.eligible.stream()
                .filter(e -> !e.effectiveDate().isBefore(from) && !e.effectiveDate().isAfter(to))
                .toList();

        BigDecimal basisSpend = displayEligible.stream().map(EligibleTxn::basis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discounts = displayEligible.stream().map(EligibleTxn::instantDiscount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = displayEligible.stream().map(EligibleTxn::convenienceFee)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cashbackInr = BigDecimal.ZERO;
        BigDecimal points = BigDecimal.ZERO;
        Set<UUID> matchedTxnIds = new HashSet<>();
        for (RewardLineResponse lineItem : displayLines) {
            if (lineItem.earned() == null || lineItem.earned().signum() <= 0) {
                continue;
            }
            matchedTxnIds.add(lineItem.transactionId());
            if (UNIT_POINTS.equals(lineItem.earnedUnit())) {
                points = points.add(lineItem.earned());
            } else {
                cashbackInr = cashbackInr.add(lineItem.earned());
            }
        }
        List<RewardReportResponse.MilestoneStatus> milestoneStatuses = evaluateMilestones(eval, from, to);
        BigDecimal milestonesInr = BigDecimal.ZERO;
        BigDecimal milestonesPts = BigDecimal.ZERO;
        for (RewardReportResponse.MilestoneStatus status : milestoneStatuses) {
            if (!status.countedInSummary()) {
                continue;
            }
            if (status.rewardType() == RewardType.POINTS) {
                milestonesPts = milestonesPts.add(status.payoutValue());
            } else {
                milestonesInr = milestonesInr.add(status.payoutValue());
            }
        }

        // Points are not valued in rupees (no conversion tracking yet) — the ₹ totals
        // and percentage rates cover the cash side only.
        BigDecimal grossValueInr = cashbackInr.add(milestonesInr);
        BigDecimal effectiveValueInr = grossValueInr.add(discounts).subtract(fees);

        RewardReportResponse.Summary summary = new RewardReportResponse.Summary(
                basisSpend,
                displayEligible.size(),
                matchedTxnIds.size(),
                cashbackInr,
                points,
                milestonesInr,
                milestonesPts,
                grossValueInr,
                discounts,
                fees,
                effectiveValueInr,
                pct(grossValueInr, basisSpend),
                pct(effectiveValueInr, basisSpend));

        List<RewardReportResponse.RuleBreakdown> breakdowns = new ArrayList<>();
        for (RewardRule rule : eval.rules) {
            List<RewardLineResponse> ruleLines = displayLines.stream()
                    .filter(l -> rule.getId().equals(l.ruleId()))
                    .toList();
            BigDecimal earned = ruleLines.stream().map(RewardLineResponse::earned)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal basisMatched = ruleLines.stream()
                    .filter(l -> l.earned() != null && l.earned().signum() > 0)
                    .map(RewardLineResponse::basis)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int matchedCount = (int) ruleLines.stream()
                    .filter(l -> l.earned() != null && l.earned().signum() > 0).count();

            RewardReportResponse.CapStatus capStatus = null;
            if (rule.hasPeriodCap()) {
                Window window = windowContaining(effectiveCapWindow(rule), to, eval, rule.getCard(), false);
                CounterScope scope = effectiveCounterScope(rule);
                BigDecimal used = null;
                List<RewardReportResponse.PerCardCapUsage> perCard = new ArrayList<>();
                if (scope == CounterScope.PER_CARD) {
                    List<Map.Entry<CounterKey, BigDecimal>> entries = eval.capUsed.entrySet().stream()
                            .filter(e -> Objects.equals(e.getKey().owner(), capOwner(rule))
                                    && Objects.equals(e.getKey().windowStart(), window.start()))
                            .toList();
                    for (Map.Entry<CounterKey, BigDecimal> entry : entries) {
                        UUID cid = entry.getKey().cardId();
                        String label = cid != null ? eval.cardLabels.getOrDefault(cid, "Card") : "Unattributed";
                        perCard.add(new RewardReportResponse.PerCardCapUsage(cid, label, entry.getValue()));
                    }
                    used = null; // null forces client to render perCard breakdown
                } else {
                    used = eval.capUsed.getOrDefault(new CounterKey(capOwner(rule), null, window.start()), BigDecimal.ZERO);
                }
                capStatus = new RewardReportResponse.CapStatus(
                        effectiveCapWindow(rule), effectiveCap(rule), used,
                        window.start(), window.end(), window.cycleFallback(),
                        rule.getCapBucket() != null ? rule.getCapBucket().getName() : null,
                        scope, perCard);
            }

            boolean activeInRange = rule.isActiveOn(to) || rule.isActiveOn(from)
                    || (rule.getActiveFrom() != null
                        && rule.getActiveFrom().isAfter(from) && rule.getActiveFrom().isBefore(to));
            breakdowns.add(new RewardReportResponse.RuleBreakdown(
                    rule.getId(), rule.getName(), rule.getStacking(), rule.getAccrualType(),
                    activeInRange, matchedCount, basisMatched, earned, unitOf(rule), capStatus));
        }

        List<RewardReportResponse.CardBreakdown> byCard = new ArrayList<>();
        if (eval.cardCount >= 2) {
            UUID NULL_KEY = new UUID(0L, 0L);
            Map<UUID, List<EligibleTxn>> txnsByCard = displayEligible.stream()
                    .collect(Collectors.groupingBy(e -> e.cardId() != null ? e.cardId() : NULL_KEY));

            Map<UUID, List<RewardLineResponse>> linesByCard = displayLines.stream()
                    .filter(l -> l.earned() != null && l.earned().signum() > 0)
                    .collect(Collectors.groupingBy(l -> l.cardId() != null ? l.cardId() : NULL_KEY));

            for (AccountCard card : eval.allCards) {
                List<EligibleTxn> cardTxns = txnsByCard.getOrDefault(card.getId(), List.of());
                List<RewardLineResponse> cardLines = linesByCard.getOrDefault(card.getId(), List.of());

                // Open cards always get a row (an idle card is worth seeing). A CLOSED card
                // appears only when it actually has activity in the range — otherwise every
                // historical reissue would clutter the breakdown forever.
                if (card.getClosedOn() != null && cardTxns.isEmpty() && cardLines.isEmpty()) {
                    continue;
                }

                BigDecimal basis = cardTxns.stream().map(EligibleTxn::basis).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal cb = BigDecimal.ZERO;
                BigDecimal pts = BigDecimal.ZERO;
                for (RewardLineResponse l : cardLines) {
                    if ("POINTS".equals(l.earnedUnit())) {
                        pts = pts.add(l.earned());
                    } else {
                        cb = cb.add(l.earned());
                    }
                }
                String label = eval.cardLabels.getOrDefault(card.getId(), card.getLabel() != null ? card.getLabel() : "Card");
                byCard.add(new RewardReportResponse.CardBreakdown(
                        card.getId(), label, false, basis, cb, pts, cardTxns.size()));
            }

            List<EligibleTxn> unattributedTxns = txnsByCard.getOrDefault(NULL_KEY, List.of());
            List<RewardLineResponse> unattributedLines = linesByCard.getOrDefault(NULL_KEY, List.of());
            if (!unattributedTxns.isEmpty() || !unattributedLines.isEmpty()) {
                BigDecimal basis = unattributedTxns.stream().map(EligibleTxn::basis).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal cb = BigDecimal.ZERO;
                BigDecimal pts = BigDecimal.ZERO;
                for (RewardLineResponse l : unattributedLines) {
                    if ("POINTS".equals(l.earnedUnit())) {
                        pts = pts.add(l.earned());
                    } else {
                        cb = cb.add(l.earned());
                    }
                }
                byCard.add(new RewardReportResponse.CardBreakdown(
                        null, "Unattributed", true, basis, cb, pts, unattributedTxns.size()));
            }
        }

        return new RewardReportResponse(summary, breakdowns, milestoneStatuses,
                eval.cycleFallback, eval.anniversaryFallback, byCard,
                eval.perCardAttributionIncompleteTxnIds.size());
    }

    /**
     * One status per milestone per window instance intersecting [from, to]. Progress is
     * computed over the FULL window (the fetch range was expanded to cover it), so a
     * mid-month filter still shows the true month-to-date position.
     */
    List<RewardReportResponse.MilestoneStatus> evaluateMilestones(Evaluation eval, LocalDate from, LocalDate to) {
        List<RewardReportResponse.MilestoneStatus> statuses = new ArrayList<>();
        for (MilestoneWithEligibility entry : eval.milestones) {
            RewardMilestone milestone = entry.milestone();
            MilestoneEligibility eligibility = entry.eligibility();
            if (milestone.getWindowType() == MilestoneWindow.ONE_TIME) {
                // The single window is the active range (both edges validated non-null;
                // activeTo is exclusive). Progress always covers the whole offer period.
                statuses.add(milestoneStatus(milestone, eligibility,
                        milestone.getActiveFrom(), milestone.getActiveTo().minusDays(1), eval, from, to));
                continue;
            }
            LocalDate cursor = from;
            boolean firstWindow = true;
            int guard = 0;
            while (!cursor.isAfter(to)) {
                if (guard++ >= 500) {
                    // Never truncate silently — a partial milestone sum would misreport totals.
                    throw new ValidationException(
                            "Date range too large for milestone evaluation — please narrow the range.");
                }
                // Real usage: a statement gap here must raise the report's fallback banner.
                Window window = windowContaining(milestone.getWindowType().asCapWindow(), cursor, eval, milestone.getCard(), true);
                // Statement gaps can interleave fallback months with statement windows whose
                // starts precede the cursor; clamping LATER windows to the cursor keeps
                // counted ranges disjoint so nothing is counted twice. The FIRST window is
                // never clamped: a range starting mid-window must still see full-window
                // progress (the fetch range was expanded for exactly this).
                LocalDate countStart = !firstWindow && window.start().isBefore(cursor) ? cursor : window.start();
                firstWindow = false;
                // Clamp to the milestone's own active range (activeTo is exclusive).
                if (milestone.getActiveFrom() != null && milestone.getActiveFrom().isAfter(countStart)) {
                    countStart = milestone.getActiveFrom();
                }
                LocalDate countEnd = window.end();
                if (milestone.getActiveTo() != null && !milestone.getActiveTo().isAfter(countEnd)) {
                    countEnd = milestone.getActiveTo().minusDays(1);
                }
                if (!countStart.isAfter(countEnd)) {
                    statuses.add(milestoneStatus(milestone, eligibility, countStart, countEnd, eval, from, to));
                }
                cursor = window.end().plusDays(1);
            }
        }
        return statuses;
    }

    /**
     * Progress over [countStart, countEnd] plus the payout attribution. The payout
     * lands on exactly one date — the counted range's end, or the threshold-crossing
     * date for ON_ACHIEVEMENT milestones (eligible transactions are walked in
     * chronological order, so the crossing date is well-defined) — and is counted
     * into the summary only when that date is inside the display range.
     */
    private RewardReportResponse.MilestoneStatus milestoneStatus(
            RewardMilestone milestone, MilestoneEligibility eligibility,
            LocalDate countStart, LocalDate countEnd, Evaluation eval, LocalDate from, LocalDate to) {
        BigDecimal progress = BigDecimal.ZERO;
        LocalDate achievedOn = null;
        for (EligibleTxn txn : eval.eligible) {
            if (txn.effectiveDate().isBefore(countStart) || txn.effectiveDate().isAfter(countEnd)) {
                continue;
            }
            if (milestone.getCard() != null) {
                UUID effectiveCardId = matchCardId(txn.cardId(), eval);
                if (effectiveCardId == null) {
                    if (eval.cardCount >= 2) {
                        // The id is in hand here, so record it directly; a milestone is
                        // re-evaluated per window instance and must not count twice.
                        eval.perCardAttributionIncompleteTxnIds.add(txn.id());
                    }
                    continue;
                }
                if (!milestone.getCard().getId().equals(effectiveCardId)) {
                    continue;
                }
            }
            if (!milestoneEligible(eligibility, txn)) {
                continue;
            }
            if (milestone.getBasis() == MilestoneBasis.SPEND) {
                progress = progress.add(txn.basis());
            } else if (milestone.getMinTxnAmount() == null
                    || txn.amount().compareTo(milestone.getMinTxnAmount()) >= 0) {
                progress = progress.add(BigDecimal.ONE);
            } else {
                continue;
            }
            if (achievedOn == null && progress.compareTo(milestone.getThreshold()) >= 0) {
                achievedOn = txn.effectiveDate();
            }
        }
        boolean achieved = achievedOn != null;
        LocalDate payoutDate = !achieved ? null
                : milestone.getPayoutTiming() == MilestonePayoutTiming.ON_ACHIEVEMENT ? achievedOn : countEnd;
        boolean countedInSummary = achieved
                && milestone.getPayoutType() == MilestonePayoutType.CASH_VALUE
                && !payoutDate.isBefore(from) && !payoutDate.isAfter(to);
        return new RewardReportResponse.MilestoneStatus(
                milestone.getId(), milestone.getName(), milestone.getWindowType(),
                countStart, countEnd,
                milestone.getBasis(), milestone.getThreshold(), milestone.getMinTxnAmount(),
                progress, achieved,
                milestone.getPayoutType(), milestone.getRewardType(), milestone.getPayoutValue(),
                payoutDate, countedInSummary);
    }

    boolean milestoneEligible(MilestoneEligibility eligibility, TxnFacts facts) {
        return milestoneEligible(eligibility, facts.mcc(), facts.categoryIds());
    }

    private boolean milestoneEligible(MilestoneEligibility eligibility, EligibleTxn txn) {
        return milestoneEligible(eligibility, txn.mcc(), txn.categoryIds());
    }

    /** Single source of truth for eligibility — real transactions and simulated spends share it. */
    private boolean milestoneEligible(MilestoneEligibility eligibility, String mcc, Set<UUID> categoryIds) {
        if (!eligibility.excludeMccs().isEmpty() && mcc != null
                && eligibility.excludeMccs().contains(mcc)) {
            return false;
        }
        if (!eligibility.excludeCategoryIds().isEmpty()
                && categoryIds.stream().anyMatch(eligibility.excludeCategoryIds()::contains)) {
            return false;
        }
        boolean hasInclude = !eligibility.includeCategoryIds().isEmpty() || !eligibility.includeMccs().isEmpty();
        if (hasInclude) {
            boolean categoryHit = !eligibility.includeCategoryIds().isEmpty()
                    && categoryIds.stream().anyMatch(eligibility.includeCategoryIds()::contains);
            boolean mccHit = !eligibility.includeMccs().isEmpty() && mcc != null
                    && eligibility.includeMccs().contains(mcc);
            return categoryHit || mccHit;
        }
        return true;
    }

    private static BigDecimal pct(BigDecimal value, BigDecimal basis) {
        if (basis == null || basis.signum() == 0) {
            return null;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(basis, 2, RoundingMode.HALF_UP);
    }
}
