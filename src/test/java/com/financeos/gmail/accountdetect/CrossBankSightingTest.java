package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A number that is a solid own number at one bank is not also suggested at another bank from weak sightings. Made-up data. */
class CrossBankSightingTest {

    private static final String REASON = "own number at another bank, seen in bank X's mails";

    /** Own account at equitas: three alert layouts with balances across 12 months. */
    private static SyntheticMailbox equitasOwn(long seed, String l4) {
        SyntheticMailbox mb = new SyntheticMailbox(seed);
        for (int m = 0; m < 12; m++) {
            mb.mail("equitasbank.com", mb.day(m), "Credit alert", "Your A/c XX" + l4 + " is credited with Rs. 4,500.00 by UPI. Balance is Rs. 12,000.00.", true);
            mb.mail("equitasbank.com", mb.day(m), "Debit alert", "Rs. 800.00 has been debited from your A/c XX" + l4 + " via UPI. Available balance Rs. 11,200.00.", true);
            mb.mail("equitasbank.com", mb.day(m), "Balance update", "Your account XX" + l4 + " balance is Rs. 15,000.00 as of today.", true);
        }
        return mb;
    }

    private static void hdfcSightings(SyntheticMailbox mb, String l4) {
        for (int m = 0; m < 3; m++) {
            mb.mail("hdfcbank.net", mb.day(m), "View: Account update for your HDFC Bank A/c",
                    "Update: Rs. 5,000.00 moved via IMPS to A/c XX" + l4 + " on 03-07-25.", true);
        }
    }

    @Test
    void aWeakSightingAtAnotherBankIsDroppedWhenTheNumberIsSolidElsewhere() {
        SyntheticMailbox mb = equitasOwn(11, "2183");
        hdfcSightings(mb, "2183");
        Detection d = mb.detectRaw();
        Entity eq = d.find("equitas", "2183");
        assertThat(eq).isNotNull();
        assertThat(eq.existsConf).isEqualTo(Conf.HIGH);
        assertThat(d.find("hdfc", "2183")).isNull();
        assertThat(d.dropped).containsKey(REASON);
    }

    @Test
    void aSameNumberAtAnotherBankWithStatementEvidenceSurvives() {
        SyntheticMailbox mb = equitasOwn(12, "2183");
        hdfcSightings(mb, "2183");
        for (int m = 1; m <= 3; m++) {
            YearMonth ym = YearMonth.of(2025, m);
            mb.statements.add(new StatementContent("PDF_FOLDER", "hdfc", "2183", Kind.CREDIT_CARD, ym, null, null, List.of(), false, ym.atDay(20)));
        }
        Detection d = mb.detectRaw();
        assertThat(d.find("equitas", "2183")).isNotNull();
        assertThat(d.find("hdfc", "2183")).isNotNull();
    }

    @Test
    void aWeakSightingWithNoSolidNumberElsewhereStays() {
        SyntheticMailbox mb = new SyntheticMailbox(13);
        hdfcSightings(mb, "2183");
        Detection d = mb.detectRaw();
        assertThat(d.dropped).doesNotContainKey(REASON);
    }
}
