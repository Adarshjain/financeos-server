package com.financeos.gmail.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Sender domains match by suffix: Equitas' .bank.in domain and the HSBC subdomains seen in mail resolve to their banks. */
class BankSenderDomainsEquitasHsbcTest {

    private final BankSenderDomains domains = BankSenderDomains.defaults();

    @Test
    void equitasBankIn_resolvesToEquitas() {
        assertThat(domains.bankIdFor("alerts@equitas.bank.in")).isEqualTo("equitas");
        assertThat(domains.bankIdFor("statements@mail.equitas.bank.in")).isEqualTo("equitas");
        assertThat(domains.bankIdFor("noreply@equitasbank.com")).isEqualTo("equitas");
    }

    @Test
    void hsbcSubdomains_resolveToHsbc() {
        assertThat(domains.bankIdFor("alerts@notification.hsbc.bank.in")).isEqualTo("hsbc");
        assertThat(domains.bankIdFor("alerts@mail.hsbc.co.in")).isEqualTo("hsbc");
    }
}
