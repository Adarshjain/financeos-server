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

        Transaction bestMatch = null;
        long bestDateDiff = Long.MAX_VALUE;
        double bestSimilarity = -1.0;

        for (Transaction candidate : candidates) {
            if (consumedTxnIds.contains(candidate.getId())) {
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

            // Find best using greedy metric (closest date, then description similarity)
            if (dateDiff < bestDateDiff) {
                bestMatch = candidate;
                bestDateDiff = dateDiff;
                bestSimilarity = calculateSimilarity(line.description(), candidate.getDescription());
            } else if (dateDiff == bestDateDiff) {
                double similarity = calculateSimilarity(line.description(), candidate.getDescription());
                if (similarity > bestSimilarity) {
                    bestMatch = candidate;
                    bestSimilarity = similarity;
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
        
        double similarity = calculateSimilarity(t1.getDescription(), t2.getDescription());
        return similarity >= 0.7; // 70% Jaccard token similarity overlap threshold
    }
}
