package com.financeos.gmail.classify;

import com.financeos.domain.account.BankCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankSenderDomainsTest {

    @Test
    void oldDomain() {
        assertThat(BankSenderDomains.bankIdFor("alerts@hdfcbank.net")).isEqualTo("hdfc");
        assertThat(BankSenderDomains.bankIdFor("credit_cards@icicibank.com")).isEqualTo("icici");
    }

    @Test
    void bankInMigrationDomain() {
        assertThat(BankSenderDomains.bankIdFor("alerts@hdfcbank.bank.in")).isEqualTo("hdfc");
        assertThat(BankSenderDomains.bankIdFor("alerts@axis.bank.in")).isEqualTo("axis");
        assertThat(BankSenderDomains.bankIdFor("donotreply@sbi.bank.in")).isEqualTo("sbi");
    }

    @Test
    void subdomainMatchesBySuffix() {
        assertThat(BankSenderDomains.bankIdFor("x@alerts.hdfcbank.net")).isEqualTo("hdfc");
        assertThat(BankSenderDomains.bankIdFor("x@mail.hsbc.co.in")).isEqualTo("hsbc");
    }

    @Test
    void coBrandAndCardDomainsMapToTheirBank() {
        assertThat(BankSenderDomains.bankIdFor("x@sbicard.com")).isEqualTo("sbi");
        assertThat(BankSenderDomains.bankIdFor("x@scapia.cards")).isEqualTo("federal");
        assertThat(BankSenderDomains.bankIdFor("x@bobfinancial.com")).isEqualTo("bank_of_baroda");
        assertThat(BankSenderDomains.bankIdFor("x@sliceit.com")).isEqualTo("slice");
        assertThat(BankSenderDomains.bankIdFor("x@aexp.com")).isEqualTo("amex");
        assertThat(BankSenderDomains.bankIdFor("x@americanexpress.com")).isEqualTo("amex");
    }

    @Test
    void unknownDomainAndLookalikesAreNull() {
        assertThat(BankSenderDomains.bankIdFor("a@example.com")).isNull();
        assertThat(BankSenderDomains.bankIdFor("a@nothdfcbank.net")).isNull();
        assertThat(BankSenderDomains.bankIdFor("a@hdfcbank.net.evil.example")).isNull();
        assertThat(BankSenderDomains.bankIdFor("a@kotak.com")).isNull();
    }

    @Test
    void caseAndBareDomainAndNullInput() {
        assertThat(BankSenderDomains.bankIdFor("Alerts@HDFCBank.NET")).isEqualTo("hdfc");
        assertThat(BankSenderDomains.bankIdFor("hdfcbank.net")).isEqualTo("hdfc");
        assertThat(BankSenderDomains.bankIdFor(null)).isNull();
        assertThat(BankSenderDomains.bankIdFor("")).isNull();
    }

    @Test
    void everyMappedIdExistsInTheCatalog() {
        BankSenderDomains.all().values().forEach(id -> assertThat(BankCatalog.isKnown(id)).as(id).isTrue());
    }
}
