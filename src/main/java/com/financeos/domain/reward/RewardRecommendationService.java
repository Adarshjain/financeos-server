package com.financeos.domain.reward;

import com.financeos.api.reward.dto.RewardCardRecommendationResponse;
import com.financeos.api.reward.dto.RewardRecommendationRequest;
import com.financeos.api.reward.dto.RewardRecommendationResponse;
import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.api.reward.dto.SimulatedCapStatusResponse;
import com.financeos.api.reward.dto.SimulatedMilestoneResponse;
import com.financeos.api.reward.dto.SimulatedRuleLineResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RewardRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RewardRecommendationService.class);

    public static final BigDecimal DEFAULT_POINT_VALUE_INR = new BigDecimal("0.25");

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final RewardCalculationService rewardCalculationService;

    public RewardRecommendationService(AccountRepository accountRepository,
                                       CategoryRepository categoryRepository,
                                       RewardRuleRepository rewardRuleRepository,
                                       RewardCalculationService rewardCalculationService) {
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardCalculationService = rewardCalculationService;
    }

    @Transactional(readOnly = true)
    public RewardRecommendationResponse recommend(RewardRecommendationRequest request) {
        UUID currentUserId = UserContext.getCurrentUserId();

        // 1. Category ownership validation
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            List<Category> userCategories = categoryRepository.findByUserId(currentUserId);
            Set<UUID> ownedCategoryIds = userCategories.stream()
                    .map(Category::getId)
                    .collect(Collectors.toSet());
            if (!ownedCategoryIds.containsAll(request.categoryIds())) {
                throw new ValidationException("One or more category IDs are invalid or do not belong to the current user.");
            }
        }

        // 2. Candidate accounts resolution and ownership validation
        List<Account> candidateAccounts = new ArrayList<>();
        if (request.accountIds() != null && !request.accountIds().isEmpty()) {
            for (UUID accountId : request.accountIds()) {
                Account account = accountRepository.findById(accountId)
                        .orElseThrow(() -> new ValidationException("Account not found: " + accountId));
                if (!account.getUser().getId().equals(currentUserId)) {
                    throw new ValidationException("You do not have permission to view rewards for this account.");
                }
                candidateAccounts.add(account);
            }
        } else {
            List<Account> userAccounts = accountRepository.findByUserId(currentUserId);
            for (Account acct : userAccounts) {
                if (acct.getType() == AccountType.credit_card || rewardRuleRepository.countByAccountId(acct.getId()) > 0) {
                    candidateAccounts.add(acct);
                }
            }
        }

        LocalDate evalDate = request.date() != null ? request.date() : LocalDate.now();
        boolean isEmi = Boolean.TRUE.equals(request.isEmi());
        boolean isIntl = Boolean.TRUE.equals(request.isIntl());
        Set<UUID> categoryIds = request.categoryIds() != null ? request.categoryIds() : Set.of();

        List<RewardCardRecommendationResponse> rawRecommendations = new ArrayList<>();

        for (Account account : candidateAccounts) {
            // Evaluate engine state as of evalDate
            RewardCalculationService.Evaluation eval = rewardCalculationService.evaluate(account.getId(), evalDate, evalDate, true);

            // Record cap headroom before simulated spend
            Map<UUID, SimulatedCapStatusResponse> ruleCapStatusBefore = new HashMap<>();
            for (RewardRule rule : eval.rules) {
                if (rule.hasPeriodCap()) {
                    CapWindow windowType = rewardCalculationService.effectiveCapWindow(rule);
                    BigDecimal totalCap = rewardCalculationService.effectiveCap(rule);
                    RewardCalculationService.Window window = rewardCalculationService.windowContaining(windowType, evalDate, eval, false);
                    String capOwnerKey = RewardCalculationService.capOwner(rule) + "|" + window.start();
                    BigDecimal usedBefore = eval.capUsed.getOrDefault(capOwnerKey, BigDecimal.ZERO);
                    BigDecimal capRemainingBefore = totalCap.subtract(usedBefore).max(BigDecimal.ZERO);
                    String bucketName = rule.getCapBucket() != null ? rule.getCapBucket().getName() : null;
                    ruleCapStatusBefore.put(rule.getId(), new SimulatedCapStatusResponse(
                            windowType, totalCap, usedBefore, capRemainingBefore, window.end(), bucketName));
                }
            }

            // Build hypothetical TxnFacts
            RewardCalculationService.TxnFacts facts = new RewardCalculationService.TxnFacts(
                    evalDate,
                    evalDate,
                    request.amount(),
                    request.amount(),
                    null, // a hypothetical swipe carries no labeled surcharge yet
                    request.mcc(),
                    request.channel(),
                    categoryIds,
                    request.merchantText(),
                    request.merchantText(),
                    isEmi,
                    isIntl,
                    request.cardId()
            );

            // Single-txn resolution against evaluated state
            Map<UUID, Pattern> regexCache = new HashMap<>();
            List<RewardCalculationService.TxnRuleResolution> resolutions = rewardCalculationService.resolveTxnFacts(facts, eval, regexCache);

            BigDecimal pointValueInr = account.getPointValueInr() != null ? account.getPointValueInr() : DEFAULT_POINT_VALUE_INR;
            boolean hasConfiguredPointValue = account.getPointValueInr() != null;
            boolean anyPointsConverted = false;

            BigDecimal guaranteedValueInr = BigDecimal.ZERO;
            List<SimulatedRuleLineResponse> ruleLineResponses = new ArrayList<>();

            for (RewardCalculationService.TxnRuleResolution res : resolutions) {
                UUID ruleId = res.rule() != null ? res.rule().getId() : null;
                String ruleName = res.rule() != null ? res.rule().getName() : null;
                RuleStacking stacking = res.rule() != null ? res.rule().getStacking() : null;
                BigDecimal earned = res.earned();
                String earnedUnit = res.rule() != null ? RewardCalculationService.unitOf(res.rule()) : RewardCalculationService.UNIT_RUPEES;

                BigDecimal lineValueInr;
                if (RewardCalculationService.UNIT_POINTS.equals(earnedUnit)) {
                    lineValueInr = earned.multiply(pointValueInr).setScale(2, RoundingMode.HALF_UP);
                    if (earned.signum() > 0) {
                        anyPointsConverted = true;
                    }
                } else {
                    lineValueInr = earned;
                }
                guaranteedValueInr = guaranteedValueInr.add(lineValueInr);

                SimulatedCapStatusResponse lineCapStatus = (res.rule() != null && res.rule().hasPeriodCap())
                        ? ruleCapStatusBefore.get(res.rule().getId()) : null;

                ruleLineResponses.add(new SimulatedRuleLineResponse(
                        ruleId, ruleName, stacking, earned, earnedUnit, lineValueInr, res.reason(), lineCapStatus));
            }

            // Milestone processing
            List<RewardReportResponse.MilestoneStatus> milestoneStatuses = rewardCalculationService.evaluateMilestones(eval, evalDate, evalDate);
            BigDecimal milestoneValueInr = BigDecimal.ZERO;
            List<SimulatedMilestoneResponse> milestoneResponses = new ArrayList<>();

            for (RewardReportResponse.MilestoneStatus status : milestoneStatuses) {
                if (evalDate.isBefore(status.windowStart()) || evalDate.isAfter(status.windowEnd())) {
                    continue;
                }
                if (status.achieved()) {
                    continue;
                }
                RewardCalculationService.MilestoneWithEligibility entry = eval.milestones.stream()
                        .filter(m -> m.milestone().getId().equals(status.milestoneId()))
                        .findFirst()
                        .orElse(null);
                if (entry == null || !rewardCalculationService.milestoneEligible(entry.eligibility(), facts)) {
                    continue;
                }
                if (status.basis() == MilestoneBasis.TXN_COUNT && status.minTxnAmount() != null
                        && request.amount().compareTo(status.minTxnAmount()) < 0) {
                    continue;
                }

                BigDecimal delta = status.basis() == MilestoneBasis.SPEND ? request.amount() : BigDecimal.ONE;
                boolean crosses = status.progress().add(delta).compareTo(status.threshold()) >= 0;
                BigDecimal remainingToThreshold = status.threshold().subtract(status.progress()).max(BigDecimal.ZERO);

                BigDecimal payoutInr = null;
                BigDecimal scoredValueInr = BigDecimal.ZERO;

                if (status.payoutType() == MilestonePayoutType.CASH_VALUE) {
                    if (status.rewardType() == RewardType.POINTS) {
                        payoutInr = status.payoutValue().multiply(pointValueInr).setScale(2, RoundingMode.HALF_UP);
                        anyPointsConverted = true;
                    } else {
                        payoutInr = status.payoutValue();
                    }

                    if (crosses) {
                        scoredValueInr = payoutInr;
                    } else {
                        scoredValueInr = delta.divide(status.threshold(), 6, RoundingMode.HALF_UP)
                                .multiply(payoutInr)
                                .setScale(2, RoundingMode.HALF_UP);
                    }
                }

                milestoneValueInr = milestoneValueInr.add(scoredValueInr);
                milestoneResponses.add(new SimulatedMilestoneResponse(
                        status.milestoneId(),
                        status.name(),
                        status.windowEnd(),
                        status.progress(),
                        status.threshold(),
                        remainingToThreshold,
                        crosses,
                        payoutInr,
                        scoredValueInr,
                        status.payoutType()
                ));
            }

            BigDecimal totalValueInr = guaranteedValueInr.add(milestoneValueInr);
            BigDecimal effectiveRatePct = request.amount().signum() > 0
                    ? totalValueInr.multiply(BigDecimal.valueOf(100)).divide(request.amount(), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // Provenance describes the config, not whether it mattered: pointsValued says that.
            String pointValueSource = hasConfiguredPointValue ? "CONFIG" : "DEFAULT";

            rawRecommendations.add(new RewardCardRecommendationResponse(
                    account.getId(),
                    account.getName(),
                    0, // set after sorting
                    totalValueInr,
                    guaranteedValueInr,
                    milestoneValueInr,
                    effectiveRatePct,
                    pointValueSource,
                    pointValueInr,
                    anyPointsConverted,
                    ruleLineResponses,
                    milestoneResponses,
                    eval.rules.isEmpty(),
                    eval.cycleFallback,
                    eval.anniversaryFallback
            ));
        }

        // Sort recommendations: totalValueInr desc, guaranteedValueInr desc, accountName asc
        rawRecommendations.sort(Comparator.comparing(RewardCardRecommendationResponse::totalValueInr).reversed()
                .thenComparing(Comparator.comparing(RewardCardRecommendationResponse::guaranteedValueInr).reversed())
                .thenComparing(RewardCardRecommendationResponse::accountName));

        List<RewardCardRecommendationResponse> rankedRecommendations = new ArrayList<>();
        for (int i = 0; i < rawRecommendations.size(); i++) {
            RewardCardRecommendationResponse card = rawRecommendations.get(i);
            rankedRecommendations.add(new RewardCardRecommendationResponse(
                    card.accountId(),
                    card.accountName(),
                    i + 1,
                    card.totalValueInr(),
                    card.guaranteedValueInr(),
                    card.milestoneValueInr(),
                    card.effectiveRatePct(),
                    card.pointValueSource(),
                    card.pointValueInr(),
                    card.pointsValued(),
                    card.ruleLines(),
                    card.milestones(),
                    card.noRulesConfigured(),
                    card.cycleFallback(),
                    card.anniversaryFallback()
            ));
        }

        String topCards = rankedRecommendations.stream()
                .limit(3)
                .map(r -> r.accountName() + ":₹" + r.totalValueInr())
                .collect(Collectors.joining(","));

        log.info("Reward recommend ranked: txnAmount={}, merchant={}, topCards={}",
                request.amount(), request.merchantText(), topCards,
                StructuredArguments.keyValue("event", Events.REWARD_RECOMMEND_RANKED),
                StructuredArguments.keyValue("txnAmount", request.amount() != null ? request.amount().toString() : "0"),
                StructuredArguments.keyValue("merchant", request.merchantText() != null ? request.merchantText() : ""),
                StructuredArguments.keyValue("topCards", topCards));

        return new RewardRecommendationResponse(request, rankedRecommendations);
    }
}
