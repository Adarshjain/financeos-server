package com.financeos.gmail.classify;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a sender address domain to a {@code BankCatalog} id. Matches by suffix, so
 * {@code alerts.hdfcbank.net} resolves like {@code hdfcbank.net}. The domains live in the
 * {@code bank_sender_domains} table and are loaded once at startup into an immutable cache; tests build the
 * bean from a fixed list with {@link #of(Map)} or {@link #defaults()}.
 */
@Component
public class BankSenderDomains {

    private static final Logger log = LoggerFactory.getLogger(BankSenderDomains.class);

    /** The domains V134 seeds, in order (also what tests and DB-less callers use). */
    private static final Map<String, String> SEED = seed();

    private static final BankSenderDomains DEFAULTS = new BankSenderDomains(SEED);

    /** The loaded bean, for the static utilities (BankDirectory, eval tooling) that cannot take injection. */
    private static volatile BankSenderDomains current = DEFAULTS;

    private final BankSenderDomainRepository repository;
    private volatile Map<String, String> domainToBank;

    @Autowired
    public BankSenderDomains(BankSenderDomainRepository repository) {
        this.repository = repository;
        this.domainToBank = SEED;
    }

    private BankSenderDomains(Map<String, String> fixed) {
        this.repository = null;
        this.domainToBank = Collections.unmodifiableMap(new LinkedHashMap<>(fixed));
    }

    /** A bean over a fixed domain-to-bank map (tests). */
    public static BankSenderDomains of(Map<String, String> domainToBank) {
        return new BankSenderDomains(domainToBank);
    }

    /** A bean over the seeded domains, no database needed. */
    public static BankSenderDomains defaults() {
        return DEFAULTS;
    }

    /** The loaded bean once Spring started it, else {@link #defaults()}. */
    public static BankSenderDomains current() {
        return current;
    }

    @PostConstruct
    void load() {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (BankSenderDomain row : repository.findAll(Sort.by("domain"))) {
            loaded.put(row.getDomain().toLowerCase(Locale.ROOT), row.getBankId());
        }
        if (loaded.isEmpty()) {
            log.warn("bank_sender_domains is empty; using the built-in list");
            loaded = new LinkedHashMap<>(SEED);
        }
        this.domainToBank = Collections.unmodifiableMap(loaded);
        current = this;
        log.info("Loaded {} bank sender domains", loaded.size());
    }

    /** The catalog bank id for the sender address (or a bare domain), or null when unknown. */
    public String bankIdFor(String address) {
        if (address == null) {
            return null;
        }
        String domain = address.trim().toLowerCase(Locale.ROOT);
        int at = domain.lastIndexOf('@');
        if (at >= 0) {
            domain = domain.substring(at + 1);
        }
        while (domain.endsWith(".") || domain.endsWith(">")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty()) {
            return null;
        }
        String best = null;
        String bestBank = null;
        for (Map.Entry<String, String> e : domainToBank.entrySet()) {
            String d = e.getKey();
            if ((domain.equals(d) || domain.endsWith("." + d)) && (best == null || d.length() > best.length())) {
                best = d;
                bestBank = e.getValue();
            }
        }
        return bestBank;
    }

    /** Every catalog sender domain (for dev tooling such as the classifier eval runner). */
    public Set<String> domains() {
        return Collections.unmodifiableSet(domainToBank.keySet());
    }

    /** The domains of one bank, in load order. */
    public List<String> domainsFor(String bankId) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : domainToBank.entrySet()) {
            if (e.getValue().equals(bankId)) {
                out.add(e.getKey());
            }
        }
        return List.copyOf(out);
    }

    /** Domain to bank id, unmodifiable. */
    public Map<String, String> all() {
        return domainToBank;
    }

    private static Map<String, String> seed() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "hdfc", "hdfcbank.net", "hdfcbank.com", "hdfcbank.bank.in", "hdfc.bank.in");
        put(m, "icici", "icicibank.com", "icici.bank.in");
        put(m, "axis", "axisbank.com", "axis.bank.in");
        put(m, "sbi", "sbi.co.in", "sbi.bank.in", "sbicard.com");
        put(m, "yes", "yesbank.in", "yes.bank.in");
        put(m, "idfc_first", "idfcfirstbank.com", "idfcfirst.bank.in");
        put(m, "federal", "federalbank.co.in", "federal.bank.in", "scapia.cards");
        put(m, "au", "aubank.in", "au.bank.in");
        put(m, "equitas", "equitasbank.com");
        put(m, "indusind", "indusind.com", "indusind.bank.in");
        put(m, "bank_of_baroda", "bankofbaroda.co.in", "bankofbaroda.bank.in", "bobcard.co.in", "bobfinancial.com");
        put(m, "canara", "canarabank.com");
        put(m, "indian_bank", "indianbank.co.in");
        put(m, "csb", "csb.co.in");
        put(m, "slice", "sliceit.com");
        put(m, "unity", "theunitybank.com");
        put(m, "amex", "americanexpress.com", "aexp.com");
        put(m, "hsbc", "hsbc.co.in", "hsbc.bank.in");
        put(m, "sbm", "sbmbank.co.in");
        put(m, "kotak", "kotak.com", "kotak.bank.in");
        return Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, String> m, String bankId, String... domains) {
        for (String d : domains) {
            m.put(d, bankId);
        }
    }
}
