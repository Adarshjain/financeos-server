package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Detection.StatementLink;
import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Card;
import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static com.financeos.gmail.accountdetect.SyntheticMailbox.START;
import static org.assertj.core.api.Assertions.assertThat;

/** Statement documents are tied to an entity by their content (bank + last4 + period), never by file name. */
class StatementLinkTest {

    private static final int LAST = MONTHS - 1;

    /** A folder PDF with no number in it: bank, kind, period end (the 10th) and a product name only. */
    private static StatementContent noNumber(String bank, YearMonth ym, String variant) {
        return new StatementContent("PDF_FOLDER", bank, null, Kind.CREDIT_CARD, ym, variant, "3-5L", List.of(), false, ym.atDay(10));
    }

    private static List<StatementLink> links(Detection d, String outcome) {
        return d.statementLinks.stream().filter(l -> l.outcome().equals(outcome)).toList();
    }

    @Test
    void aNumberInTheHeaderLinksByLast4() {
        SyntheticMailbox mb = new SyntheticMailbox(31);
        BankSpec hdfc = mb.bank("hdfc", 2, false, false, false);
        Card c = new Card(hdfc, "4371", Kind.CREDIT_CARD, "Regalia Gold", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", "4371", Kind.CREDIT_CARD, START.plusMonths(3), "Regalia Gold", "3-5L",
                List.of(), false, START.plusMonths(3).atDay(10)));

        Detection d = mb.detectRaw();
        assertThat(links(d, Detector.LINK_LAST4)).extracting(StatementLink::last4).containsExactly("4371");
        assertThat(d.find("hdfc", "4371").channels).contains(Channel.STATEMENT_CONTENT);
    }

    @Test
    void noNumberLinksThroughTheSingleBillingMailAtPeriodEnd() {
        SyntheticMailbox mb = new SyntheticMailbox(32);
        BankSpec hdfc = mb.bank("hdfc", 2, false, false, false);
        Card c = new Card(hdfc, "4371", Kind.CREDIT_CARD, "Regalia Gold", 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());   // billing mails on the 12th: period end + 2 days
        for (int m = 2; m < 6; m++) mb.statements.add(noNumber("hdfc", START.plusMonths(m), "Regalia Gold"));

        Detection d = mb.detectRaw();
        List<StatementLink> byPeriod = links(d, Detector.LINK_PERIOD);
        assertThat(byPeriod).isNotEmpty().allSatisfy(l -> assertThat(l.last4()).isEqualTo("4371"));
        assertThat(byPeriod.size() + links(d, Detector.LINK_NONE).size()).isEqualTo(4); // a skipped billing month stays unlinked
        Entity e = d.find("hdfc", "4371");
        assertThat(e.variant).isEqualTo("Regalia Gold");
        assertThat(e.limitBucket).isEqualTo("3-5L");
        // tied through a mail, so the document is not a second independent witness of existence
        assertThat(e.channels).doesNotContain(Channel.STATEMENT_CONTENT);
    }

    @Test
    void twoCardsBilledInTheSameWindowLeaveTheDocumentUnlinked() {
        SyntheticMailbox mb = new SyntheticMailbox(33);
        BankSpec hdfc = mb.bank("hdfc", 2, false, false, false);
        Card a = new Card(hdfc, "4371", Kind.CREDIT_CARD, null, 0, LAST);
        Card b = new Card(hdfc, "8642", Kind.CREDIT_CARD, null, 0, LAST);
        for (Card c : List.of(a, b)) {
            mb.cardAlerts(c, 0, LAST, false);
            mb.cardBilling(c, 0, LAST, List.of());
        }
        for (int m = 2; m < 6; m++) mb.statements.add(noNumber("hdfc", START.plusMonths(m), "Regalia Gold"));

        Detection d = mb.detectRaw();
        assertThat(links(d, Detector.LINK_PERIOD)).isEmpty();
        assertThat(links(d, Detector.LINK_NONE)).hasSize(4);
        assertThat(d.find("hdfc", "4371").variant).isNull();
        assertThat(d.find("hdfc", "8642").variant).isNull();
    }

    @Test
    void noPeriodEndOrNoBillingMailMeansUnlinked_andNoMarkersMeansNotAStatement() {
        SyntheticMailbox mb = new SyntheticMailbox(34);
        BankSpec hdfc = mb.bank("hdfc", 2, false, false, false);
        Card c = new Card(hdfc, "4371", Kind.CREDIT_CARD, null, 0, LAST);
        mb.cardAlerts(c, 0, LAST, false);
        mb.cardBilling(c, 0, LAST, List.of());
        YearMonth ym = START.plusMonths(3);
        mb.statements.add(new StatementContent("PDF_MAIL", "hdfc", null, Kind.CREDIT_CARD, ym, null, null, List.of(), false, null));
        mb.statements.add(new StatementContent("PDF_FOLDER", "axis", null, Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(10)));
        mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", "9911", null, ym, null, null, List.of(), false, ym.atDay(10)));

        Detection d = mb.detectRaw();
        assertThat(links(d, Detector.LINK_NONE)).hasSize(2);
        assertThat(links(d, Detector.LINK_NOT_STATEMENT)).hasSize(1);
        assertThat(d.find("hdfc", "9911")).isNull();
    }
}
