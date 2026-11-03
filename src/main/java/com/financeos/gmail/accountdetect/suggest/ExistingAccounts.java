package com.financeos.gmail.accountdetect.suggest;

import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One user's accounts as matching needs them: bank, open, bank-details last4, every card (open or closed), aliases. */
public record ExistingAccounts(List<Acc> accounts) {

    public record Acc(UUID id, String name, AccountType type, String bank, boolean open, String bankLast4,
                      List<CardRef> cards, List<Alias> aliases) {

        /** The number to show for this account: a bank account's last4, else its first open card's. */
        public String displayLast4() {
            if (type == AccountType.bank_account) return bankLast4;
            return cards.stream().filter(CardRef::open).map(CardRef::last4).findFirst().orElse(null);
        }
    }

    /** A card on an account, open or closed. */
    public record CardRef(UUID id, String last4, boolean open) {
        public CardRef(UUID id, String last4) {
            this(id, last4, true);
        }
    }

    public record Alias(String value, AccountIdentifierKind kind) {}

    /** What an existing number is: the account, and the card when it is a card number. */
    public record Match(UUID accountId, UUID cardId) {}

    public static final ExistingAccounts NONE = new ExistingAccounts(List.of());

    static boolean isCardKind(AccountSuggestion.Kind kind) {
        return kind != AccountSuggestion.Kind.BANK_ACCOUNT;
    }

    /**
     * The user already has this number. Card kinds match only cards (open or closed, under any account) and a credit-card
     * account's own last4; a bank account matches only a bank account's number or its aliases. The bank must be known
     * and equal: an account with no bank never matches (it can only be asked about, see {@link #candidates}).
     */
    public Match match(String bank, String last4, AccountSuggestion.Kind kind) {
        if (last4 == null || bank == null || bank.isBlank() || kind == null) return null;
        boolean card = isCardKind(kind);
        Match viaAccount = null;
        for (Acc a : accounts) {
            if (a.bank() == null || !a.bank().equals(bank)) continue;
            if (card) {
                for (CardRef c : a.cards()) if (last4.equals(c.last4())) return new Match(a.id(), c.id());
                if (viaAccount == null && a.type() == AccountType.credit_card && last4.equals(a.bankLast4())) {
                    viaAccount = new Match(a.id(), null);
                }
            } else if (a.type() == AccountType.bank_account && viaAccount == null
                    && (last4.equals(a.bankLast4()) || a.aliases().stream().anyMatch(al -> aliasMatches(al, last4)))) {
                viaAccount = new Match(a.id(), null);
            }
        }
        return viaAccount;
    }

    /** A card (open or closed) with this number at the same known bank. */
    public Match matchCard(String bank, String last4) {
        return match(bank, last4, AccountSuggestion.Kind.CREDIT_CARD);
    }

    /**
     * An alias names the number when it is the last4 itself, or (account-number aliases only) a longer number ending in
     * it; customer ids, CRNs and other aliases match exactly only.
     */
    static boolean aliasMatches(Alias alias, String last4) {
        String v = alias.value() == null ? "" : alias.value().replaceAll("\\s+", "");
        if (v.equals(last4)) return true;
        return alias.kind() == AccountIdentifierKind.ACCOUNT_NUMBER && v.length() > 4 && v.endsWith(last4);
    }

    /** Open bank accounts at exactly this bank. */
    public List<Acc> openBankAccountsAt(String bank) {
        List<Acc> out = new ArrayList<>();
        for (Acc a : accounts) if (a.open() && a.type() == AccountType.bank_account && bank.equals(a.bank())) out.add(a);
        return out;
    }

    /**
     * "Is this your..." candidates for a bank account or credit card suggestion: open accounts of that type at the same
     * bank with no number yet, plus open accounts of that type with no bank whose number equals this last4. Other kinds
     * never ask.
     */
    public List<UUID> candidates(String bank, String last4, AccountSuggestion.Kind kind) {
        if (kind != AccountSuggestion.Kind.BANK_ACCOUNT && kind != AccountSuggestion.Kind.CREDIT_CARD) return List.of();
        AccountType type = kind == AccountSuggestion.Kind.BANK_ACCOUNT ? AccountType.bank_account : AccountType.credit_card;
        List<UUID> out = new ArrayList<>();
        for (Acc a : accounts) {
            if (!a.open() || a.type() != type) continue;
            boolean bankAcc = type == AccountType.bank_account;
            if (a.bank() != null) {
                boolean noNumber = bankAcc ? a.bankLast4() == null : a.cards().isEmpty();
                if (noNumber && a.bank().equals(bank)) out.add(a.id());
            } else {
                boolean same = bankAcc ? last4.equals(a.bankLast4()) : a.cards().stream().anyMatch(c -> last4.equals(c.last4()));
                if (same) out.add(a.id());
            }
        }
        return out;
    }
}
