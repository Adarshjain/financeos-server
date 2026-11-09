package com.financeos.gmail.accountdetect.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.Band;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns one detection into suggestion plans against what the user already has. Pure: no database, no clock.
 *
 * <ul>
 *   <li>Skipped: an entity without a kind, with an unknown bank ("?" + sender domain, or blank), or whose bank / last4
 *       would not fit the columns. Variant text is truncated to its columns.</li>
 *   <li>Kind: ACCOUNT → BANK_ACCOUNT, CREDIT_CARD → CREDIT_CARD, DEBIT_CARD → DEBIT_CARD. Lifecycle ADD_ON (linkedTo =
 *       the primary's bank:last4) → ADDON_CARD under the primary's account or the primary's suggestion; REPLACEMENT
 *       (linkedTo = the old number) whose old number is an existing card → REPLACEMENT_CARD under that card's account.
 *       Both only at lifecycle confidence MEDIUM or better, else the base kind with no parent.</li>
 *   <li>Older number: a plain credit card (no other parent) whose number is the OLD number
 *       of a card the user already has on a credit-card account gets that account as its parent and band OLDER (the client
 *       asks "Older number on X?"). Evidence, at lifecycle confidence MEDIUM or better: the existing card's own entity is a
 *       REPLACEMENT linked to this number, or this entity is REPLACED / UPGRADED and linked to the existing card's number.
 *       Never for a row the user answered "not part" ({@code declinedParentKeys}), and a MAYBE row stays MAYBE.</li>
 *   <li>Band: MAYBE when existsConf or kindConf is below MEDIUM (checked first: a LOW guess is never a confident row);
 *       else OLDER when CLOSED / REPLACED / UPGRADED at MEDIUM+ or last seen over 12 months ago; else FOUND.</li>
 *   <li>Cross-bank: a plan whose band is MAYBE is not emitted when the user already has an open account or card at a
 *       different bank with the same last4.</li>
 * </ul>
 */
public final class SuggestionPlanner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SuggestionPlanner() {}

    /**
     * @param key               (bank:baseKind:last4)
     * @param baseKind          the detected kind (what {@code kind} falls back to when a link no longer holds)
     * @param match             the user already has this number (row becomes MATCHED)
     * @param parentKey         key of the parent's suggestion (add-on of a suggested primary)
     * @param debitNeedsBank    a debit card at a bank with no open account: attach to that bank's BANK_ACCOUNT suggestion
     * @param candidates        "Is this your…" candidate accounts (the row gets one only when exactly one)
     * @param variant           product name cut to its column
     * @param variantCandidatesJson JSON array of product names cut to fit the column, or null
     */
    public record Plan(String key, Detection.Entity entity, AccountSuggestion.Kind kind, AccountSuggestion.Kind baseKind,
                       Band band, ExistingAccounts.Match match, UUID parentAccountId, String parentKey,
                       boolean debitNeedsBank, List<UUID> candidates, String variant, String variantCandidatesJson) {}

    public static List<Plan> plan(Detection detection, ExistingAccounts existing, LocalDate today) {
        return plan(detection, existing, today, Set.of());
    }

    /** @param declinedParentKeys suggestion keys whose "older number of that account" link the user refused */
    public static List<Plan> plan(Detection detection, ExistingAccounts existing, LocalDate today, Set<String> declinedParentKeys) {
        Map<String, Detection.Entity> byDetectionKey = new HashMap<>();
        // old number (bank:last4) -> the entity that says it replaced that number
        Map<String, Detection.Entity> replacementOf = new HashMap<>();
        for (Detection.Entity e : detection.entities) {
            byDetectionKey.put(e.key(), e);
            if (e.lifecycle == Lifecycle.REPLACEMENT && e.linkedTo != null && sure(e)) replacementOf.put(e.linkedTo, e);
        }
        List<Plan> out = new ArrayList<>();
        for (Detection.Entity e : detection.entities) {
            if (!suggestible(e)) continue;
            AccountSuggestion.Kind base = baseKind(e.kind);
            String key = key(e);
            Band band = band(e, today);
            ExistingAccounts.Match match = existing.match(e.bank, e.last4, base);
            AccountSuggestion.Kind kind = base;
            UUID parentAccount = null;
            String parentKey = null;
            boolean debitNeedsBank = false;
            List<UUID> candidates = List.of();
            if (match == null) {
                boolean linkSure = sure(e);
                if (linkSure && e.lifecycle == Lifecycle.ADD_ON && e.linkedTo != null && base != AccountSuggestion.Kind.BANK_ACCOUNT) {
                    String[] p = split(e.linkedTo);
                    ExistingAccounts.Match primary = p == null ? null : existing.matchCard(p[0], p[1]);
                    Detection.Entity primaryEntity = byDetectionKey.get(e.linkedTo);
                    if (primary != null) {
                        kind = AccountSuggestion.Kind.ADDON_CARD;
                        parentAccount = primary.accountId();
                    } else if (primaryEntity != null && suggestible(primaryEntity)) {
                        kind = AccountSuggestion.Kind.ADDON_CARD;
                        parentKey = key(primaryEntity);
                    }
                } else if (linkSure && e.lifecycle == Lifecycle.REPLACEMENT && e.linkedTo != null && base != AccountSuggestion.Kind.BANK_ACCOUNT) {
                    String[] p = split(e.linkedTo);
                    ExistingAccounts.Match old = p == null ? null : existing.matchCard(p[0], p[1]);
                    if (old != null) {
                        kind = AccountSuggestion.Kind.REPLACEMENT_CARD;
                        parentAccount = old.accountId();
                    }
                }
                boolean olderLinked = false;
                if (kind == base && parentAccount == null && parentKey == null && base == AccountSuggestion.Kind.CREDIT_CARD
                        && !declinedParentKeys.contains(key)) {
                    UUID older = olderNumberOf(e, existing, replacementOf);
                    if (older != null) {
                        parentAccount = older;
                        olderLinked = true;
                        if (band != Band.MAYBE) band = Band.OLDER;   // (a not-in-your-name row is already MAYBE)
                    }
                }
                if (kind == AccountSuggestion.Kind.DEBIT_CARD && !olderLinked) {
                    List<ExistingAccounts.Acc> banks = existing.openBankAccountsAt(e.bank);
                    if (banks.size() == 1) parentAccount = banks.get(0).id();
                    else if (banks.isEmpty()) debitNeedsBank = true;
                    // several: no parent, the client asks which
                }
                candidates = existing.candidates(e.bank, e.last4, kind);
            }
            // a weak guess at one bank for a number the user already has at another bank is that bank's number seen
            // as the other party of a transfer (user-created accounts may have no mail evidence of their own)
            if (band == Band.MAYBE && match == null && existing.hasOpenAtOtherBank(e.bank, e.last4)) continue;
            out.add(new Plan(key, e, kind, base, band, match, parentAccount, parentKey, debitNeedsBank, candidates,
                    truncate(e.variant, AccountSuggestion.VARIANT_MAX), candidatesJson(e.variantCandidates)));
        }
        return out;
    }

    private static boolean sure(Detection.Entity e) {
        return e.lifecycleConf != null && e.lifecycleConf.atLeast(Conf.MEDIUM);
    }

    /**
     * The credit-card account whose card has this entity's number as its OLD number, or null: the existing card's own
     * entity is a REPLACEMENT linked to this number, or this entity is REPLACED / UPGRADED into an existing card.
     */
    private static UUID olderNumberOf(Detection.Entity e, ExistingAccounts existing, Map<String, Detection.Entity> replacementOf) {
        Detection.Entity successor = replacementOf.get(e.key());
        ExistingAccounts.Match m = successor == null || !e.bank.equals(successor.bank) ? null : existing.matchCard(successor.bank, successor.last4);
        if (m == null && (e.lifecycle == Lifecycle.REPLACED || e.lifecycle == Lifecycle.UPGRADED) && sure(e) && e.linkedTo != null) {
            String[] p = split(e.linkedTo);
            m = p == null || !e.bank.equals(p[0]) ? null : existing.matchCard(p[0], p[1]);
        }
        return m != null && existing.isCreditCardAccount(m.accountId()) ? m.accountId() : null;
    }

    /** A reported own number we can store: a kind, a known bank (never "?" + sender domain) and values that fit. */
    static boolean suggestible(Detection.Entity e) {
        if (baseKind(e.kind) == null) return false;
        if (e.bank == null || e.bank.isBlank() || e.bank.startsWith("?") || e.bank.length() > AccountSuggestion.BANK_MAX) return false;
        return e.last4 != null && !e.last4.isBlank() && e.last4.length() <= AccountSuggestion.LAST4_MAX;
    }

    static AccountSuggestion.Kind baseKind(Kind k) {
        if (k == null) return null;
        return switch (k) {
            case ACCOUNT -> AccountSuggestion.Kind.BANK_ACCOUNT;
            case CREDIT_CARD -> AccountSuggestion.Kind.CREDIT_CARD;
            case DEBIT_CARD -> AccountSuggestion.Kind.DEBIT_CARD;
        };
    }

    static String key(Detection.Entity e) {
        return e.bank + ":" + baseKind(e.kind) + ":" + e.last4;
    }

    static Band band(Detection.Entity e, LocalDate today) {
        if (e.notInYourName) return Band.MAYBE;   // someone else's number: never a confident row
        if (e.existsConf == null || e.existsConf == Conf.LOW) return Band.MAYBE;
        if (e.kindConf == null || !e.kindConf.atLeast(Conf.MEDIUM)) return Band.MAYBE;
        boolean goneAway = (e.lifecycle == Lifecycle.CLOSED || e.lifecycle == Lifecycle.REPLACED || e.lifecycle == Lifecycle.UPGRADED)
                && e.lifecycleConf != null && e.lifecycleConf.atLeast(Conf.MEDIUM);
        boolean stale = e.last != null && e.last.isBefore(today.minusMonths(12));
        return goneAway || stale ? Band.OLDER : Band.FOUND;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** The names as a JSON array that fits the column: each name cut, trailing names dropped until it fits; null when none. */
    static String candidatesJson(List<String> names) {
        List<String> cut = new ArrayList<>();
        for (String n : names) if (n != null && !n.isBlank()) cut.add(truncate(n, AccountSuggestion.VARIANT_MAX));
        while (!cut.isEmpty()) {
            try {
                String json = JSON.writeValueAsString(cut);
                if (json.length() <= AccountSuggestion.VARIANT_CANDIDATES_MAX) return json;
            } catch (Exception e) {
                return null;
            }
            cut.remove(cut.size() - 1);
        }
        return null;
    }

    private static String[] split(String detectionKey) {
        int i = detectionKey.lastIndexOf(':');
        if (i <= 0 || i == detectionKey.length() - 1) return null;
        return new String[]{detectionKey.substring(0, i), detectionKey.substring(i + 1)};
    }
}
