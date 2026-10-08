package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_INFO;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_INFO;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.notification.reward.RewardAlertNotificationService;
import com.financeos.domain.reward.RewardCapBucket;
import com.financeos.domain.reward.RewardCapBucketRepository;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardRuleRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Rewards news the daily alert pass already recorded on its own rows: milestones achieved and
 * caps exhausted in the last month (by the date the marker was recorded; rows marked before that
 * date was kept fall back to the window start). Reads the markers only — never the rewards engine.
 */
@Component
public class RewardInboxCollector implements InboxCollector {

    static final int RECENT_DAYS = 30;

    private final AccountRepository accountRepository;
    private final RewardMilestoneRepository milestoneRepository;
    private final RewardCapBucketRepository bucketRepository;
    private final RewardRuleRepository ruleRepository;

    public RewardInboxCollector(AccountRepository accountRepository,
                                RewardMilestoneRepository milestoneRepository,
                                RewardCapBucketRepository bucketRepository,
                                RewardRuleRepository ruleRepository) {
        this.accountRepository = accountRepository;
        this.milestoneRepository = milestoneRepository;
        this.bucketRepository = bucketRepository;
        this.ruleRepository = ruleRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        LocalDate since = today.minusDays(RECENT_DAYS);
        List<InboxItemResponse> rows = new ArrayList<>();
        for (Account card : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (card.isClosed(today)) {
                continue;
            }
            String cardLabel = InboxRows.cardLabel(card.getName(), card.primaryLast4());
            String href = "/rewards?account=" + card.getId();
            for (RewardMilestone milestone : milestoneRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())) {
                if (!RewardAlertNotificationService.KIND_ACHIEVED.equals(milestone.getNotifiedKind()) || !recent(milestone.getNotifiedOn(), milestone.getNotifiedWindowStart(), since)) {
                    continue;
                }
                rows.add(row(InboxKinds.rewardMilestoneKey(milestone.getId(), milestone.getNotifiedWindowStart()), InboxKinds.REWARD_MILESTONE,
                        milestone.getName() + " unlocked", cardLabel + " · window from " + InboxRows.date(milestone.getNotifiedWindowStart()),
                        href, milestone.getNotifiedWindowStart(), card.getId()));
            }
            for (RewardCapBucket bucket : bucketRepository.findByAccountIdOrderByCreatedAtAsc(card.getId())) {
                if (recent(bucket.getCapNotifiedOn(), bucket.getCapNotifiedWindowStart(), since)) {
                    rows.add(cap(bucket.getId(), bucket.getName(), cardLabel, href, bucket.getCapNotifiedWindowStart(), card.getId()));
                }
            }
            for (RewardRule rule : ruleRepository.findByAccountIdOrderByPriorityDesc(card.getId())) {
                if (recent(rule.getCapNotifiedOn(), rule.getCapNotifiedWindowStart(), since)) {
                    rows.add(cap(rule.getId(), rule.getName(), cardLabel, href, rule.getCapNotifiedWindowStart(), card.getId()));
                }
            }
        }
        return rows;
    }

    /**
     * Recent when the marker was recorded within the last {@value #RECENT_DAYS} days. Markers
     * recorded before {@code notified_on} existed have no date, so the window start stands in.
     */
    private static boolean recent(LocalDate notifiedOn, LocalDate windowStart, LocalDate since) {
        if (windowStart == null) {
            return false;
        }
        LocalDate when = notifiedOn != null ? notifiedOn : windowStart;
        return !when.isBefore(since);
    }

    private static InboxItemResponse cap(UUID ownerId, String name, String cardLabel, String href, LocalDate windowStart, UUID accountId) {
        return row(InboxKinds.rewardCapKey(ownerId, windowStart), InboxKinds.REWARD_CAP, cardLabel + ": " + name + " cap reached",
                "More spend here earns nothing for the rest of the window · from " + InboxRows.date(windowStart), href, windowStart, accountId);
    }

    private static InboxItemResponse row(String key, String kind, String title, String subtitle, String href, LocalDate date, UUID accountId) {
        return InboxRows.item(key, kind, SEVERITY_INFO, SECTION_INFO, title, subtitle, href, null, date,
                List.of(InboxActionResponse.navigate("open", "Open", href), InboxRows.dismiss()), InboxRefsResponse.ofAccount(accountId));
    }
}
