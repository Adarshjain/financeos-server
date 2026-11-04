package com.financeos.gmail.classify;

import com.financeos.domain.account.BankCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankSenderDomainsTest {

    private final BankSenderDomains domains = BankSenderDomains.defaults();

    @Test
    void oldDomain() {
        assertThat(domains.bankIdFor("alerts@hdfcbank.net")).isEqualTo("hdfc");
        assertThat(domains.bankIdFor("credit_cards@icicibank.com")).isEqualTo("icici");
    }

    @Test
    void bankInMigrationDomain() {
        assertThat(domains.bankIdFor("alerts@hdfcbank.bank.in")).isEqualTo("hdfc");
        assertThat(domains.bankIdFor("alerts@axis.bank.in")).isEqualTo("axis");
        assertThat(domains.bankIdFor("donotreply@sbi.bank.in")).isEqualTo("sbi");
    }

    @Test
    void subdomainMatchesBySuffix() {
        assertThat(domains.bankIdFor("x@alerts.hdfcbank.net")).isEqualTo("hdfc");
        assertThat(domains.bankIdFor("x@mail.hsbc.co.in")).isEqualTo("hsbc");
    }

    @Test
    void coBrandAndCardDomainsMapToTheirBank() {
        assertThat(domains.bankIdFor("x@sbicard.com")).isEqualTo("sbi");
        assertThat(domains.bankIdFor("x@scapia.cards")).isEqualTo("federal");
        assertThat(domains.bankIdFor("x@bobfinancial.com")).isEqualTo("bank_of_baroda");
        assertThat(domains.bankIdFor("x@sliceit.com")).isEqualTo("slice");
        assertThat(domains.bankIdFor("x@aexp.com")).isEqualTo("amex");
        assertThat(domains.bankIdFor("x@americanexpress.com")).isEqualTo("amex");
    }

    @Test
    void unknownDomainAndLookalikesAreNull() {
        assertThat(domains.bankIdFor("a@example.com")).isNull();
        assertThat(domains.bankIdFor("a@nothdfcbank.net")).isNull();
        assertThat(domains.bankIdFor("a@hdfcbank.net.evil.example")).isNull();
        assertThat(domains.bankIdFor("a@notkotak.com")).isNull();
    }

    @Test
    void caseAndBareDomainAndNullInput() {
        assertThat(domains.bankIdFor("Alerts@HDFCBank.NET")).isEqualTo("hdfc");
        assertThat(domains.bankIdFor("hdfcbank.net")).isEqualTo("hdfc");
        assertThat(domains.bankIdFor(null)).isNull();
        assertThat(domains.bankIdFor("")).isNull();
    }

    @Test
    void everyMappedIdExistsInTheCatalog() {
        domains.all().values().forEach(id -> assertThat(BankCatalog.isKnown(id)).as(id).isTrue());
    }

    @Test
    void kotakDomainsResolveToKotak() {
        assertThat(domains.bankIdFor("alerts@kotak.com")).isEqualTo("kotak");
        assertThat(domains.bankIdFor("x@mail.kotak.bank.in")).isEqualTo("kotak");
        assertThat(domains.domainsFor("kotak")).containsExactly("kotak.com", "kotak.bank.in");
    }

    @Test
    void domainsForListsOneBanksDomains_andFixedBeanIsIndependent() {
        assertThat(domains.domainsFor("hdfc")).containsExactly("hdfcbank.net", "hdfcbank.com", "hdfcbank.bank.in", "hdfc.bank.in");
        assertThat(domains.domainsFor("nope")).isEmpty();
        BankSenderDomains fixed = BankSenderDomains.of(java.util.Map.of("example.org", "hdfc"));
        assertThat(fixed.bankIdFor("a@example.org")).isEqualTo("hdfc");
        assertThat(fixed.bankIdFor("a@hdfcbank.net")).isNull();
    }

    @Test
    void longestMatchingDomainWins() {
        BankSenderDomains fixed = BankSenderDomains.of(java.util.Map.of("bank.in", "a", "x.bank.in", "b"));
        assertThat(fixed.bankIdFor("m@y.x.bank.in")).isEqualTo("b");
    }
}
