package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.notification.gmail.GmailAttentionNotificationService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Gmail: one row per mailbox whose token Google rejected (reconnect), and one summary row for
 * every processed email still waiting on the user (unmatched account, not opted in, failed).
 */
@Component
public class GmailInboxCollector implements InboxCollector {

    static final String SETTINGS_HREF = "/settings/gmail";
    static final String ATTENTION_HREF = "/settings/gmail?focus=attention";

    private final GmailConnectionRepository connectionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;

    public GmailInboxCollector(GmailConnectionRepository connectionRepository,
                               GmailProcessedMessageRepository processedMessageRepository) {
        this.connectionRepository = connectionRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        for (GmailConnection connection : connectionRepository.findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(userId)) {
            rows.add(InboxRows.item(InboxKinds.gmailReconnectKey(connection.getId()), InboxKinds.GMAIL_RECONNECT,
                    SEVERITY_WARNING, SECTION_ACT_NOW, "Reconnect Gmail",
                    connection.getEmail() + " stopped syncing · Google rejected its token", SETTINGS_HREF, null,
                    connection.getAuthFailedAt() == null ? null : LocalDate.ofInstant(connection.getAuthFailedAt(), com.financeos.core.time.AppTime.zone()),
                    List.of(InboxActionResponse.navigate("reconnect", "Reconnect", SETTINGS_HREF)),
                    InboxRefsResponse.ofConnection(connection.getId())));
        }
        long attention = processedMessageRepository.countByUserIdAndStatusIn(userId, GmailAttentionNotificationService.ATTENTION_STATUSES);
        if (attention > 0) {
            int n = (int) Math.min(attention, Integer.MAX_VALUE);
            rows.add(InboxRows.summary(InboxKinds.KEY_GMAIL_ATTENTION, InboxKinds.GMAIL_ATTENTION, SEVERITY_WARNING,
                    SECTION_NEEDS_LOOK, n == 1 ? "1 email needs attention" : n + " emails need attention",
                    "Unmatched account, not opted in, or failed to import", ATTENTION_HREF, n,
                    List.of(InboxRows.open(ATTENTION_HREF))));
        }
        return rows;
    }
}
