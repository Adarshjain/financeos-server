package com.financeos.statement.parser;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;

public final class StatementParseEngine {

    // an amount still sitting in a description: "5 ,000.00" or "1,842.00"; not a dotted
    // date ("12.05.2024"), a rate ("12.50%") or a longer number
    private static final java.util.regex.Pattern ZERO_ROW_TEXT_AMOUNT =
            java.util.regex.Pattern.compile("(?<![\\d.])(?:\\d{1,3}(?: ?,\\d{2,3})+|\\d+)\\.\\d{2}(?![\\d.%])");

    private StatementParseEngine() {
    }

    /**
     * Reads only who the file is from (issuer id, bank text, account number): no rows, no balances.
     * Throws {@link StatementParseException} for a locked or unreadable file.
     */
    public static StatementIdentity identify(byte[] bytes, String password) {
        Consumer<String> dbg = s -> {
        };
        FileType type = FileType.detect(bytes);
        if (type == FileType.PDF) {
            int start = FileType.pdfHeaderOffset(bytes);
            if (start > 0) {
                bytes = Arrays.copyOfRange(bytes, start, bytes.length);
            }
        }
        WordSource extractor = type == FileType.PDF ? new PdfWordExtractor() : new ExcelGridExtractor();
        List<Line> lines = extractor.extract(bytes, password);
        if (CardSectionLayout.present(lines)) {
            lines = Tokens.wholeRupeeAsAmounts(CardSectionLayout.normalize(lines), true);
        } else {
            lines = Tokens.wholeRupeeAsAmounts(lines, false);
        }
        CollectedRows cr = RowCollector.collect(lines, dbg);
        StatementMeta meta = MetadataExtractor.extract(cr.metaZone(), cr.dateFmt());
        if (meta.accountNumber == null) {
            meta.accountNumber = Tokens.bestAccountNumber(lines);
        }
        fillDocumentFacts(meta, lines);
        return new StatementIdentity(meta.issuerId, meta.bank, meta.accountNumber);
    }

    public static ParsedStatement parse(byte[] bytes, String password, Consumer<String> debug) {
        Consumer<String> dbg = debug != null ? debug : s -> {
        };
        FileType type = FileType.detect(bytes);
        if (type == FileType.PDF) {
            int start = FileType.pdfHeaderOffset(bytes);
            if (start > 0) {
                dbg.accept("[debug] skipped " + start + " bytes before the %PDF- header");
                bytes = Arrays.copyOfRange(bytes, start, bytes.length);
            }
        }
        WordSource extractor = type == FileType.PDF ? new PdfWordExtractor() : new ExcelGridExtractor();
        List<Line> lines = extractor.extract(bytes, password);
        boolean cardSection = CardSectionLayout.present(lines);
        if (cardSection) {
            lines = Tokens.wholeRupeeAsAmounts(CardSectionLayout.normalize(lines), true);
        } else {
            lines = Tokens.wholeRupeeAsAmounts(lines, false);
        }

        CollectedRows cr;
        try {
            cr = RowCollector.collect(lines, dbg);
        } catch (StatementParseException e) {
            ParsedStatement empty = explicitlyEmpty(lines, e);
            if (empty == null) {
                throw e;
            }
            return empty;
        }

        StatementMeta meta = MetadataExtractor.extract(cr.metaZone(), cr.dateFmt());
        if (meta.accountNumber == null) {
            meta.accountNumber = Tokens.bestAccountNumber(lines);
        }

        TxnBuild tb = TransactionBuilder.build(cr.rows(), cr.dateFmt(), cr.dateFmt2(),
                meta.periodStart, meta.periodEnd, cr.headerWords(), cr.pageHeaders());
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
        OracleOutcome oracle = SignAssigner.runBalanceOracle(tb.txns(), nCols, meta.openingBalance,
                meta.closingBalance, cr.headerWords(), dbg);

        StringBuilder ftb = new StringBuilder();
        for (Line ln : lines) {
            if (ftb.length() > 0) {
                ftb.append(' ');
            }
            ftb.append(ln.text());
        }
        String fullText = ftb.toString().toLowerCase(Locale.ROOT);
        boolean isCreditCard = cardSection || fullText.contains("credit card") || fullText.contains("credit limit");

        // Line is a record with structural equals; the Python original keys this
        // set by id(ln), so identity semantics are required here.
        Set<Line> txnLines = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RawRow r : cr.rows()) {
            if (Boolean.TRUE.equals(r.isNewTxn())) {
                txnLines.add(r.line());
            }
        }
        int maxTxnPage = cr.maxTxnPage();
        // Closing/outstanding totals often sit on a summary page after the last
        // transaction page. Extend the harvest window across trailing summary pages
        // (short label/value lines) but stop at prose-heavy T&C pages, which carry
        // example figures next to the same labels.
        int harvestMaxPage = maxTxnPage;
        int lastPage = 0;
        for (Line ln : lines) {
            lastPage = Math.max(lastPage, ln.page());
        }
        for (int pg = maxTxnPage + 1; pg <= lastPage; pg++) {
            if (isProsePage(lines, pg)) {
                break;
            }
            harvestMaxPage = pg;
        }
        Set<String> statedNone = new LinkedHashSet<>();
        LinkedHashMap<String, Object> summaryFields = SummaryFieldHarvester.harvest(lines, txnLines, harvestMaxPage, statedNone);

        List<RowResult> results;
        String mode;
        int rowsDropped = tb.droppedRows();
        if (oracle.balanceCol() != null && oracle.score() >= 0.6) {
            SignOutcome so = SignAssigner.assignSigns(tb.txns(), oracle.balanceCol(), meta.openingBalance);
            // Opening balance printed in a multi-label summary row (Slice) is missed by the
            // metadata pass but harvested as previous_balance. Use it only when it makes
            // strictly more rows chain, so a wrong hint can never be adopted.
            Double hint = meta.openingBalance == null ? (Double) summaryFields.get("previous_balance") : null;
            if (hint != null && !isCreditCard && hint < 0) {
                hint = null; // harvested with the card-dues sign (Cr = negative): not an account opening balance
            }
            if (hint != null) {
                SignOutcome hinted = SignAssigner.assignSigns(tb.txns(), oracle.balanceCol(), hint);
                if (hinted.results().stream().filter(r -> r.chainValid).count()
                        > so.results().stream().filter(r -> r.chainValid).count()) {
                    so = hinted;
                    meta.openingBalance = hint;
                }
            }
            rowsDropped += so.pruned();
            if (meta.openingBalance == null) {
                meta.openingBalance = so.openingUsed();
            }
            results = so.results();
            mode = "balance-chain";
        } else {
            results = SignAssigner.assignSignsNoBalance(tb.txns(), cr.headerWords(), isCreditCard ? -1 : 1);
            mode = "heuristic (no balance column validated)";
            if (isCreditCard) {
                applyCardCreditWords(results);
            }

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

        // A row read as 0 whose own text still holds an amount lost its real amount to
        // another cell: a parse error. Never emit it as a 0 line; drop it, count it
        // and cap the verdict below AUTO-INGEST.
        int zeroWithAmountText = 0;
        List<RowResult> kept = new ArrayList<>(results.size());
        for (RowResult r : results) {
            if (r.amount != null && r.amount == 0.0 && r.description != null
                    && ZERO_ROW_TEXT_AMOUNT.matcher(r.description).find()) {
                zeroWithAmountText++;
            } else {
                kept.add(r);
            }
        }
        if (zeroWithAmountText > 0) {
            results = kept;
            rowsDropped += zeroWithAmountText;
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
                // "Purchases" excludes the fees/interest/GST rows that "Other debits & charges" totals,
                // so the debit rows add up to purchases + cash advance + charges.
                double purchases = (Double) summaryFields.get("total_purchases");
                Double cashAdv = (Double) summaryFields.get("cash_advance");
                Double otherChg = (Double) summaryFields.get("fees_and_charges");
                boolean plainMatch = Math.abs(purchases - debits) <= tolCard;
                boolean withCharges = Math.abs(purchases + (cashAdv != null ? cashAdv : 0.0)
                        + (otherChg != null ? otherChg : 0.0) - debits) <= tolCard;
                cardChecks.put("purchases == sum(debits)", plainMatch || withCharges);
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
        if (meta.closingBalance == null) {
            meta.closingBalance = closingDerived;
        }

        // Credit-card summary boxes sometimes render their labels in an undecodable
        // font, so the label harvester can't tag the payment due date. Structurally,
        // it's the lone date in the summary zone falling just after the period end;
        // an amount sharing that row is the minimum due.
        if (isCreditCard && !summaryFields.containsKey("payment_due_date") && meta.periodEnd != null) {
            Set<LocalDate> dueDates = new HashSet<>();
            List<Line> dueLines = new ArrayList<>();
            for (Line ln : lines) {
                if (txnLines.contains(ln) || ln.page() > harvestMaxPage) {
                    continue;
                }
                for (LocalDate d : Dates.findDatesInWords(ln.words(), Dates.DATE_FORMATS)) {
                    if (d.getYear() > 1990 && d.isAfter(meta.periodEnd)
                            && !d.isAfter(meta.periodEnd.plusDays(60))) {
                        dueDates.add(d);
                        dueLines.add(ln);
                    }
                }
            }
            if (dueDates.size() == 1) {
                summaryFields.put("payment_due_date", dueDates.iterator().next());
                if (!summaryFields.containsKey("minimum_amount_due") && dueLines.size() == 1) {
                    List<Double> amts = new ArrayList<>();
                    for (Word w : dueLines.get(0).words()) {
                        Double v = Tokens.looseAmount(w.text());
                        if (v != null) {
                            amts.add(v);
                        }
                    }
                    if (amts.size() == 1) {
                        summaryFields.put("minimum_amount_due", amts.get(0));
                    }
                }
            }
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
                || (checksumOk == null && (cardOk || (crossOk && meta.accountNumber != null && meta.periodEnd != null)))) {
            verdict = "AUTO-INGEST";
        } else if (chainPct > 50 || Boolean.TRUE.equals(crossDebits)
                || Boolean.TRUE.equals(crossCredits) || cardOk) {
            verdict = "NEEDS REVIEW";
        } else {
            verdict = "REJECT";
        }

        // A card-section statement (Jupiter Edge) prints its totals as images: nothing to cross-check, but the rows
        // were read from a recognised layout, so it is reviewable rather than rejected.
        if (cardSection && "REJECT".equals(verdict) && !results.isEmpty()) {
            verdict = "NEEDS REVIEW";
        }

        if (zeroWithAmountText > 0 && "AUTO-INGEST".equals(verdict)) {
            verdict = "NEEDS REVIEW";
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
        statedNone.removeIf(summaryFields::containsKey); // a value found later wins
        parseInfo.statedNone = Set.copyOf(statedNone);
        parseInfo.verdict = verdict;
        parseInfo.rowsDropped = rowsDropped;

        StringBuilder summary = new StringBuilder();
        for (Line ln : lines) {
            if (ln.page() <= 2 || (ln.page() > maxTxnPage && ln.page() <= harvestMaxPage)) {
                summary.append(ln.text()).append('\n');
            }
        }
        fillDocumentFacts(meta, lines);
        return new ParsedStatement(meta, statementType, summaryFields, derived, parseInfo, results, summary.toString());
    }

    // Card rows with no Cr/Dr mark and no credit/debit column are debits, unless the narration says the
    // money came back to the card.
    // Anywhere in the narration: a payment to the card.
    private static final java.util.regex.Pattern CARD_PAYMENT_WORDS = java.util.regex.Pattern.compile(
            "\\b(?:repayment|payment\\s+(?:received|credited)|payment\\s*-?\\s*thank\\s+you)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    // Only as the narration's first word: a refund / reversal, or a cashback that is credited (a merchant
    // named "CASHBACK STORE" is a purchase).
    private static final java.util.regex.Pattern CARD_LEADING_CREDIT = java.util.regex.Pattern.compile(
            "^\\W*(?:refund(?:ed)?|reversal|reversed|cashback\\s+credit(?:ed)?)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern REFUND_WORD = java.util.regex.Pattern.compile("(?i)\\brefund");
    private static final java.util.regex.Pattern REVERSAL_WORD = java.util.regex.Pattern.compile("(?i)\\brevers");

    static boolean isCardCreditNarration(String description) {
        if (CARD_PAYMENT_WORDS.matcher(description).find()) {
            return true;
        }
        // a refund that was reversed (or a reversal of a refund) is the charge again, not money back
        if (REFUND_WORD.matcher(description).find() && REVERSAL_WORD.matcher(description).find()) {
            return false;
        }
        return CARD_LEADING_CREDIT.matcher(description).find();
    }

    private static void applyCardCreditWords(List<RowResult> results) {
        for (RowResult r : results) {
            if ("default".equals(r.signSource) && r.amount != null && r.description != null
                    && isCardCreditNarration(r.description)) {
                r.amount = Math.abs(r.amount);
                r.signSource = "keyword";
            }
        }
    }

    // Phrases a statement prints in place of its table when the period had no activity.
    private static final java.util.regex.Pattern NO_TRANSACTIONS_SENTENCE = java.util.regex.Pattern.compile(
            "\\b(?:not\\s+made\\s+any\\s+transactions?|no\\s+transactions?\\s+(?:were\\s+)?(?:found|made|recorded|during|in|for|available|on)"
                    + "|there\\s+(?:are|were)\\s+no\\s+transactions?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * A statement with a period, an account number and an explicit "no transactions" sentence but no table is a
     * valid empty statement. Anything else (no sentence, no period, no number) keeps failing as before.
     */
    private static ParsedStatement explicitlyEmpty(List<Line> lines, StatementParseException original) {
        if (original.getMessage() == null || !original.getMessage().contains("no date-anchored rows")) {
            return null;
        }
        // the sentence wraps over several printed lines, so match on the joined text
        StringBuilder joined = new StringBuilder();
        for (Line ln : lines) {
            joined.append(ln.text()).append(' ');
        }
        if (!NO_TRANSACTIONS_SENTENCE.matcher(joined).find()) {
            return null;
        }
        StatementMeta meta = MetadataExtractor.extract(lines, null);
        if (meta.accountNumber == null) {
            meta.accountNumber = Tokens.bestAccountNumber(lines);
        }
        if (meta.periodStart == null || meta.periodEnd == null || meta.accountNumber == null) {
            return null;
        }
        fillDocumentFacts(meta, lines);
        Derived derived = new Derived();
        derived.topMerchants = new ArrayList<>();
        ParseInfo info = new ParseInfo();
        info.mode = "explicitly empty statement";
        info.totalCredits = 0.0;
        info.totalDebits = 0.0;
        info.cardChecks = new LinkedHashMap<>();
        info.verdict = "AUTO-INGEST";
        StringBuilder summary = new StringBuilder();
        for (Line ln : lines) {
            summary.append(ln.text()).append('\n');
        }
        return new ParsedStatement(meta, "bank_account", new LinkedHashMap<>(), derived, info, new ArrayList<>(),
                summary.toString());
    }

    /** What the document says about itself, into the added meta fields only; never affects rows, signs or the other fields. */
    private static void fillDocumentFacts(StatementMeta meta, List<Line> lines) {
        try {
            List<String> texts = new ArrayList<>();
            for (Line ln : lines) texts.add(ln.text());
            DocumentFacts.Facts f = DocumentFacts.extract(texts, DocumentReader.NO_NUMBERS, new java.util.TreeSet<>(), null);
            meta.issuerId = f.issuer();
            meta.documentKind = f.kind() == null ? null : f.kind().name();
            meta.isStatement = f.kind() != null;
            meta.documentDate = f.periodEnd();
            meta.productPhrase = f.rawProduct();
            meta.creditLimit = f.limit();
            meta.closureStated = f.closure();
        } catch (RuntimeException | StackOverflowError e) {
            // the extra facts are optional
        }
    }

    private static final java.util.regex.Pattern TERMS_HEADING = java.util.regex.Pattern.compile(
            "(?i)schedule\\s+of\\s+charges|finance\\s+charge\\s+calculation|terms\\s+(?:and|&)\\s+conditions");

    // A page is prose (terms & conditions) when a meaningful share of its lines run
    // long; genuine summary pages are short label/value rows.
    private static boolean isProsePage(List<Line> lines, int page) {
        int nonEmpty = 0;
        int longLines = 0;
        for (Line ln : lines) {
            if (ln.page() != page || ln.words().isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (ln.words().size() > 15) {
                longLines++;
            }
            // two-column terms pages ("Finance Charge calculation | Schedule of charges") keep their
            // lines short, but their worked examples still carry label-like words next to figures.
            // Only a heading at the top of the page counts: a footer line must not turn a summary
            // page into prose.
            if (nonEmpty <= 2 && ln.words().size() <= 8 && TERMS_HEADING.matcher(ln.text()).find()
                    && ln.words().stream().noneMatch(w -> Tokens.looseAmount(w.text()) != null)) {
                return true;
            }
        }
        return nonEmpty > 0 && (double) longLines / nonEmpty > 0.3;
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }
}
