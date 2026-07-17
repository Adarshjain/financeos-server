package com.financeos.statement.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class ParseStatementCli {

    public static void main(String[] args) {
        String path = null;
        String password = null;
        boolean json = false;
        boolean debug = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--password" -> {
                    if (i + 1 < args.length) {
                        password = args[++i];
                    }
                }
                case "--json" -> json = true;
                case "--debug" -> debug = true;
                default -> {
                    if (path == null) {
                        path = args[i];
                    }
                }
            }
        }
        if (path == null) {
            System.err.println("usage: ParseStatementCli <file> [--password <password>] [--json] [--debug]");
            System.exit(2);
        }
        byte[] bytes = null;
        try {
            bytes = Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            System.err.println("ERROR: cannot read " + path + ": " + e.getMessage());
            System.exit(1);
        }
        Consumer<String> dbg = debug ? System.err::println : s -> {
        };
        ParsedStatement ps = null;
        try {
            ps = StatementParseEngine.parse(bytes, password, dbg);
        } catch (StatementParseException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
        LinkedHashMap<String, Object> report = buildReport(ps);
        if (json) {
            System.out.println(JsonSupport.toJson(report));
            return;
        }
        printHuman(report, ps);
    }

    private static Object num(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    private static LinkedHashMap<String, Object> buildReport(ParsedStatement ps) {
        StatementMeta meta = ps.meta();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bank", meta.bank);
        metadata.put("account_number", meta.accountNumber);
        metadata.put("ifsc", meta.ifsc);
        metadata.put("holder_name", meta.holderName);
        metadata.put("period_start", meta.periodStart == null ? null : meta.periodStart.toString());
        metadata.put("period_end", meta.periodEnd == null ? null : meta.periodEnd.toString());
        metadata.put("opening_balance", num(meta.openingBalance));
        metadata.put("closing_balance", num(meta.closingBalance));
        metadata.put("statement_type", ps.statementType());

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ps.summaryFields().entrySet()) {
            Object v = e.getValue();
            if (v instanceof LocalDate d) {
                summary.put(e.getKey(), d.toString());
            } else if (v instanceof Double d) {
                summary.put(e.getKey(), BigDecimal.valueOf(d));
            } else {
                summary.put(e.getKey(), v);
            }
        }

        Derived d = ps.derived();
        LinkedHashMap<String, Object> derived = new LinkedHashMap<>();
        derived.put("statement_type", ps.statementType());
        derived.put("transaction_count", d.transactionCount);
        derived.put("debit_count", d.debitCount);
        derived.put("credit_count", d.creditCount);
        derived.put("avg_debit", num(d.avgDebit));
        derived.put("avg_daily_spend", num(d.avgDailySpend));
        derived.put("active_days", d.activeDays);
        derived.put("largest_debit", rowSummary(d.largestDebit));
        derived.put("largest_credit", rowSummary(d.largestCredit));
        List<Object> merchants = new ArrayList<>();
        for (Map.Entry<String, Double> e : d.topMerchants) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("merchant", e.getKey());
            m.put("spend", BigDecimal.valueOf(Math.round(e.getValue() * 100.0) / 100.0));
            merchants.add(m);
        }
        derived.put("top_merchants", merchants);

        ParseInfo pi = ps.parseInfo();
        LinkedHashMap<String, Object> parse = new LinkedHashMap<>();
        parse.put("mode", pi.mode);
        parse.put("transactions", ps.transactions().size());
        parse.put("rows_chain_validated", pi.rowsChainValidated);
        // Python emits int 0 when there are no rows, a float otherwise
        parse.put("chain_validation_pct", ps.transactions().isEmpty()
                ? Integer.valueOf(0) : BigDecimal.valueOf(pi.chainValidationPct));
        parse.put("total_credits", BigDecimal.valueOf(pi.totalCredits));
        parse.put("total_debits", BigDecimal.valueOf(pi.totalDebits));
        parse.put("checksum_opening_plus_net_equals_closing", pi.checksumOk);
        LinkedHashMap<String, Object> totals = new LinkedHashMap<>();
        totals.put("debits", pi.crossDebits);
        totals.put("credits", pi.crossCredits);
        parse.put("totals_found_in_statement_summary", totals);
        parse.put("card_checks", new LinkedHashMap<>(pi.cardChecks));

        List<Object> txns = new ArrayList<>();
        for (RowResult r : ps.transactions()) {
            LinkedHashMap<String, Object> t = new LinkedHashMap<>();
            t.put("date", r.date.toString());
            t.put("description", r.description);
            t.put("amount", num(r.amount));
            t.put("balance", num(r.balance));
            t.put("chain_valid", r.chainValid);
            txns.add(t);
        }

        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("metadata", metadata);
        report.put("summary_fields", summary);
        report.put("derived", derived);
        report.put("parse", parse);
        report.put("transactions", txns);
        return report;
    }

    private static Object rowSummary(RowResult r) {
        if (r == null) {
            return null;
        }
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("date", r.date.toString());
        m.put("amount", num(r.amount));
        m.put("description", r.description);
        return m;
    }

    // All Jackson references live in this nested class so the outer class loads
    // (and the file-read error path runs) without Jackson on the classpath.
    private static final class JsonSupport {
        static String toJson(Object report) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                return escapeNonAscii(mapper.writer(new PythonPrettyPrinter()).writeValueAsString(report));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        // Python json.dumps escapes non-ASCII with lowercase hex; Jackson 2.15 only
        // does uppercase, so escape here — non-ASCII only occurs inside string
        // literals, and per-char UTF-16 escaping matches Python's surrogate pairs.
        private static String escapeNonAscii(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    sb.append(c);
                } else {
                    sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                }
            }
            return sb.toString();
        }

        // Matches Python json.dumps(report, indent=2): 2-space indent for objects AND
        // arrays, ": " field separator (no space before colon), bare {} / [] empties.
        private static final class PythonPrettyPrinter extends DefaultPrettyPrinter {
            PythonPrettyPrinter() {
                DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
                indentObjectsWith(indenter);
                indentArraysWith(indenter);
            }

            private PythonPrettyPrinter(PythonPrettyPrinter base) {
                super(base);
            }

            @Override
            public DefaultPrettyPrinter createInstance() {
                return new PythonPrettyPrinter(this);
            }

            @Override
            public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
                g.writeRaw(": ");
            }

            @Override
            public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
                if (!_objectIndenter.isInline()) {
                    --_nesting;
                }
                if (nrOfEntries > 0) {
                    _objectIndenter.writeIndentation(g, _nesting);
                }
                g.writeRaw('}');
            }

            @Override
            public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
                if (!_arrayIndenter.isInline()) {
                    --_nesting;
                }
                if (nrOfValues > 0) {
                    _arrayIndenter.writeIndentation(g, _nesting);
                }
                g.writeRaw(']');
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void printHuman(LinkedHashMap<String, Object> report, ParsedStatement ps) {
        PrintStream out = System.out;
        String sep = "=".repeat(78);
        out.println(sep);
        out.println("STATEMENT METADATA (all inferred)");
        out.println(sep);
        LinkedHashMap<String, Object> metadata = (LinkedHashMap<String, Object>) report.get("metadata");
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            out.println(String.format(Locale.US, "  %-18s %s", e.getKey(),
                    e.getValue() != null ? String.valueOf(e.getValue()) : "-- not found --"));
        }
        out.println();

        LinkedHashMap<String, Object> summary = (LinkedHashMap<String, Object>) report.get("summary_fields");
        if (!summary.isEmpty()) {
            out.println(sep);
            out.println("SUMMARY FIELDS (label-harvested)");
            out.println(sep);
            for (Map.Entry<String, Object> e : summary.entrySet()) {
                if (e.getValue() instanceof BigDecimal bd) {
                    out.println(String.format(Locale.US, "  %-24s %,.2f", e.getKey(), bd.doubleValue()));
                } else {
                    out.println(String.format(Locale.US, "  %-24s %s", e.getKey(), e.getValue()));
                }
            }
            out.println();
        }

        LinkedHashMap<String, Object> d = (LinkedHashMap<String, Object>) report.get("derived");
        out.println(sep);
        out.println("DERIVED SUMMARY");
        out.println(sep);
        out.println("  statement type     " + d.get("statement_type"));
        out.println(String.format(Locale.US,
                "  transactions       %d (%d debits, %d credits) across %d active days",
                d.get("transaction_count"), d.get("debit_count"), d.get("credit_count"),
                d.get("active_days")));
        LinkedHashMap<String, Object> ld = (LinkedHashMap<String, Object>) d.get("largest_debit");
        if (ld != null) {
            out.println(String.format(Locale.US, "  largest debit      %,.2f on %s — %s",
                    ((BigDecimal) ld.get("amount")).doubleValue(), ld.get("date"), ld.get("description")));
        }
        LinkedHashMap<String, Object> lc = (LinkedHashMap<String, Object>) d.get("largest_credit");
        if (lc != null) {
            out.println(String.format(Locale.US, "  largest credit     %+,.2f on %s — %s",
                    ((BigDecimal) lc.get("amount")).doubleValue(), lc.get("date"), lc.get("description")));
        }
        if (d.get("avg_debit") != null) {
            out.println(String.format(Locale.US, "  avg debit          %,.2f   avg daily spend %,.2f",
                    ((BigDecimal) d.get("avg_debit")).doubleValue(),
                    ((BigDecimal) d.get("avg_daily_spend")).doubleValue()));
        }
        List<Map<String, Object>> merchants = (List<Map<String, Object>>) d.get("top_merchants");
        if (!merchants.isEmpty()) {
            StringBuilder sb = new StringBuilder("  top merchants      ");
            for (int i = 0; i < merchants.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                Map<String, Object> m = merchants.get(i);
                sb.append(m.get("merchant")).append(" (")
                        .append(String.format(Locale.US, "%,.0f", ((BigDecimal) m.get("spend")).doubleValue()))
                        .append(')');
            }
            out.println(sb);
        }
        out.println();

        ParseInfo pi = ps.parseInfo();
        out.println(sep);
        out.println("TRANSACTIONS (" + ps.transactions().size() + ")   mode: " + pi.mode);
        out.println(sep);
        out.println(String.format(Locale.US, "  %-12s%14s%14s  %-3s %s",
                "DATE", "AMOUNT", "BALANCE", "OK", "DESCRIPTION"));
        for (Map<String, Object> t : (List<Map<String, Object>>) report.get("transactions")) {
            BigDecimal amount = (BigDecimal) t.get("amount");
            BigDecimal balance = (BigDecimal) t.get("balance");
            String amt = amount != null ? String.format(Locale.US, "%+,.2f", amount.doubleValue()) : "?";
            String bal = balance != null ? String.format(Locale.US, "%,.2f", balance.doubleValue()) : "-";
            String ok = Boolean.TRUE.equals(t.get("chain_valid")) ? "✓" : "·";
            out.println(String.format(Locale.US, "  %-12s%14s%14s  %-3s %s",
                    t.get("date"), amt, bal, ok, t.get("description")));
        }
        out.println();

        LinkedHashMap<String, Object> p = (LinkedHashMap<String, Object>) report.get("parse");
        out.println(sep);
        out.println("VALIDATION");
        out.println(sep);
        String chainLabel = pi.mode.startsWith("opening/closing")
                ? "rows on reconciled opening->closing chain"
                : "rows validated by balance chain";
        out.println(String.format(Locale.US, "  %-31s : %s/%s (%s%%)",
                chainLabel, p.get("rows_chain_validated"), p.get("transactions"),
                p.get("chain_validation_pct")));
        out.println(String.format(Locale.US, "  total credits                   : %,.2f",
                ((BigDecimal) p.get("total_credits")).doubleValue()));
        out.println(String.format(Locale.US, "  total debits                    : %,.2f",
                ((BigDecimal) p.get("total_debits")).doubleValue()));
        Boolean cs = pi.checksumOk;
        out.println("  opening + net == closing        : "
                + (Boolean.TRUE.equals(cs) ? "PASS ✓"
                : Boolean.FALSE.equals(cs) ? "FAIL ✗" : "n/a (missing opening/closing)"));
        out.println("  totals in statement summary     : debits " + fmtCross(pi.crossDebits)
                + ", credits " + fmtCross(pi.crossCredits));
        for (Map.Entry<String, Boolean> e : pi.cardChecks.entrySet()) {
            out.println(String.format(Locale.US, "  card: %-25s : %s", e.getKey(),
                    Boolean.TRUE.equals(e.getValue()) ? "PASS ✓" : "FAIL ✗"));
        }
        out.println("  verdict                         : " + pi.verdict);
    }

    private static String fmtCross(Boolean v) {
        if (Boolean.TRUE.equals(v)) {
            return "FOUND ✓";
        }
        return Boolean.FALSE.equals(v) ? "not found" : "n/a";
    }
}
