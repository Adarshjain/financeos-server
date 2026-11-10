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

import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRouting;
import com.financeos.statement.parser.layout.ProfiledReader;
import com.financeos.statement.parser.layout.ReaderMode;

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
        List<Line> lines = extractLines(bytes, password, dbg).lines();
        CollectedRows cr = RowCollector.collect(lines, dbg);
        StatementMeta meta = MetadataExtractor.extract(cr.metaZone(), cr.dateFmt());
        if (meta.accountNumber == null) {
            meta.accountNumber = Tokens.bestAccountNumber(lines);
        }
        fillDocumentFacts(meta, lines);
        return new StatementIdentity(meta.issuerId, meta.bank, meta.accountNumber);
    }

    /**
     * Version of the parser's output. Bump it whenever a change can alter the output for any real statement (that is,
     * whenever the corpus diff is not empty); it is stored with each statement beside the parse snapshot.
     * Rule: bump it in the same commit that makes any layout profile ACTIVE by default.
     */
    public static final int PARSER_VERSION = 1;

    /** The words of a file as ordered lines, normalised the way every reader expects them. */
    public record Extracted(List<Line> lines, boolean cardSection) {
    }

    public static ParsedStatement parse(byte[] bytes, String password, Consumer<String> debug) {
        Consumer<String> dbg = debug != null ? debug : s -> {
        };
        Extracted ex = extractLines(bytes, password, dbg);
        return parseExtracted(ex, dbg);
    }

    /** Reads a file into lines: file type, preamble offset, word extraction, card-section normalisation, whole-rupee rewrite. */
    public static Extracted extractLines(byte[] bytes, String password) {
        return extractLines(bytes, password, s -> {
        });
    }

    public static Extracted extractLines(byte[] bytes, String password, Consumer<String> dbg) {
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
        return new Extracted(lines, cardSection);
    }

    /** Test seam: lines already built, routed and read exactly like {@link #parse} does after extraction. */
    public static ParsedStatement parseLines(List<Line> lines, boolean cardSection, Consumer<String> debug) {
        Consumer<String> dbg = debug != null ? debug : s -> {
        };
        return parseExtracted(new Extracted(lines, cardSection), dbg);
    }

    private static ParsedStatement parseExtracted(Extracted ex, Consumer<String> dbg) {
        // anything going wrong in routing or the registry means: v1 only, exactly as before profiles existed
        LayoutProfile profile = null;
        ReaderMode routed = ReaderMode.OFF;
        try {
            LayoutRouting.Decision decision = LayoutRouting.decide(ex.lines());
            profile = decision.profile();
            routed = decision.mode();
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Throwable t) {
            profile = null;
            routed = ReaderMode.OFF;
        }
        if (routed == ReaderMode.ACTIVE) {
            return parseActive(ex, profile, dbg);
        }
        ParsedStatement v1 = parseV1(ex, dbg);
        // the shadow run only happens when something listens to it; any failure in it (an Error too, but never an
        // OutOfMemoryError) is invisible to the caller
        if (routed == ReaderMode.SHADOW && LayoutRouting.hasListener()) {
            try {
                ParsedStatement shadow = null;
                try {
                    ProfiledReader.Result pr = ProfiledReader.read(ex.lines(), profile);
                    shadow = finish(ex, pr.outcome()).withReader(profile.id(), "layout");
                } catch (OutOfMemoryError oom) {
                    throw oom;
                } catch (Throwable t) {
                    // reported below as failed
                }
                LayoutRouting.reportShadow(profile.id(), v1, shadow);
            } catch (OutOfMemoryError oom) {
                throw oom;
            } catch (Throwable t) {
                // a failing shadow never touches the v1 result
            }
        }
        return v1;
    }

    private static int verdictRank(ParsedStatement ps) {
        String v = ps.parseInfo() == null ? null : ps.parseInfo().verdict;
        return "AUTO-INGEST".equals(v) ? 2 : "NEEDS REVIEW".equals(v) ? 1 : 0;
    }

    /**
     * ACTIVE: the profiled result is returned only when it read rows, left no amount line unread, found an account or
     * card number and a period end, and earned AUTO-INGEST. Otherwise v1 reads too and the better verdict wins (a tie
     * goes to v1); the fallback is reported to the listener.
     */
    private static ParsedStatement parseActive(Extracted ex, LayoutProfile profile, Consumer<String> dbg) {
        ParsedStatement layout = null;
        boolean guardOk = false;
        boolean cleanRead = false;
        try {
            ProfiledReader.Result pr = ProfiledReader.read(ex.lines(), profile);
            layout = finish(ex, pr.outcome()).withReader(profile.id(), "layout");
            cleanRead = !layout.transactions().isEmpty() && pr.unreadAmountLines() == 0;
            guardOk = cleanRead && layout.meta().accountNumber != null && layout.meta().periodEnd != null
                    && verdictRank(layout) == 2;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Throwable e) {
            layout = null;
            dbg.accept("[debug] layout reader " + profile.id() + " failed; using v1");
        }
        if (layout != null && (guardOk || LayoutRouting.forcedLayout())) {
            return layout;
        }
        LayoutRouting.reportFallback(profile.id());
        // v1 reads too; whatever it throws propagates exactly as it did before profiles existed
        ParsedStatement v1 = parseV1(ex, dbg);
        return layout != null && cleanRead && verdictRank(layout) > verdictRank(v1) ? layout : v1;
    }

    /** v1 only, as before layout profiles existed: no routing, no shadow run. For paths that only need hints. */
    public static ParsedStatement parseV1Only(byte[] bytes, String password, Consumer<String> debug) {
        Consumer<String> dbg = debug != null ? debug : s -> {
        };
        return parseV1(extractLines(bytes, password, dbg), dbg);
    }

    private static ParsedStatement parseV1(Extracted ex, Consumer<String> dbg) {
        ReadOutcome ro;
        try {
            ro = readV1(ex.lines(), ex.cardSection(), dbg);
        } catch (StatementParseException e) {
            ParsedStatement empty = explicitlyEmpty(ex.lines(), e);
            if (empty == null) {
                throw e;
            }
            return empty.withReader(null, "v1");
        }
        return finish(ex, ro).withReader(null, "v1");
    }

    /** The issuer id the document's own text points to (null when not unique); used to pick a layout profile. */
    public static String issuerOf(List<Line> lines) {
        try {
            List<String> texts = new ArrayList<>();
            for (Line ln : lines) texts.add(ln.text());
            return DocumentFacts.extract(texts, DocumentReader.NO_NUMBERS, new java.util.TreeSet<>(), null).issuer();
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** The v1 (generic heuristic) reader: lines to meta, signed rows, mode and the harvested summary fields. */
    private static ReadOutcome readV1(List<Line> lines, boolean cardSection, Consumer<String> dbg) {
        CollectedRows cr = RowCollector.collect(lines, dbg);

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
        int harvestMaxPage = harvestWindow(lines, maxTxnPage);
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

            List<RowResult> reconciled = reconcileOpeningClosing(results, meta, summaryFields, isCreditCard);
            if (reconciled != null) {
                results = reconciled;
                mode = "opening/closing reconciliation";
            }
        }

        return new ReadOutcome(meta, results, mode, rowsDropped, txnLines, maxTxnPage, harvestMaxPage,
                summaryFields, statedNone, isCreditCard);
    }

    /**
     * Rows with no validated balance column: when opening (or previous balance) and closing (or total due) are known and
     * the rows, as signed or with every defaulted sign flipped, add up to them, the rows get a running balance and a
     * valid chain; meta gets the opening and closing used. Returns the reconciled rows, or null when nothing reconciles.
     */
    public static List<RowResult> reconcileOpeningClosing(List<RowResult> results, StatementMeta meta,
                                                          Map<String, Object> summaryFields, boolean isCreditCard) {
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
                    double bal = opening;
                    for (RowResult r : cand) {
                        bal += direction * (r.amount != null ? r.amount : 0.0);
                        r.balance = round2(bal);
                        r.chainValid = true;
                    }
                    meta.openingBalance = opening;
                    meta.closingBalance = closing;
                    return cand;
                }
            }
        }
        return null;
    }

    /** Everything after reading: guards, totals, checks, verdict, document facts. Shared by every reader. */
    private static ParsedStatement finish(Extracted ex, ReadOutcome ro) {
        List<Line> lines = ex.lines();
        boolean cardSection = ex.cardSection();
        StatementMeta meta = ro.meta();
        List<RowResult> results = ro.results();
        String mode = ro.mode();
        int rowsDropped = ro.rowsDropped();
        Set<Line> txnLines = ro.txnLines();
        int maxTxnPage = ro.maxTxnPage();
        int harvestMaxPage = ro.harvestMaxPage();
        LinkedHashMap<String, Object> summaryFields = ro.summaryFields();
        Set<String> statedNone = ro.statedNone();
        boolean isCreditCard = ro.isCreditCard();

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
        if (original.kind() != StatementParseException.Kind.NO_TABLE) {
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
    /** Last page whose summary figures are harvested: the last transaction page plus trailing non-prose pages. */
    public static int harvestWindow(List<Line> lines, int maxTxnPage) {
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
        return harvestMaxPage;
    }

    /** True when the page is prose (terms and conditions): no summary figure is read from it. */
    public static boolean isProse(List<Line> lines, int page) {
        return isProsePage(lines, page);
    }

    /** Balance-chain tolerance of the v1 reader; the layout reader uses the same. */
    public static final double CHAIN_TOLERANCE = Tokens.EPS;

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
