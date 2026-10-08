package com.financeos.domain.notification.statement;

import com.financeos.domain.account.Account;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Push text for the "N transactions didn't reconcile" digest; card statements carry the bill's total due. */
public final class StatementReviewMessages {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    private StatementReviewMessages() {
    }

    public static PushMessage digest(Statement statement, long dangling) {
        Account account = statement.getAccount();
        String label = accountLabel(account);
        String month = statement.getPeriodEnd() != null ? MONTH.format(statement.getPeriodEnd()) + " " : "";
        String title = label + " " + month + "statement: " + dangling + (dangling == 1 ? " transaction didn't reconcile" : " transactions didn't reconcile");
        StringBuilder body = new StringBuilder();
        StatementCreditCardDetails details = "credit_card".equals(statement.getStatementType()) ? statement.getCreditCardDetails() : null;
        if (details != null && details.getTotalAmountDue() != null) {
            body.append(MessageFormat.money(details.getTotalAmountDue()));
            if (details.getPaymentDueDate() != null) {
                body.append(" due ").append(MessageFormat.date(details.getPaymentDueDate()));
            }
            body.append(" · ");
        } else if (statement.getPeriodStart() != null && statement.getPeriodEnd() != null) {
            body.append("Period ").append(MessageFormat.date(statement.getPeriodStart()))
                    .append(" – ").append(MessageFormat.date(statement.getPeriodEnd())).append(" · ");
        }
        body.append("Review and approve or merge the leftovers.");
        String url = "/transactions/review?account=" + account.getId()
                + (statement.getPeriodStart() != null ? "&from=" + statement.getPeriodStart() : "")
                + (statement.getPeriodEnd() != null ? "&to=" + statement.getPeriodEnd() : "");
        return new PushMessage(title, body.toString(), url, "statement-review-" + statement.getId());
    }

    static String accountLabel(Account account) {
        String name = account.getName() == null ? "Account" : account.getName();
        String last4 = account.primaryLast4();
        return last4 == null || last4.isBlank() ? name : name + " ••" + last4;
    }
}
