package com.financeos.statement.parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

final class SignAssigner {

    private SignAssigner() {
    }

    static int clusterAmountColumns(List<TxnDraft> txns) {
        List<double[]> centers = new ArrayList<>();
        Map<AmountCell, Integer> centerOf = new HashMap<>();
        for (TxnDraft t : txns) {
            for (AmountCell a : t.amounts) {
                int found = -1;
                for (int i = 0; i < centers.size(); i++) {
                    double[] c = centers.get(i);
                    if (Math.abs(c[0] - a.x1) <= Tokens.COL_TOL) {
                        c[0] = (c[0] * c[1] + a.x1) / (c[1] + 1);
                        c[1] += 1;
                        found = i;
                        break;
                    }
                }
                if (found == -1) {
                    centers.add(new double[]{a.x1, 1});
                    found = centers.size() - 1;
                }
                centerOf.put(a, found);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> centers.get(i)[0]));
        int[] rankOf = new int[centers.size()];
        for (int rank = 0; rank < order.size(); rank++) {
            rankOf[order.get(rank)] = rank;
        }
        for (TxnDraft t : txns) {
            for (AmountCell a : t.amounts) {
                a.col = rankOf[centerOf.get(a)];
            }
        }
        return centers.size();
    }

    static OracleOutcome runBalanceOracle(List<TxnDraft> txns, int nCols, Double openingBalance,
                                           Consumer<String> debug) {
        Integer bestCol = null;
        double bestScore = 0.0;
        for (int col = 0; col < nCols; col++) {
            Double prev = openingBalance;
            int checked = 0;
            int validated = 0;
            for (TxnDraft t : txns) {
                Double bal = null;
                List<Double> others = new ArrayList<>();
                for (AmountCell a : t.amounts) {
                    if (a.col == col) {
                        if (bal == null) {
                            bal = a.value;
                        }
                    } else {
                        others.add(a.value);
                    }
                }
                if (bal == null) {
                    continue;
                }
                if (prev != null && !others.isEmpty()) {
                    checked++;
                    double delta = bal - prev;
                    boolean anyMatch = false;
                    for (double v : others) {
                        if (Math.abs(Math.abs(delta) - v) < Tokens.EPS) {
                            anyMatch = true;
                            break;
                        }
                    }
                    if (anyMatch) {
                        validated++;
                    }
                }
                prev = bal;
            }
            double score = checked == 0 ? 0.0 : (double) validated / checked;
            if (debug != null) {
                debug.accept(String.format("[debug] oracle: col %d as balance -> %d/%d rows validate",
                        col, validated, checked));
            }
            if (score > bestScore) {
                bestCol = col;
                bestScore = score;
            }
        }
        return new OracleOutcome(bestCol, bestScore);
    }

    private record ChainHit(double bal, double amount, int col) {
    }

    private static ChainHit chains(TxnDraft t, Double prev, int balanceCol) {
        Double bal = null;
        List<AmountCell> others = new ArrayList<>();
        for (AmountCell a : t.amounts) {
            if (a.col == balanceCol) {
                if (bal == null) {
                    bal = a.value;
                }
            } else {
                others.add(a);
            }
        }
        if (bal == null || prev == null || others.isEmpty()) {
            return null;
        }
        double delta = bal - prev;
        AmountCell match = null;
        for (AmountCell a : others) {
            if (Math.abs(Math.abs(delta) - a.value) < Tokens.EPS) {
                match = a;
                break;
            }
        }
        if (match == null) {
            return null;
        }
        double amount = delta >= 0 ? match.value : -match.value;
        return new ChainHit(bal, amount, match.col);
    }

    private record RunResult(List<RowResult> results, int chainBreaks, Map<Integer, List<Integer>> votes) {
    }

    private static RunResult run(List<TxnDraft> txns, int balanceCol, Double opening) {
        Double prev = opening;
        List<RowResult> results = new ArrayList<>();
        int chainBreaks = 0;
        Map<Integer, List<Integer>> votes = new HashMap<>();
        for (int idx = 0; idx < txns.size(); idx++) {
            TxnDraft t = txns.get(idx);
            Double bal = null;
            List<AmountCell> others = new ArrayList<>();
            for (AmountCell a : t.amounts) {
                if (a.col == balanceCol) {
                    if (bal == null) {
                        bal = a.value;
                    }
                } else {
                    others.add(a);
                }
            }
            ChainHit hit = chains(t, prev, balanceCol);
            // Chain-outlier pruning: a row that breaks the chain while the NEXT
            // row chains directly from the previous balance is not a transaction
            // (summary boxes and ads sometimes carry a date and amounts).
            if (hit == null && prev != null && idx + 1 < txns.size()
                    && chains(txns.get(idx + 1), prev, balanceCol) != null) {
                continue;
            }
            Double amount;
            boolean valid;
            if (hit != null) {
                amount = hit.amount();
                valid = true;
                votes.computeIfAbsent(hit.col(), k -> new ArrayList<>()).add(amount >= 0 ? 1 : -1);
            } else {
                amount = null;
                valid = false;
                if (!others.isEmpty()) {
                    AmountCell a = others.get(0);
                    for (AmountCell o : others) {
                        if (o.value > a.value) {
                            a = o;
                        }
                    }
                    amount = a.value * (a.sign != 0 ? a.sign : 1);
                    chainBreaks++;
                }
            }
            results.add(new RowResult(t.date, String.join(" ", t.desc), amount, bal, valid));
            if (bal != null) {
                prev = bal;
            }
        }
        return new RunResult(results, chainBreaks, votes);
    }

    static SignOutcome assignSigns(List<TxnDraft> txns, int balanceCol, Double openingBalance) {
        RunResult r = run(txns, balanceCol, openingBalance);
        List<RowResult> results = r.results();
        int chainBreaks = r.chainBreaks();
        Double opening = openingBalance;
        if (openingBalance == null && !txns.isEmpty()) {
            // No printed opening balance: derive it from the first row. Its sign
            // comes from an explicit Cr/Dr flag, or from the sign its amount
            // column consistently carried across the validated rows.
            TxnDraft t0 = txns.get(0);
            Double bal0 = null;
            for (AmountCell a : t0.amounts) {
                if (a.col == balanceCol) {
                    bal0 = a.value;
                    break;
                }
            }
            for (AmountCell a : t0.amounts) {
                if (a.col == balanceCol) {
                    continue;
                }
                List<Integer> vs = r.votes().getOrDefault(a.col, List.of());
                int voteSum = 0;
                for (int v : vs) {
                    voteSum += v;
                }
                int sign = a.sign != 0 ? a.sign
                        : (vs.size() >= 2 && Math.abs(voteSum) == vs.size() ? vs.get(0) : 0);
                if (bal0 != null && sign != 0) {
                    double candidateOpening = round2(bal0 - sign * a.value);
                    RunResult r2 = run(txns, balanceCol, candidateOpening);
                    if (countValid(r2.results()) > countValid(results)) {
                        results = r2.results();
                        chainBreaks = r2.chainBreaks();
                        opening = candidateOpening;
                    }
                }
                break;
            }
        }
        return new SignOutcome(results, chainBreaks, opening);
    }

    private static long countValid(List<RowResult> results) {
        long n = 0;
        for (RowResult r : results) {
            if (r.chainValid) {
                n++;
            }
        }
        return n;
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    static List<RowResult> assignSignsNoBalance(List<TxnDraft> txns, Line headerWords, int defaultSign) {
        Double debitX = null;
        Double creditX = null;
        if (headerWords != null) {
            for (Word w : headerWords.words()) {
                String wl = w.text().toLowerCase(Locale.ROOT);
                if (wl.contains("debit") || wl.contains("withdraw") || wl.contains("paid out")) {
                    debitX = w.x1();
                }
                if (wl.contains("credit") || wl.contains("deposit") || wl.contains("paid in")) {
                    creditX = w.x1();
                }
            }
        }
        // The amount lives in the statement's dominant amount column. Foreign-currency
        // originals ("GBP 140.00" beside the INR charge) and stray decimals in the
        // description parse as amount cells too, but sit in minority columns — pick by
        // column, not by position, so they aren't mistaken for the transaction amount.
        Map<Integer, Integer> colFreq = new HashMap<>();
        for (TxnDraft t : txns) {
            for (AmountCell a : t.amounts) {
                colFreq.merge(a.col, 1, Integer::sum);
            }
        }
        int mainCol = colFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);

        List<RowResult> results = new ArrayList<>();
        for (TxnDraft t : txns) {
            Double amount = null;
            String source = null;
            for (AmountCell a : t.amounts) {
                if (a.sign != 0) {
                    amount = a.value * a.sign;
                    source = "explicit";
                    break;
                }
            }
            if (amount == null && !t.amounts.isEmpty()) {
                AmountCell a = selectAmountCell(t.amounts, debitX, creditX, mainCol);
                if (debitX != null && Math.abs(a.x1 - debitX) < 40) {
                    amount = -a.value;
                    source = "header";
                } else if (creditX != null && Math.abs(a.x1 - creditX) < 40) {
                    amount = a.value;
                    source = "header";
                } else {
                    amount = a.value * defaultSign;
                    source = "default";
                }
            }
            RowResult rr = new RowResult(t.date, String.join(" ", t.desc), amount, null, false);
            rr.signSource = source;
            results.add(rr);
        }
        return results;
    }

    private static AmountCell selectAmountCell(List<AmountCell> cells, Double debitX, Double creditX, int mainCol) {
        if (debitX != null || creditX != null) {
            for (AmountCell a : cells) {
                if ((debitX != null && Math.abs(a.x1 - debitX) < 40)
                        || (creditX != null && Math.abs(a.x1 - creditX) < 40)) {
                    return a;
                }
            }
        }
        AmountCell inMain = null;
        for (AmountCell a : cells) {
            if (a.col == mainCol && (inMain == null || a.x1 > inMain.x1)) {
                inMain = a;
            }
        }
        if (inMain != null) {
            return inMain;
        }
        AmountCell rightmost = cells.get(0);
        for (AmountCell a : cells) {
            if (a.x1 > rightmost.x1) {
                rightmost = a;
            }
        }
        return rightmost;
    }
}
