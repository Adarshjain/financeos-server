package com.financeos.gmail.accountdetect.unlock;

import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker.Outcome;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker.State;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker.Target;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Encrypted PDFs built here with made-up passwords; profile: Riya Sharma, born 14 Aug 1994. */
class StatementUnlockerTest {

    private static final List<String> LINES = List.of(
            "HDFC Bank Regalia Gold Credit Card Statement",
            "Card No: XXXX XXXX XXXX 4381",
            "Statement Date: 15/09/2026",
            "Credit Limit: Rs. 4,50,000.00",
            "Total Amount Due: Rs. 12,345.00   Minimum Amount Due: Rs. 620.00");

    private final StatementUnlocker unlocker = StatementUnlocker.bundled();
    private final UnlockProfile riya = UnlockProfile.of("Riya Sharma", "1994-08-14");

    static byte[] pdf(String userPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 740);
                for (String l : LINES) {
                    cs.showText(l);
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", userPassword, new AccessPermission());
            p.setEncryptionKeyLength(128);
            doc.protect(p);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static Target target(String bank, Kind kind, List<String> hint, List<String> cards) {
        return new Target(bank, kind, hint, cards, YearMonth.of(2026, 10));
    }

    @Test
    void theMailsOwnHintOpensItAndTheStatementIsReadLikeAnOpenOne() throws Exception {
        Outcome o = unlocker.unlock(pdf("RIYA1408"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4U+DDMM"), List.of()), riya);

        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo("NAME4U+DDMM");
        assertThat(o.tries()).isEqualTo(1);
        assertThat(o.read().status()).isEqualTo(StatementPdfReader.Status.OPENED);
        assertThat(o.read().content().last4()).isEqualTo("4381");
        assertThat(o.read().content().variant()).isEqualTo("Regalia Gold");
        assertThat(o.read().content().limitBucket()).isEqualTo("3-5L");
        assertThat(o.read().content().limitAmount()).isEqualByComparingTo(new BigDecimal("450000.00"));
    }

    @Test
    void anUntaggedHintTriesUpperThenLowerAndReportsTheFormThatWorked() throws Exception {
        Outcome o = unlocker.unlock(pdf("riya1408"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4+DDMM"), List.of()), riya);

        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo("NAME4L+DDMM");
        assertThat(o.tries()).isEqualTo(2);
    }

    @Test
    void theTableIsUsedWhenTheMailNamesNoRule() throws Exception {
        Outcome o = unlocker.unlock(pdf("riya1408"), target("icici", Kind.CREDIT_CARD, List.of(), List.of()), riya);

        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo("NAME4L+DDMM");
        assertThat(o.tries()).isEqualTo(1);
    }

    @Test
    void hintRulesAreTriedBeforeTheTable() throws Exception {
        // the hint's wrong guess (1408) goes first, then the table's NAME4L+DDMM for icici: two tries
        Outcome o = unlocker.unlock(pdf("riya1408"), target("icici", Kind.CREDIT_CARD, List.of("DDMM"), List.of()), riya);
        assertThat(o.rule()).isEqualTo("NAME4L+DDMM");
        assertThat(o.tries()).isEqualTo(2);

        // when the hint's password is right it wins before the table is reached
        Outcome hintFirst = unlocker.unlock(pdf("1408"), target("icici", Kind.CREDIT_CARD, List.of("DDMM"), List.of()), riya);
        assertThat(hintFirst.rule()).isEqualTo("DDMM");
        assertThat(hintFirst.tries()).isEqualTo(1);
    }

    @Test
    void cardFourUsesTheNumbersTheMailNamedAtMostTwo() throws Exception {
        Outcome o = unlocker.unlock(pdf("4417"), target("icici", Kind.CREDIT_CARD, List.of("CARD4"), List.of("1111", "4417", "9999")), riya);
        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo("CARD4");

        Outcome third = unlocker.unlock(pdf("9999"), target("axis", Kind.CREDIT_CARD, List.of("CARD4"), List.of("1111", "4417", "9999")), riya);
        assertThat(third.state()).isEqualTo(State.FAILED);   // the third number is never tried
    }

    @Test
    void atMostTwelvePasswordsAreTriedPerPdf() throws Exception {
        // 14 distinct candidates (7 untagged name rules x upper/lower), the right password is the 13th: never reached
        List<String> hint = List.of("NAME4+DDMM", "NAME4+DDMMYY", "NAME4+DDMMYYYY", "NAME3+DDMM", "NAME5+DDMM", "NAME6+DDMM", "NAME2+DDMM");
        UnlockProfile priyanka = UnlockProfile.of("Priyanka Sharma", "1994-08-14");
        Outcome o = unlocker.unlock(pdf("pr1408"), target("nobank", Kind.CREDIT_CARD, hint, List.of()), priyanka);   // NAME2L+DDMM is candidate 14
        assertThat(o.state()).isEqualTo(State.FAILED);
        assertThat(o.tries()).isEqualTo(StatementUnlocker.MAX_CANDIDATES);
        assertThat(o.rule()).isNull();
        assertThat(o.read()).isNull();
    }

    @Test
    void identicalPasswordsAreTriedOnce() throws Exception {
        // NAME4+DDMM and NAME4U+DDMM both give RIYA1408; "Al" renders the same for NAME4 and NAME5
        Outcome o = unlocker.unlock(pdf("wrong"), target("nobank", Kind.ACCOUNT, List.of("NAME4+DDMM", "NAME4U+DDMM"), List.of()), riya);
        assertThat(o.tries()).isEqualTo(2);   // RIYA1408 and riya1408, not three or four
    }

    @Test
    void identifierRulesAreSkippedSoTheyStayLocked() throws Exception {
        UnlockProfile noSaved = riya;
        for (String rule : List.of("CUSTID+DDMMYYYY", "CRN", "CIF", "PAN5+DDMMYYYY", "MOBILE4", "ACCNO", "ACC4", "CARDNO")) {
            Outcome o = unlocker.unlock(pdf("anything"), target("nobank", Kind.ACCOUNT, List.of(rule), List.of()), noSaved);
            assertThat(o.state()).as(rule).isEqualTo(State.LOCKED);
            assertThat(o.tries()).isZero();
        }
        // a bank whose table rules are all identifier-based (yes: CUSTID / PAN5) has nothing to try either
        assertThat(unlocker.unlock(pdf("anything"), target("yes", Kind.ACCOUNT, List.of(), List.of()), noSaved).state()).isEqualTo(State.LOCKED);
    }

    @Test
    void withoutNameAndBirthDateNothingIsTriedAndItStaysLocked() throws Exception {
        Outcome o = unlocker.unlock(pdf("RIYA1408"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4U+DDMM"), List.of()), UnlockProfile.of(null, null));
        assertThat(o.state()).isEqualTo(State.LOCKED);
        assertThat(o.tries()).isZero();
        // a name alone is enough for a name-only rule
        Outcome nameOnly = unlocker.unlock(pdf("RIYA"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4U"), List.of()), UnlockProfile.of("Riya Sharma", null));
        assertThat(nameOnly.state()).isEqualTo(State.UNLOCKED);
    }

    @Test
    void savedStatementPasswordsOfThatBankAreTriedFirstAtMostThreeAndLabelledSaved() throws Exception {
        UnlockProfile withSaved = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of("made-up-saved-1"), "axis", List.of("other-bank")));

        Outcome o = unlocker.unlock(pdf("made-up-saved-1"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4U+DDMM"), List.of()), withSaved);
        assertThat(o.state()).isEqualTo(State.UNLOCKED);
        assertThat(o.rule()).isEqualTo(StatementUnlocker.SAVED);
        assertThat(o.tries()).isEqualTo(1);   // saved passwords come before the rule candidates
        assertThat(o.read().content()).isNotNull();

        // another bank's saved password is not tried
        Outcome other = unlocker.unlock(pdf("other-bank"), target("hdfc", Kind.CREDIT_CARD, List.of(), List.of()), withSaved);
        assertThat(other.state()).isEqualTo(State.FAILED);

        // saved passwords work with no name or birth date at all
        Outcome noProfile = unlocker.unlock(pdf("made-up-saved-1"), target("hdfc", Kind.CREDIT_CARD, List.of(), List.of()),
                new UnlockProfile(null, null, Map.of("hdfc", List.of("made-up-saved-1"))));
        assertThat(noProfile.rule()).isEqualTo(StatementUnlocker.SAVED);
        assertThat(noProfile.tries()).isEqualTo(1);
    }

    @Test
    void onlyTheFirstThreeSavedPasswordsAreTriedAheadOfTheRules() throws Exception {
        UnlockProfile many = new UnlockProfile("Riya Sharma", "1994-08-14", Map.of("hdfc", List.of("s1", "s2", "s3", "s4")));
        // the 4th saved password is not tried first; the hint's RIYA1408 (try 4) wins before it
        Outcome o = unlocker.unlock(pdf("RIYA1408"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4U+DDMM"), List.of()), many);
        assertThat(o.rule()).isEqualTo("NAME4U+DDMM");
        assertThat(o.tries()).isEqualTo(7);   // s1 S1 s2 S2 s3 S3 (each saved one also in the other case), then RIYA1408
    }

    @Test
    void aWrongProfileFailsAfterTryingAndTheOutcomeNeverCarriesAPassword() throws Exception {
        Outcome o = unlocker.unlock(pdf("zzz"), target("hdfc", Kind.CREDIT_CARD, List.of("NAME4U+DDMM"), List.of()), riya);
        assertThat(o.state()).isEqualTo(State.FAILED);
        assertThat(o.tries()).isGreaterThan(0);
        assertThat(o.toString()).doesNotContain("RIYA1408").doesNotContain("riya1408");
        assertThat(riya.toString()).doesNotContain("Riya").doesNotContain("1994");
    }

    @Test
    void cardNumbersPreferCardLabelledSlotsAndStopAtTwo() {
        var d = new com.financeos.gmail.accountdetect.MailDerived("hdfc", "c", com.financeos.gmail.accountdetect.Vocab.Role.STATEMENT, null, null,
                List.of(new com.financeos.gmail.accountdetect.MailDerived.Slot("a", "1111", com.financeos.gmail.accountdetect.Vocab.LabelKind.ACCOUNT),
                        new com.financeos.gmail.accountdetect.MailDerived.Slot("b", "2222", com.financeos.gmail.accountdetect.Vocab.LabelKind.CREDIT_CARD),
                        new com.financeos.gmail.accountdetect.MailDerived.Slot("c", "3333", com.financeos.gmail.accountdetect.Vocab.LabelKind.CARD)),
                List.of(), List.of(), List.of());
        assertThat(StatementUnlocker.cardNumbers(d)).containsExactly("2222", "3333");
        assertThat(StatementUnlocker.cardNumbers(null)).isEmpty();
    }
}
