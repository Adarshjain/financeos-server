package com.financeos.gmail.accountdetect;

import com.financeos.domain.account.AccountType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.job.FactsCodec;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion;
import com.financeos.gmail.accountdetect.suggest.ExistingAccounts;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A Jupiter / Fi statement is the partner bank's: the unlock tries partner-bank passwords, the bank comes from IFSC / text / only partner. Made-up data. */
class BrandBankResolveTest {

    @Test
    void ifscPrefixNamesTheRealBank() {
        assertThat(BankPartners.resolveBank("jupiter", "AC# 55550100123456\nIFSC: FDRL0005555")).isEqualTo("federal");
        assertThat(BankPartners.resolveBank("jupiter", "Card account\nIFSC CSBK0000123")).isEqualTo("csb");
        // an IFSC of a bank that is no partner of the brand (a beneficiary row) says nothing
        assertThat(BankPartners.resolveBank("jupiter", "Pay to IFSC HDFC0001234")).isEqualTo("jupiter");
    }

    @Test
    void aBrandWithOneNamedPartnerBankTextIsThatBank() {
        assertThat(BankPartners.resolveBank("jupiter", "Edge CSB Bank RuPay Credit Card\nRuPay Transactions - 4455")).isEqualTo("csb");
        assertThat(BankPartners.resolveBank("jupiter", "Federal Bank savings, also see CSB Bank")).isEqualTo("jupiter");   // two named: unknown
        assertThat(BankPartners.resolveBank("jupiter", "Jupiter Money statement")).isEqualTo("jupiter");
    }

    @Test
    void fiHasOnePartnerSoItIsFederal() {
        assertThat(BankPartners.resolveBank("fi", "Fi Money savings statement")).isEqualTo("federal");
        assertThat(BankPartners.resolveBank("fi", null)).isEqualTo("federal");
    }

    @Test
    void otherBanksAreLeftAlone() {
        assertThat(BankPartners.resolveBank("hdfc", "IFSC FDRL0005555")).isEqualTo("hdfc");
        assertThat(BankPartners.resolveBank(null, "x")).isNull();
    }

    @Test
    void aJupiterStatementPdfReadsAsTheFederalSavingsAccount() throws Exception {
        String text = String.join("\n", "Jupiter Money Savings Account Statement", "Statement for the period 01/04/2022 to 20/04/2022",
                "AC# 55550100123456", "IFSC: FDRL0005555", "Opening Balance 1,000.00", "Date Narration Debit Credit Balance",
                "05/04/2022 UPI ACME STORE 200.00 800.00", "Closing Balance 800.00");
        StatementContent c = StatementPdfReader.fromText(text, "PDF_MAIL", "jupiter", null);
        assertThat(c.bank()).isEqualTo("federal");
        assertThat(StatementPdfReader.fromText(text.replace("FDRL0005555", ""), "PDF_MAIL", "jupiter", null).bank()).isEqualTo("jupiter");
    }

    @Test
    void aBrandMailTriesPasswordsSavedAtItsPartnerBanksAndAPartnerMailTriesNothingNew() throws Exception {
        byte[] pdf = protectedPdf("made-up-pw-2");
        UnlockProfile saved = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("csb", List.of("made-up-pw-2")));
        StatementUnlocker unlocker = StatementUnlocker.bundled();

        StatementUnlocker.Outcome brand = unlocker.unlock(pdf, new StatementUnlocker.Target("jupiter", Kind.ACCOUNT, List.of(), List.of(), null), saved);
        assertThat(brand.state()).isEqualTo(StatementUnlocker.State.UNLOCKED);
        assertThat(brand.rule()).isEqualTo("SAVED");

        // the same password saved at federal, tried for a Fi mail (partner: federal)
        UnlockProfile atFederal = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("federal", List.of("made-up-pw-2")));
        assertThat(unlocker.unlock(pdf, new StatementUnlocker.Target("fi", Kind.ACCOUNT, List.of(), List.of(), null), atFederal).state())
                .isEqualTo(StatementUnlocker.State.UNLOCKED);

        // a password saved at a bank that is no partner of the brand is not tried
        UnlockProfile atHdfc = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of("made-up-pw-2")));
        assertThat(unlocker.unlock(pdf, new StatementUnlocker.Target("jupiter", Kind.ACCOUNT, List.of(), List.of(), null), atHdfc).state())
                .isNotEqualTo(StatementUnlocker.State.UNLOCKED);

        // a partner-bank mail (csb) does not borrow passwords saved at the brand
        UnlockProfile atBrand = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("jupiter", List.of("made-up-pw-2")));
        assertThat(unlocker.unlock(pdf, new StatementUnlocker.Target("csb", Kind.ACCOUNT, List.of(), List.of(), null), atBrand).state())
                .isNotEqualTo(StatementUnlocker.State.UNLOCKED);
    }

    @Test
    void aJupiterNumberMatchesTheUsersAccountAtThePartnerBank() {
        ExistingAccounts.Acc edge = new ExistingAccounts.Acc(UUID.randomUUID(), "Jupiter Edge+", AccountType.credit_card, "csb", true, null,
                List.of(new ExistingAccounts.CardRef(UUID.randomUUID(), "4455")), List.of());
        ExistingAccounts ex = new ExistingAccounts(List.of(edge));
        assertThat(ex.match("jupiter", "4455", AccountSuggestion.Kind.CREDIT_CARD)).isNotNull();
        assertThat(ex.match("csb", "4455", AccountSuggestion.Kind.CREDIT_CARD)).isNotNull();
        assertThat(ex.match("jupiter", "9999", AccountSuggestion.Kind.CREDIT_CARD)).isNull();
        assertThat(ex.match("hdfc", "4455", AccountSuggestion.Kind.CREDIT_CARD)).isNull();
    }

    @Test
    void theNewestReaderVersionIsStaleOnlyForBrandMails() {
        MailDerived brand = new MailDerived("jupiter", "c", Vocab.Role.STATEMENT, null, Kind.ACCOUNT, List.of(), List.of(), List.of(), List.of());
        MailDerived hdfc = new MailDerived("hdfc", "c", Vocab.Role.STATEMENT, null, Kind.ACCOUNT, List.of(), List.of(), List.of(), List.of());
        int prior = StatementPdfReader.BRAND_ONLY_SINCE - 1;
        assertThat(new FactsCodec.Facts(brand, List.of(), true, null, 0, prior).readerStale()).isTrue();
        assertThat(new FactsCodec.Facts(hdfc, List.of(), true, null, 0, prior).readerStale()).isFalse();
        assertThat(new FactsCodec.Facts(hdfc, List.of(), true, null, 0, prior - 1).readerStale()).isTrue();   // older stamps: stale for all
        assertThat(new FactsCodec.Facts(brand, List.of(), true, null, 0, StatementPdfReader.READER_VERSION).readerStale()).isFalse();
    }

    private static byte[] protectedPdf(String password) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", password, new AccessPermission());
            p.setEncryptionKeyLength(128);
            doc.protect(p);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
