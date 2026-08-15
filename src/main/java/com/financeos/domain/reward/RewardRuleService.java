package com.financeos.domain.reward;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.reward.dto.RewardRuleRequest;
import com.financeos.api.reward.dto.RewardRuleResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.user.UserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * CRUD + definition validation for {@link RewardRule}. Rule definitions are saved
 * as full overwrites (create and update share the same request shape), so the
 * builder UI never has to reason about partial-update semantics.
 */
@Service
@Transactional
@Slf4j
public class RewardRuleService {

    private static final Pattern MCC_PATTERN = Pattern.compile("^\\d{4}$");

    private final RewardRuleRepository rewardRuleRepository;
    private final RewardCapBucketRepository rewardCapBucketRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public RewardRuleService(RewardRuleRepository rewardRuleRepository,
                             RewardCapBucketRepository rewardCapBucketRepository,
                             AccountRepository accountRepository,
                             CategoryRepository categoryRepository,
                             UserRepository userRepository,
                             ObjectMapper objectMapper) {
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardCapBucketRepository = rewardCapBucketRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RewardRuleResponse> listForAccount(UUID accountId) {
        // toResponse streams every lazy collection while the transaction is still
        // open (open-in-view is off), so no explicit initialization is needed.
        return rewardRuleRepository.findByAccountIdOrderByPriorityDesc(accountId).stream()
                .map(this::toResponse)
                .toList();
    }

    public RewardRuleResponse toResponse(RewardRule rule) {
        return RewardRuleResponse.from(rule, parseTiers(rule));
    }

    /** Package-private: the calculation engine parses tiers through the same code path. */
    List<RewardTier> parseTiers(RewardRule rule) {
        if (!rule.isTiered()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rule.getTiers(), new TypeReference<List<RewardTier>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("Reward rule {} has unparseable tiers JSON; the engine will skip this rule entirely", rule.getId());
            return List.of();
        }
    }

    public RewardRuleResponse create(RewardRuleRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        if (request.accountId() == null) {
            throw new ValidationException("Account ID is required");
        }
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to add rules to this account.");
        }

        RewardRule rule = new RewardRule();
        rule.setUser(userRepository.getReferenceById(currentSessionUserId));
        rule.setAccount(account);
        applyDefinition(rule, request, currentSessionUserId);
        return toResponse(rewardRuleRepository.save(rule));
    }

    public RewardRuleResponse update(UUID id, RewardRuleRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        RewardRule rule = rewardRuleRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reward rule", id));
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this rule.");
        }

        applyDefinition(rule, request, currentSessionUserId);
        return toResponse(rewardRuleRepository.save(rule));
    }

    public void delete(UUID id) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        RewardRule rule = rewardRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reward rule", id));
        if (!rule.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to delete this rule.");
        }
        rewardRuleRepository.delete(rule);
    }

    /** Reassigns priorities so orderedIds[0] is evaluated first. Must cover ALL of the account's rules. */
    public List<RewardRuleResponse> reorder(UUID accountId, List<UUID> orderedIds) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify rules on this account.");
        }

        List<RewardRule> rules = rewardRuleRepository.findByAccountIdOrderByPriorityDesc(accountId);
        Set<UUID> existingIds = new HashSet<>(rules.stream().map(RewardRule::getId).toList());
        Set<UUID> orderedSet = new HashSet<>(orderedIds);
        // Set equality also rejects duplicated ids masking a missing one.
        if (orderedSet.size() != orderedIds.size() || !existingIds.equals(orderedSet)) {
            throw new ValidationException("orderedIds must contain exactly the account's rule ids, without duplicates.");
        }

        int priority = orderedIds.size();
        for (UUID ruleId : orderedIds) {
            RewardRule rule = rules.stream().filter(r -> r.getId().equals(ruleId)).findFirst().orElseThrow();
            rule.setPriority(priority--);
        }
        rewardRuleRepository.saveAll(rules);
        return listForAccount(accountId);
    }

    // ---- definition mapping + validation ----

    private void applyDefinition(RewardRule rule, RewardRuleRequest request, UUID currentSessionUserId) {
        rule.setName(request.name().trim());
        rule.setPriority(request.priority());
        rule.setStacking(parseEnum(RuleStacking.class, request.stacking(), RuleStacking.EXCLUSIVE));

        if (request.activeFrom() != null && request.activeTo() != null
                && !request.activeFrom().isBefore(request.activeTo())) {
            throw new ValidationException("Active-from must be before active-to.");
        }
        rule.setActiveFrom(request.activeFrom());
        rule.setActiveTo(request.activeTo());

        // Unset = the card's default; must be resolved before caps (bucket unit check).
        rule.setRewardType(parseEnum(RewardType.class, request.rewardType(),
                rule.getAccount().getDefaultRewardType()));

        applyPredicates(rule, request, currentSessionUserId);
        applyAccrual(rule, request);
        applyCaps(rule, request, currentSessionUserId);
    }

    private void applyPredicates(RewardRule rule, RewardRuleRequest request, UUID currentSessionUserId) {
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            List<Category> categories = categoryRepository.findAllById(request.categoryIds());
            if (categories.size() != request.categoryIds().size()) {
                throw new ResourceNotFoundException("One or more categories not found");
            }
            for (Category category : categories) {
                if (!category.getUser().getId().equals(currentSessionUserId)) {
                    throw new ValidationException("You do not have permission to use category: " + category.getName());
                }
            }
            rule.getCategories().clear();
            rule.getCategories().addAll(categories);
        } else {
            rule.getCategories().clear();
        }

        Set<String> mccs = new HashSet<>();
        if (request.mccs() != null) {
            for (String mcc : request.mccs()) {
                if (mcc == null || mcc.isBlank()) {
                    continue;
                }
                String trimmed = mcc.trim();
                if (!MCC_PATTERN.matcher(trimmed).matches()) {
                    throw new ValidationException("MCC must be a 4-digit code: " + trimmed);
                }
                mccs.add(trimmed);
            }
        }
        rule.getMccs().clear();
        rule.getMccs().addAll(mccs);

        rule.getChannels().clear();
        if (request.channels() != null) {
            for (String channel : request.channels()) {
                rule.getChannels().add(parseEnum(TransactionChannel.class, channel, null));
            }
        }

        rule.getDaysOfWeek().clear();
        if (request.daysOfWeek() != null) {
            for (String day : request.daysOfWeek()) {
                rule.getDaysOfWeek().add(parseEnum(DayOfWeek.class, day, null));
            }
        }

        boolean hasPattern = request.merchantPattern() != null && !request.merchantPattern().isBlank();
        RewardMerchantMatch merchantMatch = parseEnum(RewardMerchantMatch.class, request.merchantMatch(), null);
        if (hasPattern && merchantMatch == null) {
            throw new ValidationException("Merchant match type is required when a merchant pattern is set.");
        }
        if (!hasPattern && merchantMatch != null) {
            throw new ValidationException("Merchant pattern is required when a merchant match type is set.");
        }
        if (hasPattern && merchantMatch == RewardMerchantMatch.REGEX) {
            try {
                Pattern.compile(request.merchantPattern().trim());
            } catch (PatternSyntaxException e) {
                throw new ValidationException("Invalid regex pattern: " + e.getDescription());
            }
        }
        rule.setMerchantPattern(hasPattern ? request.merchantPattern().trim() : null);
        rule.setMerchantMatch(hasPattern ? merchantMatch : null);

        if (request.minAmount() != null && request.minAmount().signum() < 0) {
            throw new ValidationException("Minimum amount cannot be negative.");
        }
        if (request.minAmount() != null && request.maxAmount() != null
                && request.minAmount().compareTo(request.maxAmount()) > 0) {
            throw new ValidationException("Minimum amount cannot exceed maximum amount.");
        }
        rule.setMinAmount(request.minAmount());
        rule.setMaxAmount(request.maxAmount());

        rule.setEmiTreatment(parseEnum(EmiTreatment.class, request.emiTreatment(), EmiTreatment.INCLUDE));
        rule.setIntlTreatment(parseEnum(IntlTreatment.class, request.intlTreatment(), IntlTreatment.INCLUDE));
        rule.setFeeTreatment(parseEnum(FeeTreatment.class, request.feeTreatment(), FeeTreatment.INCLUDE));
    }

    private void applyAccrual(RewardRule rule, RewardRuleRequest request) {
        AccrualType accrualType = parseEnum(AccrualType.class, request.accrualType(), null);
        rule.setAccrualType(accrualType);

        boolean tiered = request.tiers() != null && !request.tiers().isEmpty();
        applyTiers(rule, request, tiered);

        if (accrualType == AccrualType.PERCENT) {
            if (!tiered && (request.percentRate() == null || request.percentRate().signum() < 0)) {
                throw new ValidationException("Percent rate is required and cannot be negative (0% models an exclusion).");
            }
            rule.setPercentRate(tiered ? null : request.percentRate());
            rule.setRounding(parseEnum(CashbackRounding.class, request.rounding(), CashbackRounding.NONE));
            rule.setSlabSize(null);
            rule.setPointsPerSlab(null);
            rule.setPointPrecision(null);
        } else {
            if (request.slabSize() == null || request.slabSize().signum() <= 0) {
                throw new ValidationException("Slab size is required and must be positive.");
            }
            if (!tiered && (request.pointsPerSlab() == null || request.pointsPerSlab().signum() < 0)) {
                throw new ValidationException("Reward per slab is required and cannot be negative (0 models an exclusion).");
            }
            if (request.pointPrecision() != null && (request.pointPrecision() < 0 || request.pointPrecision() > 2)) {
                throw new ValidationException("Point precision must be between 0 and 2.");
            }
            rule.setSlabSize(request.slabSize());
            rule.setPointsPerSlab(tiered ? null : request.pointsPerSlab());
            rule.setPointPrecision(request.pointPrecision());
            rule.setPercentRate(null);
            rule.setRounding(null);
        }
    }

    /** Tier schedule: ascending upTo breakpoints, open-ended (null) last tranche, non-negative rates. */
    private void applyTiers(RewardRule rule, RewardRuleRequest request, boolean tiered) {
        if (!tiered) {
            rule.setTierWindow(null);
            rule.setTiers(null);
            return;
        }
        CapWindow tierWindow = parseEnum(CapWindow.class, request.tierWindow(), null);
        if (tierWindow == null) {
            throw new ValidationException("Tier window is required when tiers are set.");
        }
        List<RewardRuleRequest.TierRequest> tiers = request.tiers();
        java.math.BigDecimal previousUpTo = null;
        for (int i = 0; i < tiers.size(); i++) {
            RewardRuleRequest.TierRequest tier = tiers.get(i);
            if (tier.rate() == null || tier.rate().signum() < 0) {
                throw new ValidationException("Every tier needs a non-negative rate.");
            }
            boolean last = i == tiers.size() - 1;
            if (last) {
                if (tier.upTo() != null) {
                    throw new ValidationException("The last tier must be open-ended (no 'up to').");
                }
            } else {
                if (tier.upTo() == null || tier.upTo().signum() <= 0) {
                    throw new ValidationException("Every tier except the last needs a positive 'up to' breakpoint.");
                }
                if (previousUpTo != null && tier.upTo().compareTo(previousUpTo) <= 0) {
                    throw new ValidationException("Tier breakpoints must be strictly increasing.");
                }
                previousUpTo = tier.upTo();
            }
        }
        rule.setTierWindow(tierWindow);
        try {
            List<RewardTier> parsed = tiers.stream().map(t -> new RewardTier(t.upTo(), t.rate())).toList();
            rule.setTiers(objectMapper.writeValueAsString(parsed));
        } catch (JsonProcessingException e) {
            throw new ValidationException("Could not serialize tiers.");
        }
    }

    private void applyCaps(RewardRule rule, RewardRuleRequest request, UUID currentSessionUserId) {
        if (request.perTxnCap() != null && request.perTxnCap().signum() <= 0) {
            throw new ValidationException("Per-transaction cap must be positive when set.");
        }
        if (request.periodCap() != null && request.periodCap().signum() <= 0) {
            throw new ValidationException("Period cap must be positive when set.");
        }
        if (request.periodCap() != null && request.capBucketId() != null) {
            throw new ValidationException("Choose either an own period cap or a shared bucket, not both.");
        }
        CapWindow capWindow = parseEnum(CapWindow.class, request.capWindow(), null);
        if (request.periodCap() != null && capWindow == null) {
            throw new ValidationException("Cap window is required when a period cap is set.");
        }
        rule.setPerTxnCap(request.perTxnCap());
        rule.setPeriodCap(request.periodCap());
        rule.setCapWindow(request.periodCap() != null ? capWindow : null);
        rule.setCapBucket(resolveCapBucket(rule, request, currentSessionUserId));
        rule.setOnCapExhausted(parseEnum(CapExhaustedBehavior.class, request.onCapExhausted(), CapExhaustedBehavior.FALL_THROUGH));
    }

    private RewardCapBucket resolveCapBucket(RewardRule rule, RewardRuleRequest request, UUID currentSessionUserId) {
        if (request.capBucketId() == null) {
            return null;
        }
        RewardCapBucket bucket = rewardCapBucketRepository.findById(request.capBucketId())
                .orElseThrow(() -> new ResourceNotFoundException("Cap bucket", request.capBucketId()));
        if (!bucket.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to use this cap bucket.");
        }
        if (!bucket.getAccount().getId().equals(rule.getAccount().getId())) {
            throw new ValidationException("Cap bucket belongs to a different account.");
        }
        // The bucket's cap unit is its reward type — a mixed cash/points ceiling makes no sense.
        if (bucket.getRewardType() != rule.getRewardType()) {
            throw new ValidationException(
                    "This bucket holds " + bucket.getRewardType().name().toLowerCase(Locale.ROOT)
                            + " rules — a " + rule.getRewardType().name().toLowerCase(Locale.ROOT)
                            + " rule cannot share it.");
        }
        return bucket;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }
}
