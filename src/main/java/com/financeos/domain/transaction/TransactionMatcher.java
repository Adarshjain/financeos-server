package com.financeos.domain.transaction;

import com.financeos.gmail.reconcile.ParsedStatementLine;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TransactionMatcher {

    public double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String clean1 = s1.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        String clean2 = s2.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        String[] tokens1 = clean1.split("\\s+");
        String[] tokens2 = clean2.split("\\s+");

        Set<String> set1 = Arrays.stream(tokens1).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
        Set<String> set2 = Arrays.stream(tokens2).filter(t -> !t.isEmpty()).collect(Collectors.toSet());

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / union.size();
    }

    public Transaction findBestMatch(
            ParsedStatementLine line,
            List<Transaction> candidates,
            int dateWindow,
            Set<UUID> consumedTxnIds) {
        return findBestMatch(line, null, candidates, dateWindow, consumedTxnIds);
    }

    public Transaction findBestMatch(
            ParsedStatementLine line,
            UUID lineCardId,
            List<Transaction> candidates,
            int dateWindow,
            Set<UUID> consumedTxnIds) {

        Transaction bestMatch = null;
        boolean bestIsSameCard = false;
        long bestDateDiff = Long.MAX_VALUE;
        double bestSimilarity = -1.0;

        for (Transaction candidate : candidates) {
            if (consumedTxnIds.contains(candidate.getId())) {
                continue;
            }

            // Skip candidates on a different known card (they are different transactions)
            if (lineCardId != null && candidate.getCard() != null && !lineCardId.equals(candidate.getCard().getId())) {
                continue;
            }

            // Exact amount and same direction check
            if (candidate.getAmount().compareTo(line.amount().abs()) != 0) {
                continue;
            }
            TransactionType lineType = TransactionType.fromLlmDirection(line.direction());
            if (candidate.getType() != lineType) {
                continue;
            }

            // Date within window check
            long dateDiff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(line.date(), candidate.getDate()));
            if (dateDiff > dateWindow) {
                continue;
            }

            // Bank text vs bank text: the same row always carries the same date and narration,
            // so two rows that differ only in a reference number are different transactions.
            if (isBankText(candidate)
                    && (dateDiff != 0 || !sameText(line.description(), effectiveDescription(candidate)))) {
                continue;
            }

            boolean isSameCard = lineCardId != null && candidate.getCard() != null && lineCardId.equals(candidate.getCard().getId());
            double similarity = calculateSimilarity(line.description(), effectiveDescription(candidate));

            if (bestMatch == null) {
                bestMatch = candidate;
                bestIsSameCard = isSameCard;
                bestDateDiff = dateDiff;
                bestSimilarity = similarity;
            } else {
                if (isSameCard && !bestIsSameCard) {
                    bestMatch = candidate;
                    bestIsSameCard = true;
                    bestDateDiff = dateDiff;
                    bestSimilarity = similarity;
                } else if (isSameCard == bestIsSameCard) {
                    if (dateDiff < bestDateDiff) {
                        bestMatch = candidate;
                        bestDateDiff = dateDiff;
                        bestSimilarity = similarity;
                    } else if (dateDiff == bestDateDiff && similarity > bestSimilarity) {
                        bestMatch = candidate;
                        bestSimilarity = similarity;
                    }
                }
            }
        }

        return bestMatch;
    }

    public boolean areDuplicates(Transaction t1, Transaction t2, int dateWindow) {
        if (t1.getDate() == null || t2.getDate() == null) return false;
        long dateDiff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(t1.getDate(), t2.getDate()));
        if (dateDiff > dateWindow) return false;
        if (t1.getAmount() == null || t2.getAmount() == null) return false;
        if (t1.getAmount().compareTo(t2.getAmount()) != 0) return false;
        if (t1.getType() != t2.getType()) return false;

        if (isBankText(t1) && isBankText(t2)) {
            return dateDiff == 0 && sameText(effectiveDescription(t1), effectiveDescription(t2));
        }

        return namesSameCounterparty(effectiveDescription(t1), effectiveDescription(t2));
    }

    /**
     * True when the row's description is the bank's own narration (statement upload or Gmail statement),
     * as opposed to alert text written by the extractor or a user-typed manual entry.
     */
    private static boolean isBankText(Transaction t) {
        return t.getSource() == TransactionSource.file_upload || t.getSource() == TransactionSource.gmail_statement;
    }

    /** Exact comparison ignoring case and whitespace (PDF extraction can wrap or space narrations differently). */
    static boolean sameText(String s1, String s2) {
        if (s1 == null || s2 == null) return false;
        return s1.replaceAll("\\s+", "").equalsIgnoreCase(s2.replaceAll("\\s+", ""));
    }

    /**
     * True when the shorter text appears in the longer one, starting at a word, ignoring case,
     * punctuation and legal suffixes: an alert's or manual entry's counterparty ("Swiggy Limited")
     * matches the bank narration that names it ("UPI/DR/4123/SWIGGY/YESB"), while two narrations
     * that differ anywhere, such as in a reference number, never match.
     */
    static boolean namesSameCounterparty(String s1, String s2) {
        if (s1 == null || s2 == null) return false;
        List<String> words1 = words(s1);
        List<String> words2 = words(s2);
        String joined1 = String.join("", words1);
        String joined2 = String.join("", words2);
        boolean firstIsShorter = joined1.length() <= joined2.length();
        List<String> shortWords = firstIsShorter ? words1 : words2;
        List<String> longWords = firstIsShorter ? words2 : words1;
        String needle = String.join("", shortWords.stream().filter(w -> !LEGAL_SUFFIXES.contains(w)).toList());
        if (needle.length() < 3) return false;

        String haystack = String.join("", longWords);
        int wordStart = 0;
        for (String word : longWords) {
            if (haystack.startsWith(needle, wordStart)) return true;
            wordStart += word.length();
        }
        return false;
    }

    private static final Set<String> LEGAL_SUFFIXES = Set.of("ltd", "limited", "pvt", "private", "llp", "inc", "corp");

    private static List<String> words(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
    }

    /**
     * Returns the source description for matching: prefers sourcedDescription (original from ingestion),
     * falls back to description (for manually created transactions that have no sourcedDescription).
     */
    private String effectiveDescription(Transaction t) {
        return t.getSourcedDescription() != null ? t.getSourcedDescription() : t.getDescription();
    }
}
