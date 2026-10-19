package com.financeos.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Set;

class BankCatalogTest {

    @Test
    void knownIds() {
        assertThat(BankCatalog.isKnown("hdfc")).isTrue();
        assertThat(BankCatalog.isKnown("indian_bank")).isTrue();
        assertThat(BankCatalog.isKnown("nope")).isFalse();
        assertThat(BankCatalog.isKnown(null)).isFalse();
    }

    @Test
    void matchesCaseInsensitivelyAndAcrossWhitespace() {
        assertThat(BankCatalog.banksNamedIn("Your hdfc   bank card ending 1234")).containsExactly("hdfc");
    }

    @Test
    void southIndianBankDoesNotAlsoCountAsIndianBank() {
        assertThat(BankCatalog.banksNamedIn("Alert from South Indian Bank")).containsExactly("south_indian");
        assertThat(BankCatalog.banksNamedIn("Indian Overseas Bank debit alert")).containsExactly("iob");
        assertThat(BankCatalog.banksNamedIn("Indian Bank credit")).containsExactly("indian_bank");
    }

    @Test
    void shortAliasesMatchWholeWordsOnly() {
        assertThat(BankCatalog.banksNamedIn("Paid via SBI card")).contains("sbi");
        assertThat(BankCatalog.banksNamedIn("Ref SBIN0001234 credited")).isEmpty();
        assertThat(BankCatalog.banksNamedIn("HDFCBANK alert")).isEmpty();
        assertThat(BankCatalog.banksNamedIn("(HDFC) alert")).containsExactly("hdfc");
    }

    @Test
    void collectsBanksAcrossSeveralTexts() {
        assertThat(BankCatalog.banksNamedIn("Axis Bank", null, "from ICICI Bank"))
                .isEqualTo(Set.of("axis", "icici"));
    }

    @Test
    void nothingNamed() {
        assertThat(BankCatalog.banksNamedIn((String) null)).isEmpty();
        assertThat(BankCatalog.banksNamedIn("Rs 500 spent at Amazon")).isEmpty();
    }
}
