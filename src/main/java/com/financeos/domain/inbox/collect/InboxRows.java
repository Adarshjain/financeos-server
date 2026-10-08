package com.financeos.domain.inbox.collect;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.notification.MessageFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/** Row and action builders shared by the collectors, so every kind phrases things the same way. */
public final class InboxRows {

    private InboxRows() {
    }

    public static InboxItemResponse item(String key, String kind, String severity, String section, String title,
                                         @Nullable String subtitle, @Nullable String href, @Nullable BigDecimal amount,
                                         @Nullable LocalDate date, List<InboxActionResponse> actions, InboxRefsResponse refs) {
        return new InboxItemResponse(key, kind, InboxItemResponse.ROW_ITEM, severity, section, title, subtitle, href,
                amount, date, null, actions, null, refs);
    }

    public static InboxItemResponse summary(String key, String kind, String severity, String section, String title,
                                            @Nullable String subtitle, String href, int count,
                                            List<InboxActionResponse> actions) {
        return new InboxItemResponse(key, kind, InboxItemResponse.ROW_SUMMARY, severity, section, title, subtitle, href,
                null, null, count, actions, null, InboxRefsResponse.NONE);
    }

    // ---------------------------------------------------------------- actions

    public static InboxActionResponse open(String href) {
        return InboxActionResponse.navigate("open", "Open", href);
    }

    public static InboxActionResponse snooze() {
        return InboxActionResponse.mutate("snooze", "Snooze", null);
    }

    public static InboxActionResponse dismiss() {
        return InboxActionResponse.mutate("dismiss", "Dismiss", null);
    }

    // ---------------------------------------------------------------- phrasing

    /** "<name> ••<last4>" or just the name. */
    public static String cardLabel(@Nullable String name, @Nullable String last4) {
        String n = name == null ? "Card" : name;
        return last4 == null || last4.isBlank() ? n : n + " ••" + last4;
    }

    /** "Due today" / "Due tomorrow" / "Due in N days" / "Overdue by N days". */
    public static String dueText(long daysUntil) {
        if (daysUntil < 0) {
            return "Overdue by " + MessageFormat.days(-daysUntil);
        }
        String text = MessageFormat.inDays("due", daysUntil);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static String money(@Nullable BigDecimal amount) {
        return MessageFormat.money(amount);
    }

    public static String date(@Nullable LocalDate date) {
        return MessageFormat.date(date);
    }
}
