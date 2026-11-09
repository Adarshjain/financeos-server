package com.financeos.gmail.accountdetect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BankPartnersTest {

    @Test
    void equalBanksCover() {
        assertThat(BankPartners.covers("federal", "federal")).isTrue();
    }

    @Test
    void partnerBrandCoversItsPartnerBanks() {
        assertThat(BankPartners.covers("federal", "fi")).isTrue();
        assertThat(BankPartners.covers("federal", "jupiter")).isTrue();
        assertThat(BankPartners.covers("csb", "jupiter")).isTrue();
    }

    @Test
    void unrelatedBrandOrBankDoesNotCover() {
        assertThat(BankPartners.covers("csb", "fi")).isFalse();
        assertThat(BankPartners.covers("hdfc", "jupiter")).isFalse();
        assertThat(BankPartners.covers("jupiter", "federal")).isFalse();
    }

    @Test
    void nullsNeverCover() {
        assertThat(BankPartners.covers(null, "fi")).isFalse();
        assertThat(BankPartners.covers("federal", null)).isFalse();
        assertThat(BankPartners.covers(null, null)).isFalse();
    }

    @Test
    void brandsForListsTheBrandsMailingForABank() {
        assertThat(BankPartners.brandsFor("federal")).contains("fi", "jupiter");
        assertThat(BankPartners.brandsFor("csb")).contains("jupiter").doesNotContain("fi");
        assertThat(BankPartners.brandsFor("hdfc")).isEmpty();
        assertThat(BankPartners.brandsFor(null)).isEmpty();
    }

    @Test
    void brandDomainsResolveToTheBrandAndCsbBankInToCsb() {
        com.financeos.gmail.classify.BankSenderDomains d = com.financeos.gmail.classify.BankSenderDomains.defaults();
        assertThat(d.bankIdFor("no-reply@fi.money")).isEqualTo("fi");
        assertThat(d.bankIdFor("statements@jupiter.money")).isEqualTo("jupiter");
        assertThat(d.bankIdFor("alerts@csb.bank.in")).isEqualTo("csb");
    }

    @Test
    void otherDirectoryBrandsStayExactBank() {
        // slice, kiwi, onecard... are in BankDirectory.PARTNERS but their mail must not cover partner-bank accounts
        assertThat(BankPartners.covers("sbm", "slice")).isFalse();
        assertThat(BankPartners.covers("nesfb", "slice")).isFalse();
        assertThat(BankPartners.brandsFor("sbm")).isEmpty();
        assertThat(BankPartners.brandsFor("nesfb")).isEmpty();
    }

    @Test
    void parkedBrandMailAttachesToPartnerBankAccountsOnly() {
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("jupiter", "csb")).isTrue();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("jupiter", "federal")).isTrue();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("fi", "federal")).isTrue();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("fi", "csb")).isFalse();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("hdfc", "federal")).isFalse();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("slice", "sbm")).isFalse();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable(null, "csb")).isTrue();
        assertThat(com.financeos.gmail.attention.NumberGroupKey.attachable("csb", null)).isTrue();
    }
}
