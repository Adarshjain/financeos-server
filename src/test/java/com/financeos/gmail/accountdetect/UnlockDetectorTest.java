package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Card;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The exact limit and the "not in your name" flag as the detector derives them from statements. Made-up mailbox. */
class UnlockDetectorTest {

    private static final YearMonth START = YearMonth.of(2025, 1);
    private static final int LAST = 11;

    private static StatementContent stmt(int month, String limitBucket, String amount, String holder) {
        YearMonth ym = START.plusMonths(month);
        return new StatementContent("PDF_MAIL", "hdfc", "4371", Kind.CREDIT_CARD, ym, "Regalia Gold", limitBucket, List.of(), false, ym.atDay(10),
                null, null, null, amount == null ? null : new BigDecimal(amount), null, holder);
    }

    private static StatementContent stmtFrom(String source, int month, String holder) {
        YearMonth ym = START.plusMonths(month);
        return new StatementContent(source, "hdfc", "4371", Kind.CREDIT_CARD, ym, "Regalia Gold", "3-5L", List.of(), false, ym.atDay(10),
                null, null, null, null, null, holder);
    }

    private static StatementContent noNumber(int month, String holder) {
        YearMonth ym = START.plusMonths(month);
        return new StatementContent("PDF_MAIL", "hdfc", null, Kind.CREDIT_CARD, ym, "Regalia Gold", "3-5L", List.of(), false, ym.atDay(10),
                null, null, null, null, null, holder);
    }

    private Detection.Entity detect(StatementContent... statements) {
        SyntheticMailbox mb = new SyntheticMailbox(71);
        BankSpec hdfc = mb.bank("hdfc", 2, false, false, false);
        Card c = new Card(hdfc, "4371", Kind.CREDIT_CARD, "Regalia Gold", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        mb.statements.addAll(List.of(statements));
        return mb.detectRaw().find("hdfc", "4371");
    }

    // ---- exact limit

    @Test
    void theExactLimitIsTheLatestStatementsWhenItAgreesWithItsBucket() {
        Detection.Entity e = detect(stmt(2, "3-5L", "450000", null), stmt(4, "3-5L", "450000", null), stmt(6, "3-5L", "475000.50", null));

        assertThat(e.limitConf).isNotNull();
        assertThat(e.limitConf.atLeast(Conf.MEDIUM)).isTrue();
        assertThat(e.limitAmount).isEqualByComparingTo("475000.50");
    }

    @Test
    void anOlderAmountIsNeverUsedWhenTheLatestStatementPrintedNone() {
        // the newest statement gives the bucket but read no amount: the older amount must not stand in for it
        Detection.Entity e = detect(stmt(2, "3-5L", "450000", null), stmt(4, "3-5L", "450000", null), stmt(6, "3-5L", null, null));

        assertThat(e.limitBucket).isEqualTo("3-5L");
        assertThat(e.limitConf.atLeast(Conf.MEDIUM)).isTrue();
        assertThat(e.limitAmount).isNull();
    }

    @Test
    void anAmountThatDisagreesWithItsBucketIsDropped() {
        Detection.Entity e = detect(stmt(2, "3-5L", "450000", null), stmt(4, "3-5L", "450000", null), stmt(6, "3-5L", "150000", null));
        assertThat(e.limitAmount).isNull();
    }

    // ---- not in your name

    @Test
    void twoLinkedStatementsAllNotTheUsersMakeItNotInYourName() {
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(4, "3-5L", null, "N")).notInYourName).isTrue();
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(3, "3-5L", null, "N"), stmt(4, "3-5L", null, "N")).notInYourName).isTrue();
    }

    @Test
    void oneStatementIsNotEnoughAndAnyYVetoes() {
        assertThat(detect(stmt(2, "3-5L", null, "N")).notInYourName).isFalse();
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(4, "3-5L", null, "N"), stmt(5, "3-5L", null, "Y")).notInYourName).isFalse();
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(4, "3-5L", null, "Y")).notInYourName).isFalse();
    }

    @Test
    void unknownHoldersAreNotCounted() {
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(4, "3-5L", null, null), stmt(5, "3-5L", null, null)).notInYourName).isFalse();
        assertThat(detect(stmt(2, "3-5L", null, null), stmt(4, "3-5L", null, null)).notInYourName).isFalse();
    }

    @Test
    void statementsLinkedOnlyByPeriodNeverCount() {
        Detection.Entity e = detect(noNumber(2, "N"), noNumber(3, "N"), noNumber(4, "N"), noNumber(5, "N"));
        assertThat(e.notInYourName).isFalse();
    }

    @Test
    void severalMailsOfOnePeriodCountOnce() {
        // three mails (and PDFs) of one month are one statement: not enough for "not in your name"
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(2, "3-5L", null, "N"), stmt(2, "3-5L", null, "N")).notInYourName).isFalse();
        // two distinct months are
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(2, "3-5L", null, "N"), stmt(3, "3-5L", null, "N")).notInYourName).isTrue();
    }

    @Test
    void aStatementTheUserUploadedVetoesIt() {
        // the app-parsed (DB) statement of the same number is the user's own, whatever the mails say
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(3, "3-5L", null, "N"), stmtFrom("DB", 4, null)).notInYourName).isFalse();
        assertThat(detect(stmt(2, "3-5L", null, "N"), stmt(3, "3-5L", null, "N"), stmtFrom("PDF_FOLDER", 4, null)).notInYourName).isTrue();
    }
}
