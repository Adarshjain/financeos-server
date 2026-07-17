package com.financeos.statement.parser;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;

public final class StatementParseEngine {

    private StatementParseEngine() {
    }

    public static ParsedStatement parse(byte[] bytes, String password, Consumer<String> debug) {
        Consumer<String> dbg = debug != null ? debug : s -> {
        };
        FileType type = FileType.detect(bytes);
        WordSource extractor = type == FileType.PDF ? new PdfWordExtractor() : new ExcelGridExtractor();
        List<Line> lines = extractor.extract(bytes, password);

        CollectedRows cr = RowCollector.collect(lines, dbg);

        StatementMeta meta = MetadataExtractor.extract(cr.metaZone(), cr.dateFmt());
        if (meta.accountNumber == null) {
            for (Line ln : lines) {
                Matcher m = Tokens.ACCOUNT_LABEL.matcher(ln.text());
                if (m.find()) {
                    meta.accountNumber = m.group(1);
                    break;
                }
            }
        }

        TxnBuild tb = TransactionBuilder.build(cr.rows(), cr.dateFmt(), cr.dateFmt2(),
                meta.periodStart, meta.periodEnd, cr.headerWords());
        if (tb.openingFromMarker() != null && meta.openingBalance == null) {
            meta.openingBalance = tb.openingFromMarker();
        }

        // Statements printed newest-first would run the balance chain backwards
        // and flip every sign — reverse to chronological order before the oracle.
        int asc = 0;
        int desc = 0;
        for (int i = 0; i + 1 < tb.txns().size(); i++) {
            LocalDate a = tb.txns().get(i).date;
            LocalDate b = tb.txns().get(i + 1).date;
            if (b.isAfter(a)) {
                asc++;
            } else if (b.isBefore(a)) {
                desc++;
            }
        }
        if (desc > asc) {
            Collections.reverse(tb.txns());
            dbg.accept("[debug] rows are newest-first; reversed to chronological order");
        }

        int nCols = SignAssigner.clusterAmountColumns(tb.txns());
        OracleOutcome oracle = SignAssigner.runBalanceOracle(tb.txns(), nCols, meta.openingBalance, dbg);

        StringBuilder ftb = new StringBuilder();
        for (Line ln : lines) {
            if (ftb.length() > 0) {
                ftb.append(' ');
            }
            ftb.append(ln.text());
        }
        String fullText = ftb.toString().toLowerCase(Locale.ROOT);
        boolean isCreditCard = fullText.contains("credit card") || fullText.contains("credit limit");

        // Line is a record with structural equals; the Python original keys this
        // set by id(ln), so identity semantics are required here.
        Set<Line> txnLines = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RawRow r : cr.rows()) {
            if (Boolean.TRUE.equals(r.isNewTxn())) {
                txnLines.add(r.line());
            }
        }
        int maxTxnPage = cr.maxTxnPage();
        LinkedHashMap<String, Object> summaryFields = SummaryFieldHarvester.harvest(lines, txnLines, maxTxnPage);

        List<RowResult> results;
        String mode;
        if (oracle.balanceCol() != null && oracle.score() >= 0.6) {
            SignOutcome so = SignAssigner.assignSigns(tb.txns(), oracle.balanceCol(), meta.openingBalance);
            if (meta.openingBalance == null) {
                meta.openingBalance = so.openingUsed();
            }
            results = so.results();
            mode = "balance-chain";
        } else {
            results = SignAssigner.assignSignsNoBalance(tb.txns(), cr.headerWords(), isCreditCard ? -1 : 1);
            mode = "heuristic (no balance column validated)";

            Double opening = meta.openingBalance;
            if (opening == null) {
                opening = (Double) summaryFields.get("previous_balance");
            }
            Double closing = meta.closingBalance;
            if (closing == null && isCreditCard) {
                closing = (Double) summaryFields.get("total_amount_due");
            }
            if (opening != null && closing != null && !results.isEmpty()) {
                double tol = 0.51;
                int direction = isCreditCard ? -1 : 1;
                List<RowResult> flipped = new ArrayList<>();
                for (RowResult r : results) {
                    RowResult c = r.copy();
                    if ("default".equals(c.signSource) && c.amount != null) {
                        c.amount = -c.amount;
                    }
                    flipped.add(c);
                }
                for (List<RowResult> cand : List.of(results, flipped)) {
                    double net = 0.0;
                    for (RowResult r : cand) {
                        net += r.amount != null ? r.amount : 0.0;
                    }
                    if (Math.abs(opening + direction * net - closing) <= tol) {
                        results = cand;
                        double bal = opening;
                        for (RowResult r : results) {
                            bal += direction * (r.amount != null ? r.amount : 0.0);
                            r.balance = round2(bal);
                            r.chainValid = true;
                        }
                        mode = "opening/closing reconciliation";
                        meta.openingBalance = opening;
                        meta.closingBalance = closing;
                        break;
                    }
                }
            }
        }

        // Python `if r["amount"] and ...` — an amount of exactly 0.0 is falsy
        // there and excluded from both sums; replicated with the != 0.0 test.
        double credits = 0.0;
        double debits = 0.0;
        int validRows = 0;
        for (RowResult r : results) {
            if (r.amount != null && r.amount != 0.0 && r.amount > 0) {
                credits += r.amount;
            }
            if (r.amount != null && r.amount != 0.0 && r.amount < 0) {
                debits += -r.amount;
            }
            if (r.chainValid) {
                validRows++;
            }
        }
        Double closingDerived = !results.isEmpty() && results.get(results.size() - 1).balance != null
                ? results.get(results.size() - 1).balance : null;
        Double closingTarget = meta.closingBalance != null ? meta.closingBalance : closingDerived;
        Boolean checksumOk = null;
        if (meta.openingBalance != null && closingTarget != null) {
            checksumOk = Math.abs(meta.openingBalance + credits - debits - closingTarget) < Tokens.EPS;
            if (Boolean.FALSE.equals(checksumOk) && isCreditCard) {
                checksumOk = Math.abs(meta.openingBalance + debits - credits - closingTarget) <= 0.51;
            }
        }

        Set<Double> summaryValues = new HashSet<>();
        for (Line ln : lines) {
            if (txnLines.contains(ln)) {
                continue;
            }
            for (Word w : ln.words()) {
                Amount a = Tokens.parseAmount(Tokens.norm(w.text()));
                if (a != null) {
                    summaryValues.add(a.value());
                }
            }
        }

        List<RowResult> debitRows = new ArrayList<>();
        List<RowResult> creditRows = new ArrayList<>();
        for (RowResult r : results) {
            if (r.amount != null && r.amount < 0) {
                debitRows.add(r);
            }
            if (r.amount != null && r.amount > 0) {
                creditRows.add(r);
            }
        }
        LinkedHashMap<String, Double> merchantSpend = new LinkedHashMap<>();
        for (RowResult r : debitRows) {
            String trimmed = r.description.trim();
            String key;
            if (trimmed.isEmpty()) {
                key = "(no description)";
            } else {
                String[] toks = trimmed.split("\\s+");
                key = String.join(" ", List.of(toks).subList(0, Math.min(3, toks.length)));
            }
            merchantSpend.merge(key, -r.amount, Double::sum);
        }
        List<Map.Entry<String, Double>> topMerchants = new ArrayList<>();
        for (Map.Entry<String, Double> e : merchantSpend.entrySet()) {
            topMerchants.add(Map.entry(e.getKey(), e.getValue()));
        }
        topMerchants.sort(Comparator.comparingDouble(e -> -e.getValue())); // stable: ties keep insertion order
        if (topMerchants.size() > 5) {
            topMerchants = new ArrayList<>(topMerchants.subList(0, 5));
        }
        long nDays = results.isEmpty() ? 0
                : ChronoUnit.DAYS.between(results.get(0).date, results.get(results.size() - 1).date) + 1;
        RowResult largestDebit = null;
        for (RowResult r : debitRows) {
            if (largestDebit == null || r.amount < largestDebit.amount) {
                largestDebit = r;
            }
        }
        RowResult largestCredit = null;
        for (RowResult r : creditRows) {
            if (largestCredit == null || r.amount > largestCredit.amount) {
                largestCredit = r;
            }
        }
        double debitSum = 0.0;
        for (RowResult r : debitRows) {
            debitSum += -r.amount;
        }
        Double avgDebit = debitRows.isEmpty() ? null : round2(debitSum / debitRows.size());
        Double avgDailySpend = !debitRows.isEmpty() && nDays != 0 ? round2(debitSum / nDays) : null;
        Set<LocalDate> distinctDates = new HashSet<>();
        for (RowResult r : results) {
            distinctDates.add(r.date);
        }

        Derived derived = new Derived();
        derived.transactionCount = results.size();
        derived.debitCount = debitRows.size();
        derived.creditCount = creditRows.size();
        derived.activeDays = distinctDates.size();
        derived.largestDebit = largestDebit;
        derived.largestCredit = largestCredit;
        derived.avgDebit = avgDebit;
        derived.avgDailySpend = avgDailySpend;
        derived.topMerchants = topMerchants;

        final double debitsF = debits;
        final double creditsF = credits;
        Boolean crossDebits = debits != 0.0
                ? summaryValues.stream().anyMatch(v -> Math.abs(v - debitsF) < Tokens.EPS) : null;
        Boolean crossCredits = credits != 0.0
                ? summaryValues.stream().anyMatch(v -> Math.abs(v - creditsF) < Tokens.EPS) : null;

        double tolCard = 0.51;
        LinkedHashMap<String, Boolean> cardChecks = new LinkedHashMap<>();
        if (isCreditCard) {
            if (summaryFields.containsKey("total_purchases")) {
                cardChecks.put("purchases == sum(debits)",
                        Math.abs((Double) summaryFields.get("total_purchases") - debits) <= tolCard);
            }
            if (summaryFields.containsKey("payments_received")) {
                Double cr2 = (Double) summaryFields.get("credits_received");
                double expected = (Double) summaryFields.get("payments_received") + (cr2 != null ? cr2 : 0.0);
                cardChecks.put("payments == sum(credits)", Math.abs(expected - credits) <= tolCard);
            }
            Double prev = (Double) summaryFields.get("previous_balance");
            if (prev == null) {
                prev = meta.openingBalance;
            }
            Double due = (Double) summaryFields.get("total_amount_due");
            if (prev != null && due != null) {
                Double fcv = (Double) summaryFields.get("finance_charges");
                double fc = fcv != null ? fcv : 0.0;
                cardChecks.put("prev + charges + spends - payments == due",
                        Math.abs(prev + fc + debits - credits - due) <= tolCard);
            }
        }
        if (meta.periodStart == null && !results.isEmpty()) {
            LocalDate mn = results.get(0).date;
            LocalDate mx = results.get(0).date;
            for (RowResult r : results) {
                if (r.date.isBefore(mn)) {
                    mn = r.date;
                }
                if (r.date.isAfter(mx)) {
                    mx = r.date;
                }
            }
            meta.periodStart = mn;
            meta.periodEnd = mx;
        }
        if (meta.closingBalance == null) {
            meta.closingBalance = closingDerived;
        }

        String statementType = mode.equals("balance-chain") ? "bank_account"
                : isCreditCard ? "credit_card" : "bank_account";

        boolean crossOk = Boolean.TRUE.equals(crossDebits) && !Boolean.FALSE.equals(crossCredits);
        boolean cardOk = Boolean.TRUE.equals(cardChecks.get("prev + charges + spends - payments == due"))
                || (Boolean.TRUE.equals(cardChecks.get("purchases == sum(debits)"))
                && Boolean.TRUE.equals(cardChecks.get("payments == sum(credits)")));
        double chainPct = results.isEmpty() ? 0 : round1(100.0 * validRows / results.size());
        String verdict;
        if ((Boolean.TRUE.equals(checksumOk) && chainPct > 95)
                || (Boolean.TRUE.equals(checksumOk) && mode.startsWith("opening/closing"))
                || (checksumOk == null && (crossOk || cardOk))) {
            verdict = "AUTO-INGEST";
        } else if (chainPct > 50 || Boolean.TRUE.equals(crossDebits)
                || Boolean.TRUE.equals(crossCredits) || cardOk) {
            verdict = "NEEDS REVIEW";
        } else {
            verdict = "REJECT";
        }

        ParseInfo parseInfo = new ParseInfo();
        parseInfo.mode = mode;
        parseInfo.rowsChainValidated = validRows;
        parseInfo.chainValidationPct = chainPct;
        parseInfo.totalCredits = round2(credits);
        parseInfo.totalDebits = round2(debits);
        parseInfo.checksumOk = checksumOk;
        parseInfo.crossDebits = crossDebits;
        parseInfo.crossCredits = crossCredits;
        parseInfo.cardChecks = cardChecks;
        parseInfo.verdict = verdict;

        return new ParsedStatement(meta, statementType, summaryFields, derived, parseInfo, results);
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }
}
