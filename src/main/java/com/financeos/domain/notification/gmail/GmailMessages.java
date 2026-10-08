package com.financeos.domain.notification.gmail;

import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;
import java.util.ArrayList;
import java.util.List;

/** Push texts for the Gmail producers. Pure. */
public final class GmailMessages {

    /** The error text the ingestion service writes when no LLM key is configured. */
    static final String NO_KEYS_PREFIX = "needs attention: add an API key";

    private GmailMessages() {
    }

    public static PushMessage reconnect(GmailConnection connection) {
        return new PushMessage("Gmail disconnected",
                "Reconnect " + connection.getEmail() + " to keep importing alerts and statements.",
                InboxKinds.inboxHref(InboxKinds.gmailReconnectKey(connection.getId())), "gmail-reconnect-" + connection.getId());
    }

    /** One digest for every item the user has not been told about yet. */
    public static PushMessage attention(List<GmailProcessedMessage> items) {
        int n = items.size();
        if (n > 0 && items.stream().allMatch(GmailMessages::isNoKeys)) {
            return new PushMessage("Gmail imports are paused",
                    "Add an LLM API key in Settings to process " + n + (n == 1 ? " waiting email." : " waiting emails."),
                    "/settings/llm-keys", "gmail-attention");
        }
        int unresolved = 0;
        int notOptedIn = 0;
        int failed = 0;
        for (GmailProcessedMessage item : items) {
            if (item.getStatus() == GmailProcessedStatus.UNRESOLVED_ACCOUNT) {
                unresolved++;
            } else if (item.getStatus() == GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN) {
                notOptedIn++;
            } else {
                failed++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (unresolved > 0) {
            parts.add(unresolved + " couldn't be matched to an account");
        }
        if (notOptedIn > 0) {
            parts.add(notOptedIn + " from " + (notOptedIn == 1 ? "an account" : "accounts") + " not opted in");
        }
        if (failed > 0) {
            parts.add(failed + " failed to import");
        }
        String title = n == 1 ? "1 email needs attention" : n + " emails need attention";
        return new PushMessage(title, String.join(" · ", parts), InboxKinds.inboxHref(InboxKinds.KEY_GMAIL_ATTENTION), "gmail-attention");
    }

    static boolean isNoKeys(GmailProcessedMessage item) {
        return item.getError() != null && item.getError().startsWith(NO_KEYS_PREFIX);
    }
}
