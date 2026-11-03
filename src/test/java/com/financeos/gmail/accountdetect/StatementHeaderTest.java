package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import java.util.List;
import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static org.assertj.core.api.Assertions.assertThat;

/** A number the bank's own statement header names as the holder's is never suppressed by mail-slot classification. */
class StatementHeaderTest {

    /** An own account plus a monthly bill payment from it whose payee is the card: the card sits in a counterparty slot. */
    private static SyntheticMailbox payeeCard(long seed, String card) {
        SyntheticMailbox mb = new SyntheticMailbox(seed);
        BankSpec hdfc = mb.bank("hdfc", 0, false, false, false);
        mb.accountMails(hdfc, "3301", null, false);
        for (int m = 0; m < MONTHS; m++) {
            var d = mb.day(m);
            mb.alert(hdfc.domain(), d, "Funds transfer alert", "INR 12000.00 debited from your A/c XX3301 and credited to A/c XX" + card
                    + " of beneficiary SAMPLE PAYEE via NEFT. UTR 4410" + (100000000000L + m) + ".");
        }
        return mb;
    }

    private static void folderStatement(SyntheticMailbox mb, String l4, YearMonth ym, String variant, String limit) {
        mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", l4, Kind.CREDIT_CARD, ym, variant, limit, List.of(), false, ym.atDay(20)));
    }

    @Test
    void counterpartyOnly_withoutAStatement_isStillDropped() {
        Detection d = payeeCard(401, "5521").detect(401);
        assertThat(d.find("hdfc", "5521")).isNull();
        assertThat(d.dropped).containsKey("number in counterparty slot only");
    }

    @Test
    void aPayeeCardWithTwoHeaderStatements_isMediumNeverHigh() {
        SyntheticMailbox mb = payeeCard(402, "5522");
        folderStatement(mb, "5522", SyntheticMailbox.START.plusMonths(2), "Regalia", "5-10L");
        folderStatement(mb, "5522", SyntheticMailbox.START.plusMonths(8), "Regalia", "5-10L");
        Detection d = mb.detect(402);
        Entity e = d.find("hdfc", "5522");
        assertThat(e).isNotNull();
        assertThat(e.counterpartyHits).isPositive();   // the payee sightings remain a contradiction
        assertThat(e.existsConf).isEqualTo(Conf.MEDIUM);
        assertThat(e.evidenceMails).isEqualTo(2);       // counterparty mails add no evidence
        // the statement's variant follows the normal rules: Regalia nests with Regalia Gold
        assertThat(e.variant).isEqualTo("Regalia");
        assertThat(e.variantConf).isEqualTo(Conf.LOW);
        assertThat(e.limitBucket).isEqualTo("5-10L");
    }

    @Test
    void aPayeeCardWithOneHeaderStatement_isLow() {
        SyntheticMailbox mb = payeeCard(403, "5523");
        folderStatement(mb, "5523", SyntheticMailbox.START.plusMonths(4), null, null);
        Entity e = mb.detect(403).find("hdfc", "5523");
        assertThat(e).isNotNull();
        assertThat(e.existsConf).isEqualTo(Conf.LOW);
    }

    @Test
    void twoHeaderStatementsUnder30DaysApart_areLow() {
        SyntheticMailbox mb = new SyntheticMailbox(404);
        var ym = SyntheticMailbox.START.plusMonths(3);
        mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", "5524", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(5)));
        mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", "5524", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(25)));
        Entity e = mb.detect(404).find("hdfc", "5524");
        assertThat(e).isNotNull();
        assertThat(e.existsConf).isEqualTo(Conf.LOW);
    }

    @Test
    void aCardAlsoNamedInALoanOfferMail_keepsItsStatementBackedExistence() {
        SyntheticMailbox mb = new SyntheticMailbox(405);
        BankSpec hdfc = mb.bank("hdfc", 0, false, false, false);
        mb.mail(hdfc.domain(), mb.day(3), "Pre-approved offer", "Get an instant loan on your Credit Card XX5525 at a special rate.", true);
        folderStatement(mb, "5525", SyntheticMailbox.START.plusMonths(2), null, null);
        folderStatement(mb, "5525", SyntheticMailbox.START.plusMonths(6), null, null);
        Entity e = mb.detect(405).find("hdfc", "5525");
        assertThat(e).isNotNull();
        assertThat(e.existsConf).isEqualTo(Conf.MEDIUM);
    }
}
