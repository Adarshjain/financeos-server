package com.financeos.domain.notification.reward;

import com.financeos.api.reward.dto.RewardReportResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.MilestoneBasis;
import com.financeos.domain.reward.MilestonePayoutType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Push texts for the rewards alerts: a milestone within reach as its window closes, one achieved, a cap exhausted. */
public final class RewardMessages {

    private RewardMessages() {
    }

    public static PushMessage milestoneClosing(Account card, RewardReportResponse.MilestoneStatus status) {
        BigDecimal remaining = status.threshold().subtract(status.progress()).max(BigDecimal.ZERO);
        String gap = status.basis() == MilestoneBasis.TXN_COUNT
                ? remaining.setScale(0, RoundingMode.CEILING).toPlainString() + " more " + (remaining.compareTo(BigDecimal.ONE) == 0 ? "transaction" : "transactions")
                : MessageFormat.money(remaining) + " more";
        String title = status.name() + ": " + gap + " to go";
        String body = (status.basis() == MilestoneBasis.TXN_COUNT ? "Transact" : "Spend") + " on " + card(card) + " by "
                + MessageFormat.date(status.windowEnd()) + " to unlock " + payout(status) + ".";
        return message(card, status.milestoneId(), title, body);
    }

    public static PushMessage milestoneAchieved(Account card, RewardReportResponse.MilestoneStatus status) {
        String body = card(card) + " crossed " + threshold(status) + " this window. "
                + (status.payoutType() == MilestonePayoutType.INFO_TRACKER ? "Milestone reached."
                : payout(status) + (status.payoutDate() != null ? " credited around " + MessageFormat.date(status.payoutDate()) : " on its way") + ".");
        return message(card, status.milestoneId(), status.name() + " unlocked", body);
    }

    public static PushMessage capReached(Account card, RewardCalculationService.CapUsage cap) {
        String name = cap.bucketName() != null ? cap.bucketName() : cap.ruleName();
        String title = card(card) + ": " + name + " cap reached";
        String body = amount(cap.used(), cap.unit()) + " of " + amount(cap.cap(), cap.unit()) + " used " + windowLabel(cap.window())
                + ". More spend here earns nothing until " + MessageFormat.date(cap.windowEnd().plusDays(1)) + " · try another card.";
        UUID owner = cap.bucketId() != null ? cap.bucketId() : cap.ruleId();
        return new PushMessage(title, body, "/rewards?account=" + card.getId(), "cap-" + owner);
    }

    // ---------------------------------------------------------------- formatting

    static String card(Account account) {
        String name = account.getName() == null ? "Card" : account.getName();
        String last4 = account.primaryLast4();
        return last4 == null || last4.isBlank() ? name : name + " ••" + last4;
    }

    static String payout(RewardReportResponse.MilestoneStatus status) {
        if (status.payoutType() == MilestonePayoutType.INFO_TRACKER || status.payoutValue() == null) {
            return "the milestone";
        }
        return status.rewardType() == RewardType.POINTS
                ? MessageFormat.points(status.payoutValue()) + " pts"
                : MessageFormat.money(status.payoutValue());
    }

    static String threshold(RewardReportResponse.MilestoneStatus status) {
        return status.basis() == MilestoneBasis.TXN_COUNT
                ? status.threshold().setScale(0, RoundingMode.HALF_UP).toPlainString() + " transactions"
                : MessageFormat.money(status.threshold());
    }

    static String amount(BigDecimal value, String unit) {
        return "POINTS".equals(unit) ? MessageFormat.points(value) + " pts" : MessageFormat.money(value);
    }

    static String windowLabel(CapWindow window) {
        if (window == null) {
            return "this period";
        }
        return switch (window) {
            case DAY -> "today";
            case CALENDAR_MONTH -> "this month";
            case STATEMENT_CYCLE -> "this statement cycle";
            case QUARTER -> "this quarter";
            case CALENDAR_YEAR -> "this year";
            case ANNIVERSARY_YEAR -> "this card year";
        };
    }

    private static PushMessage message(Account card, UUID milestoneId, String title, String body) {
        return new PushMessage(title, body, "/rewards?account=" + card.getId(), "milestone-" + milestoneId);
    }
}
