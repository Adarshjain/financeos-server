package com.financeos.domain.notification.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.notification.push.PushMessage;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The attention digest counts what the app shows: unresolved mail naming the same number at the same bank is one entry. */
class GmailMessagesGroupedAttentionTest {

    private static GmailProcessedMessage unresolved(String last4, String sender) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setSenderAddress(sender);
        return m;
    }

    @Test
    void sameNumberAtTheSameBank_countsOnce() {
        PushMessage message = GmailMessages.attention(List.of(
                unresolved("3003", "alerts@hdfcbank.net"), unresolved("3003", "alerts@hdfcbank.net"), unresolved("3003", "estatement@hdfcbank.net")));

        assertThat(message.title()).isEqualTo("1 email needs attention");
        assertThat(message.body()).isEqualTo("1 couldn't be matched to an account");
    }

    @Test
    void otherBankOrNumber_andMailWithNoNumber_countSeparately() {
        PushMessage message = GmailMessages.attention(List.of(
                unresolved("3003", "alerts@hdfcbank.net"), unresolved("3003", "alerts@icicibank.com"), unresolved("4004", "alerts@hdfcbank.net"),
                unresolved(null, "alerts@hdfcbank.net"), unresolved(null, "alerts@hdfcbank.net")));

        assertThat(message.title()).isEqualTo("5 emails need attention");
    }
}
