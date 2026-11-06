package com.financeos.domain.transaction.match;

import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PairRulesTest {

    private static final UUID ACE = UUID.randomUUID();
    private static final LocalDate OCT_17 = LocalDate.of(2022, 10, 17);

    private static MatchRecord rec(String amount, LocalDate date, String text) {
        return new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal(amount), date, null, text, null, ReferenceNumbers.extract(text));
    }

    @Test
    void lifeInsuranceAlertPairsWithItsStatementLineDespiteTheMerchantText() {
        MatchRecord alert = rec("60182.88", OCT_17, "LIFE INSURA");
        MatchRecord line = rec("60182.88", OCT_17, "10073240 F IN. INST");

        assertThat(PairRules.canPair(alert, line, 3)).isTrue();
        assertThat(PairRules.cost(alert, line)).isEqualTo(5);
    }

    @Test
    void hardRules() {
        MatchRecord line = rec("500.00", OCT_17, "UPI/DR/412345678901/KARTHIK/OKAXIS");
        assertThat(PairRules.canPair(rec("500.50", OCT_17, "Karthik"), line, 3)).as("amount to the paisa").isFalse();
        assertThat(PairRules.canPair(rec("500", OCT_17, "Karthik"), line, 3)).as("scale does not matter").isTrue();
        assertThat(PairRules.canPair(rec("500.00", OCT_17.plusDays(2), "Karthik"), line, 1)).as("outside W").isFalse();
        assertThat(PairRules.canPair(rec("500.00", OCT_17.plusDays(1), "Karthik"), line, 1)).as("inside W").isTrue();
        MatchRecord credit = new MatchRecord(ACE, TransactionType.CREDIT, new BigDecimal("500.00"), OCT_17, null, "Karthik", null, Set.of());
        assertThat(PairRules.canPair(credit, line, 3)).as("direction").isFalse();
        MatchRecord otherAccount = new MatchRecord(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("500.00"), OCT_17, null, "Karthik", null, Set.of());
        assertThat(PairRules.canPair(otherAccount, line, 3)).as("account").isFalse();
    }

    @Test
    void cardLast4MustMatchWhenBothKnowIt() {
        MatchRecord a = new MatchRecord(ACE, TransactionType.DEBIT, BigDecimal.TEN, OCT_17, "XX1234", "Amazon", null, Set.of());
        MatchRecord b = new MatchRecord(ACE, TransactionType.DEBIT, BigDecimal.TEN, OCT_17, "5678", "Amazon", null, Set.of());
        MatchRecord unknown = new MatchRecord(ACE, TransactionType.DEBIT, BigDecimal.TEN, OCT_17, null, "Amazon", null, Set.of());
        MatchRecord same = new MatchRecord(ACE, TransactionType.DEBIT, BigDecimal.TEN, OCT_17, "1234", "Amazon", null, Set.of());
        assertThat(PairRules.canPair(a, b, 3)).isFalse();
        assertThat(PairRules.canPair(a, unknown, 3)).isTrue();
        assertThat(PairRules.canPair(a, same, 3)).isTrue();
    }

    @Test
    void differentReferencesNeverPairAndASharedOneCostsNothing() {
        MatchRecord line = rec("500.00", OCT_17, "UPI/DR/412345678901/KARTHIK/OKAXIS");
        MatchRecord sameRef = rec("500.00", OCT_17.plusDays(1), "Paid to n241 UPI Ref No 412345678901");
        MatchRecord otherRef = rec("500.00", OCT_17, "Paid to KARTHIK UPI Ref No 499999999999");
        assertThat(PairRules.canPair(sameRef, line, 1)).isTrue();
        assertThat(PairRules.cost(sameRef, line)).isZero();
        assertThat(PairRules.canPair(otherRef, line, 1)).isFalse();
    }

    @Test
    void costIsTenPerDayPlusFiveForAMerchantMiss() {
        MatchRecord line = rec("45.00", OCT_17, "UPI/DR/KARBAGAVALLI/SBIN");
        assertThat(PairRules.cost(rec("45.00", OCT_17, "KARBAGAVALLI"), line)).isZero();
        assertThat(PairRules.cost(rec("45.00", OCT_17.plusDays(2), "KARBAGAVALLI"), line)).isEqualTo(20);
        assertThat(PairRules.cost(rec("45.00", OCT_17.plusDays(1), "TEA STALL"), line)).isEqualTo(15);
    }

    @Test
    void merchantMatchStripsRailsNumbersMaskedNumbersAndSuffixes() {
        assertThat(PairRules.merchantMatches("Swiggy Limited", "UPI/DR/412345678901/SWIGGY/YESB")).isTrue();
        assertThat(PairRules.merchantMatches("UPI_ZOMATO", "POS 4xxxxxxxxxxx1234 ZOMATO PVT LTD")).isTrue();
        assertThat(PairRules.merchantMatches("NEFT ACME PAYROLL", "ACME PAYROLL")).isTrue();
        assertThat(PairRules.merchantMatches("LIFE INSURA", "10073240 F IN. INST")).isFalse();
        assertThat(PairRules.merchantMatches("XX1234", "XX1234")).isFalse();
        assertThat(PairRules.merchantMatches(null, "SWIGGY")).isFalse();
    }

    @Test
    void aForeignRecordWithARupeeAmountPairsOnTheRupeeRules() {
        MatchRecord usd = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("20.00"), OCT_17, null, "GITHUB", "USD", Set.of());
        MatchRecord line = rec("20.00", OCT_17, "GITHUB, INC. GITHUB.COM CA");
        MatchRecord inr = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("20.00"), OCT_17, null, "GITHUB", "INR", Set.of());
        assertThat(PairRules.canPair(usd, line, 7)).as("both carry a rupee amount: amount rules, no code needed").isTrue();
        assertThat(PairRules.canPair(usd, rec("21.00", OCT_17, "GITHUB, INC. GITHUB.COM CA 136 USD"), 7))
                .as("different rupee amount").isFalse();
        assertThat(PairRules.canPair(inr, line, 3)).isTrue();
    }
    // ------------------------------------------------------------ foreign currency (wave 3)

    private static MatchRecord usdAlert(LocalDate date, String text) {
        return new MatchRecord(ACE, TransactionType.DEBIT, null, date, "1234", text, "USD", Set.of(), new BigDecimal("20.00"));
    }

    @Test
    void aForeignAlertPairsWithTheLinePrintingItsCurrencyCodeWhateverTheRupeeAmount() {
        MatchRecord line = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17.plusDays(5), null,
                "GITHUB, INC. GITHUB.COM CA 136 USD", null, Set.of());
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), line, 3, 7)).isTrue();
        assertThat(PairRules.canPair(line, usdAlert(OCT_17, "GITHUB"), 3, 7)).as("either side").isTrue();
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), line, 3)).as("default foreign window is 7").isTrue();
    }

    @Test
    void aForeignAlertNeverPairsWithALineLackingTheCode() {
        MatchRecord noCode = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17, null,
                "GITHUB, INC. GITHUB.COM CA", null, Set.of());
        MatchRecord otherCode = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17, null,
                "GITHUB, INC. GITHUB.COM CA 18 EUR", null, Set.of());
        MatchRecord inWord = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17, null,
                "GITHUB USDT WALLET", null, Set.of());
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), noCode, 3, 7)).isFalse();
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), otherCode, 3, 7)).isFalse();
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), inWord, 3, 7)).as("whole token only").isFalse();
    }

    @Test
    void aForeignAlertNeverPairsBeyondTheForeignWindowOrWithAnotherMerchant() {
        MatchRecord line = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17.plusDays(8), null,
                "GITHUB, INC. GITHUB.COM CA 136 USD", null, Set.of());
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), line, 3, 7)).as("8 days apart").isFalse();
        assertThat(PairRules.canPair(usdAlert(OCT_17.plusDays(1), "GITHUB"), line, 3, 7)).as("7 days apart").isTrue();
        assertThat(PairRules.canPair(usdAlert(OCT_17.plusDays(1), "OPENAI"), line, 3, 7)).as("merchant").isFalse();
        MatchRecord otherCard = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17, "9999",
                "GITHUB, INC. GITHUB.COM CA 136 USD", null, Set.of());
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), otherCard, 3, 7)).as("card").isFalse();
        MatchRecord credit = new MatchRecord(ACE, TransactionType.CREDIT, new BigDecimal("928.08"), OCT_17, null,
                "GITHUB, INC. GITHUB.COM CA 136 USD", null, Set.of());
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), credit, 3, 7)).as("direction").isFalse();
    }

    @Test
    void twoForeignRecordsPairOnTheSameCurrencyAndOriginalAmountWithinW() {
        MatchRecord a = usdAlert(OCT_17, "GITHUB");
        assertThat(PairRules.canPair(a, usdAlert(OCT_17.plusDays(1), "GitHub Inc"), 1, 7)).isTrue();
        assertThat(PairRules.canPair(a, usdAlert(OCT_17.plusDays(2), "GITHUB"), 1, 7)).as("outside W").isFalse();
        MatchRecord other = new MatchRecord(ACE, TransactionType.DEBIT, null, OCT_17, null, "GITHUB", "USD", Set.of(), new BigDecimal("21.00"));
        assertThat(PairRules.canPair(a, other, 1, 7)).as("original amount").isFalse();
        MatchRecord eur = new MatchRecord(ACE, TransactionType.DEBIT, null, OCT_17, null, "GITHUB", "EUR", Set.of(), new BigDecimal("20.00"));
        assertThat(PairRules.canPair(a, eur, 1, 7)).as("currency").isFalse();
    }

    @Test
    void aFilledForeignRowAndAnInrMailPairOnTheRupeeAmountWithoutTheCode() {
        MatchRecord filled = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("928.08"), OCT_17, null,
                "GITHUB, INC. GITHUB.COM CA 136 USD", "USD", Set.of(), new BigDecimal("20.00"));
        MatchRecord inrMail = rec("928.08", OCT_17.plusDays(1), "GITHUB");
        assertThat(PairRules.canPair(inrMail, filled, 3, 7)).isTrue();
        assertThat(PairRules.canPair(rec("930.00", OCT_17, "GITHUB"), filled, 3, 7)).as("amount").isFalse();
        assertThat(PairRules.canPair(rec("928.08", OCT_17.plusDays(5), "GITHUB"), filled, 3, 7)).as("rupee W, not foreign W").isFalse();
    }

    @Test
    void aLinePrintingTheForeignAmountBesideTheCodeMustShowTheAlertsAmount() {
        MatchRecord before = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("1720.00"), OCT_17, null,
                "GITHUB, INC. GITHUB.COM USD 20.00", null, Set.of());
        MatchRecord after = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("1720.00"), OCT_17, null,
                "GITHUB, INC. 20.00 USD", null, Set.of());
        MatchRecord five = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("430.00"), OCT_17, null,
                "GITHUB, INC. GITHUB.COM USD 5.00", null, Set.of());
        MatchRecord within = new MatchRecord(ACE, TransactionType.DEBIT, new BigDecimal("1720.00"), OCT_17, null,
                "GITHUB, INC. USD 20.01", null, Set.of());
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), before, 3, 7)).isTrue();
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), after, 3, 7)).isTrue();
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), within, 3, 7)).as("within 0.01").isTrue();
        assertThat(PairRules.canPair(usdAlert(OCT_17, "GITHUB"), five, 3, 7)).as("printed 5.00, alert 20.00").isFalse();
        assertThat(PairRules.printedForeignAmount("GITHUB, INC. GITHUB.COM CA 136 USD", "USD")).as("integer is no amount").isNull();
    }
}
