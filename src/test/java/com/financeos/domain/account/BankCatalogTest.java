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
    void indianBankIsNotMatchedInsideUncataloguedBanksNames() {
        assertThat(BankCatalog.banksNamedIn("Alert from South Indian Bank")).isEmpty();
        assertThat(BankCatalog.banksNamedIn("Indian Overseas Bank debit alert")).isEmpty();
        assertThat(BankCatalog.banksNamedIn("Indian Bank credit")).containsExactly("indian_bank");
        assertThat(BankCatalog.banksNamedIn("South Indian Bank and Indian Bank")).containsExactly("indian_bank");
    }

    @Test
    void catalogIsTheNineteenBanks() {
        assertThat(BankCatalog.BANKS).extracting(BankCatalog.Bank::id).containsExactlyInAnyOrder(
                "amex", "au", "axis", "bank_of_baroda", "canara", "csb", "equitas", "federal", "hdfc", "hsbc",
                "icici", "idfc_first", "indian_bank", "indusind", "sbi", "sbm", "slice", "unity", "yes");
    }

    @Test
    void droppedBanksAreUnknownAndNotNamed() {
        for (String dropped : new String[] {"kotak", "pnb", "south_indian", "iob", "rbl", "idbi", "citi", "dbs"}) {
            assertThat(BankCatalog.isKnown(dropped)).as(dropped).isFalse();
        }
        assertThat(BankCatalog.banksNamedIn("Kotak Mahindra Bank alert", "Citibank")).isEmpty();
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
