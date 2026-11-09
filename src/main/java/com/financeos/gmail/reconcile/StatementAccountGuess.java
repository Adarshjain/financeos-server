package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.BankCatalog;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.internal.GmailMessage;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names the account a statement mail is for WITHOUT opening its PDF: the user's accounts at the sender's bank, narrowed by
 * what the mail says outside the file (credit card or bank account statement, the card or account digits, product words
 * such as "PREMIER" that match the account's name). A guess is returned only when exactly one account fits; with none or
 * several the caller behaves as if it knew nothing. Also reads the statement month a mail's subject names.
 */
final class StatementAccountGuess {

    private static final Set<String> NAME_STOP = Set.of("credit", "card", "cards", "bank", "account", "accounts", "savings", "saving",
            "salary", "current", "ac", "a", "c", "cc", "visa", "mastercard", "rupay", "my", "the", "of", "and", "ltd", "limited");
    private static final Pattern CREDIT_CARD = Pattern.compile("credit\\s*card|bobcard");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("account statement|combined (?:e-?mail )?statement|savings account|current account|bank account|salary account");
    private static final Pattern ENDING = Pattern.compile("(?i)(?:ending|ends|ended)\\s*(?:in|with|:)?\\s*(?:x+|\\*+)?\\s*(\\d{4})\\b|\\b(?:x{2,}|\\*{2,})\\s*(\\d{4})\\b");
    /** An unmasked customer ID ("customer ID 8111277"): its last four digits are what an identifier on the account keeps. */
    private static final Pattern CUSTOMER_ID = Pattern.compile("(?i)customer\\s*id\\s*:?\\s*(\\d{4,})\\b");
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
                    + "(?:[\\s,]+(\\d{4})|\\s*[-'/]\\s*(\\d{4}|\\d{2}))(?!\\d)");

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final com.financeos.domain.account.AccountIdentifierRepository identifierRepository;

    StatementAccountGuess(AccountRepository accountRepository, CardRepository cardRepository,
                          com.financeos.domain.account.AccountIdentifierRepository identifierRepository) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.identifierRepository = identifierRepository;
    }

    /**
     * What the mail pins down: the one account that fits, the digits to keep on the row (the account's own, else null) and
     * what corroborated it ("digits" or "name").
     */
    record Guess(Account account, String last4, String corroboration) {}

    Optional<Guess> find(UUID userId, GmailMessage message) {
        String bank = BankSenderDomains.current().bankIdFor(message.from());
        if (bank == null || userId == null) {
            return Optional.empty();
        }
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts == null) {
            return Optional.empty();
        }
        List<Account> candidates = new ArrayList<>();
        for (Account a : accounts) {
            if (com.financeos.gmail.accountdetect.BankPartners.covers(a.getBank(), bank) && (a.getType() == AccountType.bank_account || a.getType() == AccountType.credit_card)) {
                candidates.add(a);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // an exact bank match beats a partner-brand match
        if (candidates.stream().anyMatch(a -> bank.equals(a.getBank()))) {
            candidates = new ArrayList<>(candidates.stream().filter(a -> bank.equals(a.getBank())).toList());
        }

        String subject = lower(message.subject());
        String text = subject + " " + lower(message.getStrippedText()) + " "
                + message.attachments().stream().map(att -> lower(att.filename())).reduce("", (x, y) -> x + " " + y);

        // 1. Kind: the subject's words first, the body's only when the subject says nothing.
        AccountType kind = kindOf(subject);
        if (kind == null) {
            kind = kindOf(text);
        }
        if (kind != null) {
            AccountType wanted = kind;
            candidates = new ArrayList<>(candidates.stream().filter(a -> a.getType() == wanted).toList());
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // 2. Digits the mail names must belong to the account when the account has any known numbers.
        Set<String> mentioned = mentionedDigits(message);
        Map<UUID, Set<String>> known = new java.util.HashMap<>();
        for (Account a : candidates) {
            known.put(a.getId(), knownNumbers(a));
        }
        if (!mentioned.isEmpty()) {
            candidates = new ArrayList<>(candidates.stream().filter(a -> {
                Set<String> k = known.get(a.getId());
                return k.isEmpty() || matches(k, mentioned);
            }).toList());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            if (candidates.size() > 1) {
                List<Account> byDigits = candidates.stream().filter(a -> matches(known.get(a.getId()), mentioned)).toList();
                if (byDigits.size() == 1) {
                    candidates = new ArrayList<>(byDigits);
                }
            }
        }

        // 3. Product words that are the account's name.
        Set<String> words = words(text);
        if (candidates.size() > 1) {
            List<Account> byName = candidates.stream().filter(a -> nameMatches(a, bank, words)).toList();
            if (byName.size() == 1) {
                candidates = new ArrayList<>(byName);
            }
        }
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        Account account = candidates.get(0);
        Set<String> own = known.get(account.getId());

        // 4. A bank and kind alone never name an account: the mail's digits or a product word must say so.
        boolean byDigits = matches(own, mentioned);
        boolean byName = nameMatches(account, bank, words);
        if (!byDigits && !byName) {
            return Optional.empty();
        }
        String last4 = own.stream().filter(k -> matches(Set.of(k), mentioned)).findFirst().orElse(own.size() == 1 ? own.iterator().next() : null);
        return Optional.of(new Guess(account, last4, byDigits ? "digits" : "name"));
    }

    private static boolean matches(Set<String> known, Set<String> mentioned) {
        for (String k : known) {
            for (String m : mentioned) {
                if (k.equals(m) || (m.length() == 3 && k.endsWith(m))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AccountType kindOf(String text) {
        boolean card = CREDIT_CARD.matcher(text).find();
        boolean account = BANK_ACCOUNT.matcher(text).find();
        if (card == account) {
            return null;
        }
        return card ? AccountType.credit_card : AccountType.bank_account;
    }

    private Set<String> mentionedDigits(GmailMessage message) {
        Set<String> out = new HashSet<>(MaskedNumberHints.find(message.subject(), message.getStrippedText()));
        out.removeIf(d -> d.length() < 3);
        for (String t : new String[]{message.subject(), message.getStrippedText()}) {
            if (t == null) {
                continue;
            }
            Matcher m = ENDING.matcher(t);
            while (m.find()) {
                out.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
            Matcher cust = CUSTOMER_ID.matcher(t);
            while (cust.find()) {
                String digits = cust.group(1);
                out.add(digits.substring(digits.length() - 4));
            }
        }
        message.attachments().forEach(att -> out.addAll(
                MaskedNumberHints.find(att.filename()).stream().filter(d -> d.length() >= 3).toList()));
        return out;
    }

    /** The account's bank last4, its cards' last4 and the tails of its saved identifiers (customer ID and the like). */
    private Set<String> knownNumbers(Account a) {
        Set<String> out = new HashSet<>();
        if (a.getBankDetails() != null && a.getBankDetails().getLast4() != null && a.getBankDetails().getLast4().length() == 4) {
            out.add(a.getBankDetails().getLast4());
        }
        List<Card> cards = cardRepository.findByAccountId(a.getId());
        if (cards != null) {
            for (Card c : cards) {
                if (c.getLast4() != null && c.getLast4().length() == 4) {
                    out.add(c.getLast4());
                }
            }
        }
        if (identifierRepository != null) {
            List<com.financeos.domain.account.AccountIdentifier> ids = identifierRepository.findByAccountId(a.getId());
            if (ids != null) {
                for (com.financeos.domain.account.AccountIdentifier id : ids) {
                    String v = id.getValue() == null ? "" : id.getValue().replaceAll("[^0-9]", "");
                    if (v.length() >= 4) {
                        out.add(v.substring(v.length() - 4));
                    }
                }
            }
        }
        return out;
    }

    private static boolean nameMatches(Account a, String bankId, Set<String> mailWords) {
        Set<String> bankWords = new HashSet<>();
        bankWords.add(bankId);
        BankCatalog.find(bankId).ifPresent(b -> {
            bankWords.addAll(words(lower(b.name())));
            b.aliases().forEach(al -> bankWords.addAll(words(lower(al))));
        });
        Set<String> significant = new HashSet<>(words(lower(a.getName())));
        significant.removeAll(NAME_STOP);
        significant.removeAll(bankWords);
        return !significant.isEmpty() && mailWords.containsAll(significant);
    }

    private static Set<String> words(String text) {
        return new HashSet<>(Arrays.stream(text.replaceAll("[^a-z0-9]+", " ").trim().split(" ")).filter(w -> !w.isEmpty()).toList());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** The month the subject names ("May 2026", "SEP-26", "May-2026"), empty when it names none. */
    static Optional<YearMonth> monthInSubject(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        Matcher m = MONTH_YEAR.matcher(subject);
        if (!m.find()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3));
        if (year < 100) {
            year += 2000;
        }
        String mon = m.group(1).toLowerCase(Locale.ROOT).substring(0, 3);
        for (Month month : Month.values()) {
            if (month.name().toLowerCase(Locale.ROOT).startsWith(mon)) {
                return Optional.of(YearMonth.of(year, month));
            }
        }
        return Optional.empty();
    }

    /** True when every day of the month lies inside one of the periods. */
    static boolean covers(List<com.financeos.domain.statement.StatementCoverage.Period> periods, YearMonth month) {
        return covers(periods, month.atDay(1), month.atEndOfMonth());
    }

    static boolean covers(List<com.financeos.domain.statement.StatementCoverage.Period> periods, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate day = d;
            if (periods.stream().noneMatch(p -> p.contains(day))) {
                return false;
            }
        }
        return true;
    }
}
