package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Card;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static org.assertj.core.api.Assertions.assertThat;

/** A loan label drops a number only from a mail that gives facts; offers and other no-fact mails never veto. */
class LoanVetoTest {

    private static final int LAST = MONTHS - 1;

    /** The role of the layout carrying each mail that names the number with a LOAN label. */
    private static List<Role> loanRoles(SyntheticMailbox mb, String bank, String l4) {
        List<MailDerived> derived = mb.mails.stream().map(FeatureExtractor::derive).toList();
        var roles = Detector.clusterRoles(derived);
        return derived.stream().filter(m -> bank.equals(m.bank()) && m.slots().stream().anyMatch(s -> l4.equals(s.last4()) && s.label() == LabelKind.LOAN))
                .map(m -> roles.get(m.clusterId())).distinct().toList();
    }

    /** An own card with a year of alerts; with the offer, one loan-on-card offer mail (role OTHER) names it too. */
    private static SyntheticMailbox ownCard(boolean offer) {
        SyntheticMailbox mb = new SyntheticMailbox(501);
        BankSpec hdfc = mb.bank("hdfc", 0, true, false, false);
        mb.cardAlerts(new Card(hdfc, "6621", Kind.CREDIT_CARD, null, 0, LAST), 0, LAST, false);
        var d = mb.day(4);
        if (offer) mb.mail(hdfc.domain(), d, "A special offer for you", "Your pre-approved Insta Loan on Card XX6621 is ready at a special rate. Apply now.", true);
        return mb;
    }

    @Test
    void aLoanOnCardOfferNamingAnOwnCard_doesNotDropTheCard() {
        SyntheticMailbox mb = ownCard(true);
        assertThat(loanRoles(mb, "hdfc", "6621")).isNotEmpty().allMatch(r -> !r.factual());
        Entity e = mb.detect(501).find("hdfc", "6621");
        Entity control = ownCard(false).detect(501).find("hdfc", "6621");
        assertThat(control).isNotNull();
        assertThat(e).isNotNull();
        assertThat(e.existsConf).isEqualTo(control.existsConf);
        assertThat(e.kind).isEqualTo(Kind.CREDIT_CARD);
    }

    @Test
    void anEmiDueReminderLoan_isStillDropped() {
        SyntheticMailbox mb = new SyntheticMailbox(502);
        BankSpec hdfc = mb.bank("hdfc", 0, true, false, false);
        mb.cardAlerts(new Card(hdfc, "6622", Kind.CREDIT_CARD, null, 0, LAST), 0, LAST, false);
        for (int m = 0; m < MONTHS; m++)
            mb.mail(hdfc.domain(), mb.day(m), "EMI due reminder", "Dear Customer, the EMI of INR 12,500.00 for your Loan account no XX5679 is due on 05.", true);
        assertThat(loanRoles(mb, "hdfc", "5679")).contains(Role.DUE_REMINDER);
        Detection d = mb.detect(502);
        assertThat(d.find("hdfc", "5679")).isNull();
        assertThat(d.dropped).containsKey("number named as a loan (loans are out of scope)");
        assertThat(d.find("hdfc", "6622")).isNotNull();
    }
}
