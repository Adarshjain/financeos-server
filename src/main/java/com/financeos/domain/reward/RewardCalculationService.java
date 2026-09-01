package com.financeos.domain.reward;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRepository;
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
    private final CardholderRepository cardholderRepository;

    public RewardCalculationService(RewardRuleRepository rewardRuleRepository,
                                    RewardRuleService rewardRuleService,
                                    RewardMilestoneRepository rewardMilestoneRepository,
                                    RewardMilestoneService rewardMilestoneService,
                                    TransactionRepository transactionRepository,
                                    TransactionLinkRepository transactionLinkRepository,
                                    StatementRepository statementRepository,
                                    AccountRepository accountRepository,
                                    CardholderRepository cardholderRepository) {
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardRuleService = rewardRuleService;
        this.rewardMilestoneRepository = rewardMilestoneRepository;
        this.rewardMilestoneService = rewardMilestoneService;
        this.transactionRepository = transactionRepository;
        this.transactionLinkRepository = transactionLinkRepository;
        this.statementRepository = statementRepository;
        this.accountRepository = accountRepository;
        this.cardholderRepository = cardholderRepository;
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

    record CounterKey(String owner, UUID cardholderId, LocalDate windowStart) {
    }

    record MilestoneWithEligibility(RewardMilestone milestone, MilestoneEligibility eligibility) {
    }

    static final class Evaluation {
        final List<RewardRule> rules;
        final List<MilestoneWithEligibility> milestones;
        final List<RewardLineResponse> lines = new ArrayList<>();
        /** capKey (owner, cardholderId, windowStart) -> used amount in the rule's output unit. */
        final Map<CounterKey, BigDecimal> capUsed = new HashMap<>();
        /** tiered rules: (ruleId, cardholderId, tierWindowStart) -> running matched basis in the window. */
        final Map<CounterKey, BigDecimal> tierProgress = new HashMap<>();
        /** parsed tier schedules per tiered rule. */
        final Map<UUID, List<RewardTier>> tierSchedules = new HashMap<>();
        /** eligible debit txn id -> netted basis, display-range membership decided later. */
        final List<EligibleTxn> eligible = new ArrayList<>();
        final Map<UUID, String> cardholderLabels = new HashMap<>();
        /**
         * Every cardholder on the account, open AND closed.
         */
        List<Cardholder> allCardholders = List.of();
        int cardholderCount = 0;
        /**
         * Distinct transactions whose missing attribution affected a cardholder-scoped decision.
         */
        final Set<UUID> perCardAttributionIncompleteTxnIds = new HashSet<>();
        /** Scratch flag for the transaction currently being evaluated; collected by evaluateTransaction. */
        boolean currentTxnAttributionIncomplete = false;
        boolean cycleFallback = false;
        boolean anniversaryFallback = false;
        List<Statement> statements = List.of();
        LocalDate anniversaryDate;
        UUID primaryCardholderId;
        UUID soleCardholderId;

        Evaluation(List<RewardRule> rules, List<MilestoneWithEligibility> milestones) {
            this.rules = rules;
            this.milestones = milestones;
        }
    }

    private record EligibleTxn(UUID id, LocalDate effectiveDate, BigDecimal basis,
                               BigDecimal instantDiscount, BigDecimal convenienceFee,
                               BigDecimal amount, String mcc, Set<UUID> categoryIds,
                               UUID cardholderId) {
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
            UUID cardholderId) {

        static TxnFacts from(Transaction txn, BigDecimal basis, LocalDate effectiveDate) {
            Set<UUID> catIds = txn.getCategories() != null ? txn.getCategories().stream()
                    .map(tc -> tc.getCategory().getId())
                    .collect(Collectors.toSet()) : Set.of();
            UUID cardholderId = (txn.getCard() != null && txn.getCard().getCardholder() != null)
                    ? txn.getCard().getCardholder().getId()
                    : null;
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
                    cardholderId);
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
        List<RewardRule> usableRules = new ArrayList<>();
        Map<UUID, List<RewardTier>> schedules = new HashMap<>();
        for (RewardRule rule : rules) {
            if (rule.isTiered()) {
                List<RewardTier> tiers = rewardRuleService.parseTiers(rule);
                if (tiers.isEmpty() || rule.getTierWindow() == null) {
                    log.warn("Reward rule {} has a broken tier configuration; skipping it in evaluation",
                            rule.getId(),
                            StructuredArguments.keyValue("event", Events.REWARD_RULE_SKIPPED),
                            StructuredArguments.keyValue("ruleId", rule.getId()));
                    continue;
                }
                schedules.put(rule.getId(), tiers);
            }
            usableRules.add(rule);
        }
        Evaluation eval = new Evaluation(usableRules, milestones);
        eval.tierSchedules.putAll(schedules);
        eval.anniversaryDate = account.getRewardAnniversaryDate();

        List<Cardholder> allCardholders = cardholderRepository != null
                ? cardholderRepository.findByAccountId(accountId)
                : List.of();
        eval.allCardholders = allCardholders;
        eval.cardholderCount = allCardholders.size();

        Cardholder primaryCardholder = allCardholders.stream()
                .filter(Cardholder::isPrimary)
                .findFirst()
                .orElse(null);
        eval.primaryCardholderId = primaryCardholder != null ? primaryCardholder.getId() : null;
        eval.soleCardholderId = allCardholders.size() == 1 ? allCardholders.get(0).getId() : null;

        for (Cardholder ch : allCardholders) {
            eval.cardholderLabels.put(ch.getId(), ch.getDisplayName());
        }
        rules = usableRules;
        eval.statements = statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId).stream()
                .filter(s -> s.getVerdict() != StatementVerdict.REJECTED)
                .filter(s -> s.getPeriodStart() != null && s.getPeriodEnd() != null)
                .toList();

        LocalDate expandedFrom = from;
        LocalDate expandedTo = to;
        List<CapWindow> windowTypes = new ArrayList<>();
        for (RewardRule rule : rules) {
            if (rule.hasPeriodCap()) {
                windowTypes.add(effectiveCapWindow(rule));
            }
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
        Set<UUID> categoryIds = txn.getCategories() != null ? txn.getCategories().stream()
                .map(tc -> tc.getCategory().getId())
                .collect(Collectors.toSet()) : Set.of();
        UUID txnCardholderId = (txn.getCard() != null && txn.getCard().getCardholder() != null)
                ? txn.getCard().getCardholder().getId()
                : null;
        UUID matchedCardholderId = matchCardholderId(txnCardholderId, eval);
        eval.eligible.add(new EligibleTxn(txn.getId(), effectiveDate, basis,
                txn.getInstantDiscount(), txn.getConvenienceFee(),
                txn.getAmount(), txn.getMcc(), categoryIds,
                matchedCardholderId));

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
            BigDecimal raw = accrue(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
            if (raw.signum() == 0) {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, zeroAccrualReason(rule, ruleBasis), ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
                emitted = true;
                break;
            }
            BigDecimal award = clamp(rule, raw, facts.effectiveDate(), eval, facts.cardholderId());
            if (award.signum() > 0) {
                consumeCap(rule, award, facts.effectiveDate(), eval, facts.cardholderId());
                RewardLineReason reason = award.compareTo(raw) < 0 ? RewardLineReason.PARTIAL_CAP : RewardLineReason.MATCHED;
                results.add(new TxnRuleResolution(rule, award, reason, ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
                emitted = true;
                break;
            }
            lastCapExhausted = rule;
            if (rule.getOnCapExhausted() == CapExhaustedBehavior.STOP) {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, RewardLineReason.CAP_EXHAUSTED, ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
                emitted = true;
                break;
            }
            // FALL_THROUGH: try the next matching exclusive rule.
        }
        if (!emitted) {
            if (lastCapExhausted != null) {
                BigDecimal ruleBasis = basisFor(lastCapExhausted, facts);
                results.add(new TxnRuleResolution(lastCapExhausted, BigDecimal.ZERO, RewardLineReason.CAP_EXHAUSTED, ruleBasis));
                recordTierProgress(lastCapExhausted, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
            } else if (exclusives.isEmpty()) {
                results.add(new TxnRuleResolution(null, BigDecimal.ZERO, RewardLineReason.NO_RULE, facts.basis()));
            }
        }

        // 2. ADDITIVE rules: each pays independently with its own caps.
        for (RewardRule rule : matching) {
            if (rule.getStacking() != RuleStacking.ADDITIVE) {
                continue;
            }
            BigDecimal ruleBasis = basisFor(rule, facts);
            BigDecimal raw = accrue(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
            if (raw.signum() == 0) {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, zeroAccrualReason(rule, ruleBasis), ruleBasis));
                recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
                continue;
            }
            BigDecimal award = clamp(rule, raw, facts.effectiveDate(), eval, facts.cardholderId());
            if (award.signum() > 0) {
                consumeCap(rule, award, facts.effectiveDate(), eval, facts.cardholderId());
                RewardLineReason reason = award.compareTo(raw) < 0 ? RewardLineReason.PARTIAL_CAP : RewardLineReason.MATCHED;
                results.add(new TxnRuleResolution(rule, award, reason, ruleBasis));
            } else {
                results.add(new TxnRuleResolution(rule, BigDecimal.ZERO, RewardLineReason.CAP_EXHAUSTED, ruleBasis));
            }
            recordTierProgress(rule, ruleBasis, facts.effectiveDate(), eval, facts.cardholderId());
        }

        return results;
    }

    BigDecimal basisFor(RewardRule rule, TxnFacts facts) {
        if (rule.getFeeTreatment() == FeeTreatment.EXCLUDE_FEE && facts.convenienceFee() != null) {
            return facts.basis().subtract(facts.convenienceFee()).max(BigDecimal.ZERO);
        }
        return facts.basis();
    }

    private void recordTierProgress(RewardRule rule, BigDecimal basis, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        if (!rule.isTiered() || rule.getTierWindow() == null || basis.signum() <= 0) {
            return;
        }
        eval.tierProgress.merge(tierKey(rule, effectiveDate, eval, txnCardholderId), basis, BigDecimal::add);
    }

    private CounterKey tierKey(RewardRule rule, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        Window window = windowContaining(rule.getTierWindow(), effectiveDate, eval, true);
        if (rule.getCounterScope() == CounterScope.PER_CARDHOLDER) {
            UUID effectiveCardholderId = counterCardholderId(txnCardholderId, eval);
            if (txnCardholderId == null && eval.cardholderCount >= 2) {
                eval.currentTxnAttributionIncomplete = true;
            }
            return new CounterKey(rule.getId().toString(), effectiveCardholderId, window.start());
        }
        return new CounterKey(rule.getId().toString(), null, window.start());
    }

    // ---------- predicates ----------

    private boolean matches(RewardRule rule, TxnFacts facts, Map<UUID, Pattern> regexCache, Evaluation eval) {
        // Axis 1: Rule-level cardholder scoping
        if (rule.getCardholder() != null) {
            UUID effectiveCardholderId = matchCardholderId(facts.cardholderId(), eval);
            if (effectiveCardholderId == null) {
                if (eval.cardholderCount >= 2) {
                    eval.currentTxnAttributionIncomplete = true;
                }
                return false;
            }
            if (!rule.getCardholder().getId().equals(effectiveCardholderId)) {
                return false;
            }
        }

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

        if (!rule.getDaysOfWeek().isEmpty() && !rule.getDaysOfWeek().contains(facts.date().getDayOfWeek())) {
            return false;
        }

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

    private BigDecimal accrue(RewardRule rule, BigDecimal basis, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        if (rule.isTiered()) {
            return accrueTiered(rule, basis, effectiveDate, eval, txnCardholderId);
        }
        if (rule.getAccrualType() == AccrualType.PERCENT) {
            BigDecimal raw = basis.multiply(rule.getPercentRate())
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            return roundCashback(rule, raw);
        }
        BigDecimal slabs = basis.divide(rule.getSlabSize(), 0, RoundingMode.FLOOR);
        BigDecimal points = slabs.multiply(rule.getPointsPerSlab());
        return floorPoints(rule, points);
    }

    private BigDecimal accrueTiered(RewardRule rule, BigDecimal basis, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        List<RewardTier> tiers = eval.tierSchedules.getOrDefault(rule.getId(), List.of());
        if (tiers.isEmpty() || rule.getTierWindow() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal position = eval.tierProgress.getOrDefault(tierKey(rule, effectiveDate, eval, txnCardholderId), BigDecimal.ZERO);
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

    private BigDecimal roundCashback(RewardRule rule, BigDecimal raw) {
        CashbackRounding rounding = rule.getRounding() != null ? rule.getRounding() : CashbackRounding.NONE;
        return switch (rounding) {
            case NONE -> raw.setScale(2, RoundingMode.HALF_UP);
            case FLOOR_RUPEE -> raw.setScale(0, RoundingMode.FLOOR);
            case NEAREST_RUPEE -> raw.setScale(0, RoundingMode.HALF_UP);
        };
    }

    private BigDecimal floorPoints(RewardRule rule, BigDecimal raw) {
        int precision = rule.getPointPrecision() != null ? rule.getPointPrecision() : 0;
        return raw.setScale(precision, RoundingMode.FLOOR);
    }

    private static boolean isZeroRate(RewardRule rule) {
        return rule.getAccrualType() == AccrualType.PERCENT
                ? rule.getPercentRate() == null || rule.getPercentRate().signum() == 0
                : rule.getPointsPerSlab() == null || rule.getPointsPerSlab().signum() == 0;
    }

    private RewardLineReason zeroAccrualReason(RewardRule rule, BigDecimal ruleBasis) {
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

    BigDecimal effectiveCap(RewardRule rule) {
        return rule.getCapBucket() != null ? rule.getCapBucket().getCap() : rule.getPeriodCap();
    }

    CapWindow effectiveCapWindow(RewardRule rule) {
        return rule.getCapBucket() != null ? rule.getCapBucket().getWindowType() : rule.getCapWindow();
    }

    CounterScope effectiveCounterScope(RewardRule rule) {
        return rule.getCapBucket() != null ? rule.getCapBucket().getCounterScope() : rule.getCounterScope();
    }

    private BigDecimal clamp(RewardRule rule, BigDecimal raw, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        BigDecimal award = raw;
        if (rule.getPerTxnCap() != null && award.compareTo(rule.getPerTxnCap()) > 0) {
            award = rule.getPerTxnCap();
        }
        if (rule.hasPeriodCap()) {
            BigDecimal used = eval.capUsed.getOrDefault(capKey(rule, effectiveDate, eval, txnCardholderId), BigDecimal.ZERO);
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

    private void consumeCap(RewardRule rule, BigDecimal award, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        if (rule.hasPeriodCap()) {
            eval.capUsed.merge(capKey(rule, effectiveDate, eval, txnCardholderId), award, BigDecimal::add);
        }
    }

    private CounterKey capKey(RewardRule rule, LocalDate effectiveDate, Evaluation eval, UUID txnCardholderId) {
        Window window = windowContaining(effectiveCapWindow(rule), effectiveDate, eval, true);
        if (effectiveCounterScope(rule) == CounterScope.PER_CARDHOLDER) {
            UUID effectiveCardholderId = counterCardholderId(txnCardholderId, eval);
            if (txnCardholderId == null && eval.cardholderCount >= 2) {
                eval.currentTxnAttributionIncomplete = true;
            }
            return new CounterKey(capOwner(rule), effectiveCardholderId, window.start());
        }
        return new CounterKey(capOwner(rule), null, window.start());
    }

    static String capOwner(RewardRule rule) {
        return rule.getCapBucket() != null ? "bucket|" + rule.getCapBucket().getId() : rule.getId().toString();
    }

    // ---------- windows ----------

    Window windowContaining(CapWindow capWindow, LocalDate date, Evaluation eval, boolean markFallback) {
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
                LocalDate anniversary = eval.anniversaryDate;
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
                if (markFallback) {
                    eval.cycleFallback = true;
                }
                yield new Window(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()), true);
            }
        };
    }

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
        UUID txnCardholderId = (txn.getCard() != null && txn.getCard().getCardholder() != null)
                ? txn.getCard().getCardholder().getId()
                : null;
        UUID effectiveCardholderId = matchCardholderId(txnCardholderId, eval);
        String label = effectiveCardholderId != null ? eval.cardholderLabels.getOrDefault(effectiveCardholderId, "Cardholder")
                : (txn.getCard() != null && txn.getCard().getCardholder() != null ? txn.getCard().getCardholder().getDisplayName() : null);
        return new RewardLineResponse(txn.getId(), txn.getDate(), effectiveDate,
                txn.getDescription(), txn.getSourcedDescription(), txn.getMcc(), txn.getChannel(),
                txn.getAmount(), basis,
                null, null, null, null,
                BigDecimal.ZERO, "RUPEES", reason, effectiveCardholderId, label);
    }

    private RewardLineResponse line(Transaction txn, LocalDate effectiveDate, BigDecimal basis,
                                    RewardRule rule, BigDecimal earned, RewardLineReason reason, Evaluation eval) {
        String unit = unitOf(rule);
        UUID txnCardholderId = (txn.getCard() != null && txn.getCard().getCardholder() != null)
                ? txn.getCard().getCardholder().getId()
                : null;
        UUID effectiveCardholderId = matchCardholderId(txnCardholderId, eval);
        String label = effectiveCardholderId != null ? eval.cardholderLabels.getOrDefault(effectiveCardholderId, "Cardholder")
                : (txn.getCard() != null && txn.getCard().getCardholder() != null ? txn.getCard().getCardholder().getDisplayName() : null);
        return new RewardLineResponse(txn.getId(), txn.getDate(), effectiveDate,
                txn.getDescription(), txn.getSourcedDescription(), txn.getMcc(), txn.getChannel(),
                txn.getAmount(), basis,
                rule.getId(), rule.getName(), rule.getStacking(), rule.getAccrualType(),
                earned, unit, reason, effectiveCardholderId, label);
    }

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
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = displayEligible.stream().map(EligibleTxn::convenienceFee)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

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
                Window window = windowContaining(effectiveCapWindow(rule), to, eval, false);
                CounterScope scope = effectiveCounterScope(rule);
                BigDecimal used = null;
                List<RewardReportResponse.PerCardCapUsage> perCard = new ArrayList<>();
                if (scope == CounterScope.PER_CARDHOLDER) {
                    List<Map.Entry<CounterKey, BigDecimal>> entries = eval.capUsed.entrySet().stream()
                            .filter(e -> Objects.equals(e.getKey().owner(), capOwner(rule))
                                    && Objects.equals(e.getKey().windowStart(), window.start()))
                            .toList();
                    for (Map.Entry<CounterKey, BigDecimal> entry : entries) {
                        UUID cid = entry.getKey().cardholderId();
                        String label = cid != null ? eval.cardholderLabels.getOrDefault(cid, "Cardholder") : "Unattributed";
                        perCard.add(new RewardReportResponse.PerCardCapUsage(cid, label, entry.getValue()));
                    }
                    used = null;
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
        if (eval.cardholderCount >= 2) {
            UUID NULL_KEY = new UUID(0L, 0L);
            Map<UUID, List<EligibleTxn>> txnsByCard = displayEligible.stream()
                    .collect(Collectors.groupingBy(e -> e.cardholderId() != null ? e.cardholderId() : NULL_KEY));

            Map<UUID, List<RewardLineResponse>> linesByCard = displayLines.stream()
                    .filter(l -> l.earned() != null && l.earned().signum() > 0)
                    .collect(Collectors.groupingBy(l -> l.cardId() != null ? l.cardId() : NULL_KEY));

            for (Cardholder cardholder : eval.allCardholders) {
                List<EligibleTxn> cardTxns = txnsByCard.getOrDefault(cardholder.getId(), List.of());
                List<RewardLineResponse> cardLines = linesByCard.getOrDefault(cardholder.getId(), List.of());

                if (cardholder.isEffectivelyClosed() && cardTxns.isEmpty() && cardLines.isEmpty()) {
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
                String label = eval.cardholderLabels.getOrDefault(cardholder.getId(), cardholder.getDisplayName());
                byCard.add(new RewardReportResponse.CardBreakdown(
                        cardholder.getId(), label, false, basis, cb, pts, cardTxns.size()));
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

    List<RewardReportResponse.MilestoneStatus> evaluateMilestones(Evaluation eval, LocalDate from, LocalDate to) {
        List<RewardReportResponse.MilestoneStatus> statuses = new ArrayList<>();
        for (MilestoneWithEligibility entry : eval.milestones) {
            RewardMilestone milestone = entry.milestone();
            MilestoneEligibility eligibility = entry.eligibility();
            if (milestone.getWindowType() == MilestoneWindow.ONE_TIME) {
                statuses.add(milestoneStatus(milestone, eligibility,
                        milestone.getActiveFrom(), milestone.getActiveTo().minusDays(1), eval, from, to));
                continue;
            }
            LocalDate cursor = from;
            boolean firstWindow = true;
            int guard = 0;
            while (!cursor.isAfter(to)) {
                if (guard++ >= 500) {
                    throw new ValidationException(
                            "Date range too large for milestone evaluation — please narrow the range.");
                }
                Window window = windowContaining(milestone.getWindowType().asCapWindow(), cursor, eval, true);
                LocalDate countStart = !firstWindow && window.start().isBefore(cursor) ? cursor : window.start();
                firstWindow = false;
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

    private RewardReportResponse.MilestoneStatus milestoneStatus(
            RewardMilestone milestone, MilestoneEligibility eligibility,
            LocalDate countStart, LocalDate countEnd, Evaluation eval, LocalDate from, LocalDate to) {
        BigDecimal progress = BigDecimal.ZERO;
        LocalDate achievedOn = null;
        for (EligibleTxn txn : eval.eligible) {
            if (txn.effectiveDate().isBefore(countStart) || txn.effectiveDate().isAfter(countEnd)) {
                continue;
            }
            if (milestone.getCardholder() != null) {
                UUID effectiveCardholderId = matchCardholderId(txn.cardholderId(), eval);
                if (effectiveCardholderId == null) {
                    if (eval.cardholderCount >= 2) {
                        eval.perCardAttributionIncompleteTxnIds.add(txn.id());
                    }
                    continue;
                }
                if (!milestone.getCardholder().getId().equals(effectiveCardholderId)) {
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

    UUID matchCardholderId(UUID txnCardholderId, Evaluation eval) {
        if (txnCardholderId != null) {
            return txnCardholderId;
        }
        if (eval.soleCardholderId != null) {
            return eval.soleCardholderId;
        }
        return null;
    }

    UUID counterCardholderId(UUID txnCardholderId, Evaluation eval) {
        if (txnCardholderId != null) {
            return txnCardholderId;
        }
        if (eval.primaryCardholderId != null) {
            return eval.primaryCardholderId;
        }
        if (eval.soleCardholderId != null) {
            return eval.soleCardholderId;
        }
        return null;
    }

    private static BigDecimal pct(BigDecimal value, BigDecimal basis) {
        if (basis == null || basis.signum() == 0) {
            return null;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(basis, 2, RoundingMode.HALF_UP);
    }
}
