package com.financeos.sms.line;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The six steps of the line key, against the shared catalogue. Messages are invented. */
class SmsLineKeyTest {

    private final SmsLineKey key = new SmsLineKey(new SenderCatalogue(new ObjectMapper()));

    @Test
    void operatorPrefixAndSuffixAreStripped() {
        SmsLineKey.Result r = key.resolve("vm-hsbcin-s", "Your HSBC Acc XXXXXX4321 is credited for INR 10.00");
        assertThat(r.hasLine()).isTrue();
        assertThat(r.sender()).isEqualTo("HSBCIN");
        assertThat(r.ending()).isEqualTo("4321");
        assertThat(r.family()).isNull();
    }

    @Test
    void phoneNumbersAndShortCodesArePersonal() {
        assertThat(key.resolve("+919999900000", "Rs 10 debited").skip()).isEqualTo(SmsLineKey.Skip.PERSONAL);
        assertThat(key.resolve("56767", "Rs 10 debited XX1234").skip()).isEqualTo(SmsLineKey.Skip.PERSONAL);
        assertThat(key.resolve("AB", "x").skip()).isEqualTo(SmsLineKey.Skip.PERSONAL);
        assertThat(SmsLineKey.isValidSender("123456")).isFalse();
        assertThat(SmsLineKey.isValidSender("A12345")).isTrue();
    }

    @Test
    void promoSuffixAndOtpAreDroppedBeforeAnythingElse() {
        assertThat(key.resolve("VM-HDFCBK-P", "Rs 10 debited from XX1234").skip()).isEqualTo(SmsLineKey.Skip.PROMO);
        assertThat(key.resolve("VM-HDFCBK-S", "Your OTP is 123456 for Rs 10 payment").skip()).isEqualTo(SmsLineKey.Skip.OTP);
        assertThat(key.resolve("VM-HDFCBK-S", "Do not share this code. Rs 10 debited").skip()).isEqualTo(SmsLineKey.Skip.OTP);
    }

    @Test
    void numberlessWalletWithoutFamiliesHasNoEnding() {
        SmsLineKey.Result r = key.resolve("AX-MOBIKW-T", "Rs.5 has been debited from your MobiKwik wallet XX1234");
        assertThat(r.ending()).isNull();
        assertThat(r.family()).isNull();
        assertThat(r.sender()).isEqualTo("MOBIKW");
    }

    @Test
    void numberlessSenderWithFamiliesTakesTheFirstMatchingFamily() {
        SmsLineKey.Result r = key.resolve("JUSPAY", "Payment of Rs 5 using apay balance is successful");
        assertThat(r.family()).isEqualTo("apay");
        assertThat(r.ending()).isNull();
        // no family pattern found: no line
        assertThat(key.resolve("JUSPAY", "Rs 5 debited").skip()).isEqualTo(SmsLineKey.Skip.UNKNOWN_SENDER);
    }

    @Test
    void endingIsTheEarliestMatchingPattern() {
        // grouped mask starts before the plain mask group
        assertThat(key.resolve("HSBCIN", "HSBC: A/c 123-456***-789 has been debited, ref XX5555").ending()).isEqualTo("789");
        assertThat(key.resolve("HDFCBK", "Rs 10 debited from A/c XX1234").ending()).isEqualTo("1234");
        assertThat(key.resolve("HDFCBK", "Rs 10 debited").ending()).isNull();
    }

    @Test
    void unknownSenderNeedsAmountEndingAndMoneyWord() {
        assertThat(key.resolve("TESTBK", "Rs 10.00 debited from A/c XX1234").hasLine()).isTrue();
        assertThat(key.resolve("TESTBK", "Rs 10.00 debited from your account").skip()).isEqualTo(SmsLineKey.Skip.UNKNOWN_SENDER);
        assertThat(key.resolve("TESTBK", "Your A/c XX1234 offer").skip()).isEqualTo(SmsLineKey.Skip.UNKNOWN_SENDER);
        assertThat(key.resolve("TESTBK", "Debited from A/c XX1234").skip()).isEqualTo(SmsLineKey.Skip.UNKNOWN_SENDER);
    }

    @Test
    void amountAndMoneyWordRuleForTheTransactionCount() {
        assertThat(SmsLineKey.hasAmountAndMoneyWord("INR 1,000.50 credited")).isTrue();
        assertThat(SmsLineKey.hasAmountAndMoneyWord("credited")).isFalse();
        assertThat(SmsLineKey.hasAmountAndMoneyWord("Rs 10")).isFalse();
    }

    @Test
    void endingWordPatterns() {
        assertThat(key.ending("Your Credit Card ending 4321 was used for INR 10.00")).isEqualTo("4321");
        assertThat(key.ending("CARD ENDING WITH 4321 is credited")).isEqualTo("4321");
        assertThat(key.ending("Card ending 987 spent")).isEqualTo("987");
    }

    @Test
    void accountWordPatternAndTheFullAccountNumberNonMatch() {
        assertThat(key.ending("Equitas A/c 4321 credited by INR 10.00")).isEqualTo("4321");
        assertThat(key.ending("Your A/c no. 4321 is debited")).isEqualTo("4321");
        assertThat(key.ending("A/c no: 4321 debited")).isEqualTo("4321");
        // a full unmasked account number is not an ending
        assertThat(key.ending("Funds credited to A/c 123456789012 today")).isNull();
        assertThat(key.ending("Funds credited to A/c no. 12345678 today")).isNull();
        // the grouped HSBC form still gives its own last three digits
        assertThat(key.ending("HSBC: A/c 123-456***-789 is credited")).isEqualTo("789");
    }

    @Test
    void cardWordNameDashAndTwoDigitMaskPatterns() {
        assertThat(key.ending("HDFC Bank Card 4321 At Test Store for INR 10")).isEqualTo("4321");
        assertThat(key.ending("Debit Card 4321 used")).isEqualTo("4321");
        assertThat(key.ending("Card no. 4321 used")).isEqualTo("4321");
        assertThat(key.ending("INR 10 debited from Equitas-4321 via UPI")).isEqualTo("4321");
        assertThat(key.ending("Credit Card no ****12 used for INR 10")).isEqualTo("12");
        // not a card ending: more than four digits
        assertThat(key.ending("Card 123456 blocked")).isNull();
    }

    @Test
    void theEarliestMatchWinsAcrossTheNewPatterns() {
        assertThat(key.ending("Card 4321 at Shop, A/c XX9999 debited")).isEqualTo("4321");
        assertThat(key.ending("A/c XX9999 debited, Card 4321 at Shop")).isEqualTo("9999");
    }

    @Test
    void aTwoDigitEndingMakesALineForACatalogueSender() {
        SmsLineKey.Result r = key.resolve("HSBCIN", "Credit Card no ****12 used for INR 10.00");
        assertThat(r.hasLine()).isTrue();
        assertThat(r.ending()).isEqualTo("12");
    }

    @Test
    void anAmountOrPointsFigureIsNotAnEnding() {
        assertThat(key.ending("Your Credit Card ending 1500.00 was charged")).isNull();
        assertThat(key.ending("Balance ending 1,500 today")).isNull();
        assertThat(key.ending("You earned on card 5000 points")).isNull();
        assertThat(key.ending("Redeem on card 5000 pts now")).isNull();
        assertThat(key.ending("Credit Card ending 4321 used")).isEqualTo("4321");
        assertThat(key.ending("Payment on card 4321.")).isEqualTo("4321");
    }
}
