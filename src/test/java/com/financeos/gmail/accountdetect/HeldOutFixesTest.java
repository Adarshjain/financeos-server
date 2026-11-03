package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Generic fixes driven by the held-out dev corpus and the real-mailbox recall check. */
class HeldOutFixesTest {

    private static List<String> own(String seg) {
        return FeatureExtractor.numbersIn(seg).stream().filter(n -> !n.label().rejected()).map(FeatureExtractor.Num::last4).toList();
    }

    @Test
    void aBareNumberRightAfterEndingCountsWhenACardWordIsClose() {
        assertThat(own("A replacement card with a new number ending 7312 is being dispatched to you."))
                .containsExactly("7312");
        // no card or account word nearby: still not a number we read
        assertThat(own("Your order with a new number ending 7312 is on its way.")).isEmpty();
    }

    private static Entity alertsOnly(String bank, int mails, int counterparty) {
        Entity e = new Entity(bank, "8452");
        e.bankChannels.add(Channel.ALERTS);
        e.evidenceMails = mails;
        e.evidenceSpanDays = 400;
        e.counterpartyHits = counterparty;
        return e;
    }

    @Test
    void aFewCounterpartySightingsAgainstManyOwnMailsAllowMediumNeverHigh() {
        assertThat(Contract.existsMax(alertsOnly("axis", 160, 2))).isEqualTo(Conf.MEDIUM);
        assertThat(Contract.existsMax(alertsOnly("axis", 40, 2))).isEqualTo(Conf.MEDIUM);
        assertThat(Contract.existsMax(alertsOnly("axis", 40, 3))).isEqualTo(Conf.LOW);
        assertThat(Contract.existsMax(alertsOnly("axis", 10, 0))).isEqualTo(Conf.MEDIUM);
        assertThat(Contract.existsMax(alertsOnly("axis", 19, 1))).isEqualTo(Conf.LOW);
        Entity two = alertsOnly("axis", 160, 2);
        two.bankChannels.add(Channel.SERVICE);
        assertThat(Contract.existsMax(two)).isEqualTo(Conf.MEDIUM);
    }

    @Test
    void aSenderOutsideTheIssuerDirectoryIsLow() {
        Entity e = alertsOnly("?unknown-issuer.example", 160, 0);
        e.bankChannels.add(Channel.BILLING);
        assertThat(Contract.existsMax(e)).isEqualTo(Conf.LOW);
        assertThat(BankDirectory.bankForDomain("scapia.cc")).isEqualTo("federal");
    }
}
