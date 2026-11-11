package com.financeos.sms.line;

import com.financeos.domain.account.AccountType;
import com.financeos.sms.line.SmsAccountQueries.AccountRow;
import com.financeos.sms.line.SmsAccountQueries.EndingRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Guesses which of the user's accounts a line belongs to. Fills suggested_account_id only: it never turns a line on,
 * never touches a line the user already gave an account, and never replaces an earlier guess.
 */
@Service
public class SmsSuggestionService {

    private static final Pattern GENERIC_TAIL = Pattern.compile("(?i)\\s+(wallet|gift card|meal card|card)$");
    private static final int MAX_TYPO = 2;
    private static final int MIN_SUGGEST_ENDING = 3;

    private final SmsLineRepository lines;
    private final SmsAccountQueries queries;
    private final SenderCatalogue catalogue;

    public SmsSuggestionService(SmsLineRepository lines, SmsAccountQueries queries, SenderCatalogue catalogue) {
        this.lines = lines;
        this.queries = queries;
        this.catalogue = catalogue;
    }

    /** Re-runs for the user's lines that have no account and no guess yet (after an account is created, say). Returns how many got one. */
    @Transactional
    public int rerunForUser(UUID userId) {
        List<SmsLine> open = lines.findByUserId(userId).stream()
                .filter(l -> l.getAccountId() == null && l.getSuggestedAccountId() == null && l.getState() != SmsLineState.NOT_MINE)
                .toList();
        return suggest(userId, open);
    }

    /** Suggests for these lines (callers pass lines of one user); already-assigned or already-guessed lines are skipped. */
    @Transactional
    public int suggest(UUID userId, List<SmsLine> candidates) {
        List<SmsLine> todo = candidates.stream()
                .filter(l -> l.getAccountId() == null && l.getSuggestedAccountId() == null).toList();
        if (todo.isEmpty()) {
            return 0;
        }
        List<AccountRow> accounts = queries.accounts(userId);
        Map<UUID, List<String>> endings = new HashMap<>();
        for (EndingRow e : queries.endings(userId)) {
            endings.computeIfAbsent(e.accountId(), k -> new ArrayList<>()).add(e.value());
        }
        int filled = 0;
        for (SmsLine line : todo) {
            Optional<UUID> pick = suggestFor(line, accounts, endings);
            if (pick.isPresent()) {
                line.setSuggestedAccountId(pick.get());
                lines.save(line);
                filled++;
            }
        }
        return filled;
    }

    Optional<UUID> suggestFor(SmsLine line, List<AccountRow> accounts, Map<UUID, List<String>> endings) {
        Optional<SenderCatalogue.Sender> entry = catalogue.find(line.getSender());
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        SenderCatalogue.Sender sender = entry.get();
        if (line.getEnding() != null && line.getEnding().length() < MIN_SUGGEST_ENDING) {
            // Two digits are too few to name an account: the line stays unassigned.
            return Optional.empty();
        }
        if (line.getEnding() != null && sender.bank() != null) {
            List<AccountRow> matches = accounts.stream()
                    .filter(a -> sender.bank().equalsIgnoreCase(a.bank()))
                    .filter(a -> endings.getOrDefault(a.id(), List.of()).stream().anyMatch(v -> endsWith(v, line.getEnding())))
                    .toList();
            if (matches.size() == 1) {
                return Optional.of(matches.get(0).id());
            }
            List<AccountRow> open = matches.stream().filter(a -> a.closedOn() == null).toList();
            return open.size() == 1 ? Optional.of(open.get(0).id()) : Optional.empty();
        }
        if (line.getEnding() == null && sender.numberless()) {
            List<String> names = providerNames(sender.provider());
            List<AccountRow> matches = accounts.stream()
                    .filter(a -> a.type() == AccountType.generic)
                    .filter(a -> names.stream().anyMatch(n -> closeEnough(normalise(a.name()), n)))
                    .toList();
            return matches.size() == 1 ? Optional.of(matches.get(0).id()) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The stored value's trailing digits are this ending (3 or 4 digits; a 2-digit ending is never suggested); a stored value shorter than the ending never matches. */
    static boolean endsWith(String stored, String ending) {
        String v = stored.replaceAll("\\s+", "");
        return v.length() >= ending.length() && v.endsWith(ending);
    }

    private static List<String> providerNames(String provider) {
        List<String> names = new ArrayList<>();
        names.add(normalise(provider));
        String stripped = normalise(GENERIC_TAIL.matcher(provider).replaceFirst(""));
        if (!stripped.isEmpty() && !names.contains(stripped)) {
            names.add(stripped);
        }
        return names;
    }

    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Equal, or within two edits when both are long enough for that to mean something. */
    static boolean closeEnough(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return Math.min(a.length(), b.length()) >= 5 && editDistance(a, b) <= MAX_TYPO;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
