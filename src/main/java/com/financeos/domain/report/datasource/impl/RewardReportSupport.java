package com.financeos.domain.report.datasource.impl;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared plumbing for the reward report datasources ({@code reward_earnings},
 * {@code reward_milestones}, {@code reward_caps}): which cards to evaluate, over what
 * range, point valuation, and unique display labels for rules and milestones.
 */
@Component
public class RewardReportSupport {

    static final String NONE = "(none)";
    static final String UNIT_RUPEES = "RUPEES";
    static final String UNIT_POINTS = "POINTS";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final RewardMilestoneRepository rewardMilestoneRepository;

    public RewardReportSupport(AccountRepository accountRepository,
                               TransactionRepository transactionRepository,
                               RewardRuleRepository rewardRuleRepository,
                               RewardMilestoneRepository rewardMilestoneRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardMilestoneRepository = rewardMilestoneRepository;
    }

    /** The evaluation range for one card. */
    record DateBounds(LocalDate from, LocalDate to) {
    }

    /** Cards that have at least one reward rule. */
    List<Account> ruleAccounts(UUID userId) {
        return accounts(rewardRuleRepository.findDistinctAccountIdsByUserId(userId));
    }

    /** Cards that have at least one milestone. */
    List<Account> milestoneAccounts(UUID userId) {
        return accounts(rewardMilestoneRepository.findDistinctAccountIdsByUserId(userId));
    }

    private List<Account> accounts(@Nullable List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Account> out = new ArrayList<>();
        for (UUID id : ids) {
            accountRepository.findById(id).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Every effective date on the card: earliest to the later of today and the latest
     * (future-dated settlements included). Null when the card has no transactions.
     */
    @Nullable
    DateBounds bounds(UUID accountId) {
        TransactionRepository.EffectiveDateSpan span = transactionRepository.effectiveDateSpan(accountId, AppTime.today());
        return span == null ? null : new DateBounds(span.from(), span.to());
    }

    /** Rupee value of an amount in a reward unit; unvalued points are worth zero. */
    static BigDecimal valueInr(@Nullable BigDecimal amount, @Nullable String unit, @Nullable BigDecimal pointValueInr) {
        if (amount == null) {
            return zero();
        }
        if (UNIT_RUPEES.equals(unit)) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        if (UNIT_POINTS.equals(unit) && pointValueInr != null) {
            return amount.multiply(pointValueInr).setScale(2, RoundingMode.HALF_UP);
        }
        return zero();
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /** Unique label per rule across all the given cards (see {@link #disambiguate}). */
    Map<UUID, String> ruleLabels(List<Account> accounts) {
        List<LabelSource> sources = new ArrayList<>();
        for (Account account : accounts) {
            for (RewardRule rule : rewardRuleRepository.findByAccountIdOrderByPriorityDesc(account.getId())) {
                sources.add(new LabelSource(rule.getId(), rule.getName(), account.getName(),
                        rule.getActiveFrom(), rule.getActiveTo()));
            }
        }
        return disambiguate(sources);
    }

    /** Unique label per milestone across all the given cards (see {@link #disambiguate}). */
    Map<UUID, String> milestoneLabels(List<Account> accounts) {
        List<LabelSource> sources = new ArrayList<>();
        for (Account account : accounts) {
            for (RewardMilestone m : rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())) {
                sources.add(new LabelSource(m.getId(), m.getName(), account.getName(),
                        m.getActiveFrom(), m.getActiveTo()));
            }
        }
        return disambiguate(sources);
    }

    /** A named item to label; {@code activeTo} is exclusive. */
    record LabelSource(UUID id, String name, String cardName,
                       @Nullable LocalDate activeFrom, @Nullable LocalDate activeTo) {
    }

    /**
     * Names are not unique: two cards can each have a "Base 1%" rule, and the devaluation
     * flow (end-date and clone) leaves two same-named rules on one card. A unique name
     * stays as is. A shared name gets " · card" when it spans cards, then an active
     * range " (from → to)" when it still collides on one card, then " #n" as a last resort.
     */
    static Map<UUID, String> disambiguate(List<LabelSource> sources) {
        Map<String, List<LabelSource>> byName = new LinkedHashMap<>();
        for (LabelSource s : sources) {
            byName.computeIfAbsent(key(s.name()), k -> new ArrayList<>()).add(s);
        }
        Map<UUID, String> labels = new HashMap<>();
        for (List<LabelSource> group : byName.values()) {
            if (group.size() == 1) {
                labels.put(group.get(0).id(), displayName(group.get(0).name()));
                continue;
            }
            boolean spansCards = group.stream().map(LabelSource::cardName).distinct().count() > 1;
            Map<String, List<LabelSource>> byCard = new LinkedHashMap<>();
            for (LabelSource s : group) {
                String base = displayName(s.name()) + (spansCards ? " · " + s.cardName() : "");
                byCard.computeIfAbsent(base, k -> new ArrayList<>()).add(s);
            }
            for (Map.Entry<String, List<LabelSource>> entry : byCard.entrySet()) {
                List<LabelSource> sameCard = entry.getValue();
                if (sameCard.size() == 1) {
                    labels.put(sameCard.get(0).id(), entry.getKey());
                    continue;
                }
                Map<String, Integer> seen = new HashMap<>();
                for (LabelSource s : sameCard) {
                    String label = entry.getKey() + " (" + range(s) + ")";
                    int n = seen.merge(label, 1, Integer::sum);
                    labels.put(s.id(), n == 1 ? label : label + " #" + n);
                }
            }
        }
        return labels;
    }

    private static String key(@Nullable String name) {
        return displayName(name).toLowerCase(Locale.ROOT);
    }

    private static String displayName(@Nullable String name) {
        return name == null || name.isBlank() ? "Unnamed" : name.trim();
    }

    private static String range(LabelSource s) {
        String from = s.activeFrom() != null ? s.activeFrom().toString() : "start";
        String to = s.activeTo() != null ? s.activeTo().minusDays(1).toString() : "now";
        return from + " → " + to;
    }

    static String period(LocalDate start, LocalDate end) {
        return start + " → " + end;
    }

    static String label(@Nullable String value, String fallback) {
        return Objects.requireNonNullElse(value, fallback);
    }
}
