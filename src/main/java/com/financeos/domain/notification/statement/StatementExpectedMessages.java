package com.financeos.domain.notification.statement;

import com.financeos.domain.account.Account;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.notification.push.PushMessage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Push text for a card statement that should have arrived by now. */
public final class StatementExpectedMessages {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    private StatementExpectedMessages() {
    }

    public static PushMessage missing(Account card, LocalDate expectedPeriodEnd) {
        String title = StatementReviewMessages.accountLabel(card) + ": " + MONTH.format(expectedPeriodEnd) + " statement hasn't arrived";
        boolean gmail = card.getIngestFromDate() != null;
        String body = "Expected around " + MessageFormat.date(expectedPeriodEnd) + ". "
                + (gmail ? "Check the mailbox connection, or upload it." : "Upload it to keep bills and rewards current.");
        return new PushMessage(title, body, gmail ? "/settings/gmail" : "/settings/ingest", "statement-expected-" + card.getId());
    }
}
