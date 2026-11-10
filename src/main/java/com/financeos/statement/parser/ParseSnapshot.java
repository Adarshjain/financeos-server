package com.financeos.statement.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable, fully ordered JSON snapshot of everything the statement parser returns (except the derived stats and the
 * summary text). The one renderer used both by the corpus tool and by production, which stores it with each statement. Money is written as a plain 2 dp decimal string, dates as ISO strings, nulls as JSON null. Key
 * order is fixed, map keys are sorted, so the same parse always gives byte-identical output.
 */
public final class ParseSnapshot {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ParseSnapshot() {
    }

    /** The snapshot as compact JSON text (no whitespace); the same parse always gives the same string. */
    public static String json(ParsedStatement ps) {
        return of(ps).toString();
    }

    public static ObjectNode of(ParsedStatement ps) {
        ObjectNode root = F.objectNode();
        // only a layout-read result names its reader: v1 snapshots stay exactly as they were
        if (ps.reader() != null && !"v1".equals(ps.reader())) {
            root.set("layoutId", value(ps.layoutId()));
            root.put("reader", ps.reader());
        }
        root.set("meta", meta(ps.meta()));
        root.put("statementType", ps.statementType());
        root.set("parseInfo", parseInfo(ps.parseInfo()));
        ObjectNode summary = root.putObject("summaryFields");
        List<String> keys = new ArrayList<>(ps.summaryFields().keySet());
        Collections.sort(keys);
        for (String k : keys) summary.set(k, value(ps.summaryFields().get(k)));
        ArrayNode txns = root.putArray("transactions");
        int i = 0;
        for (RowResult r : ps.transactions()) txns.add(row(i++, r));
        return root;
    }

    /** Plain decimal with 2 dp, the same way for every money value. */
    public static String money(Number n) {
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static JsonNode value(Object v) {
        if (v == null) return F.nullNode();
        if (v instanceof LocalDate d) return F.textNode(d.toString());
        if (v instanceof Boolean b) return F.booleanNode(b);
        if (v instanceof Number n) return F.textNode(money(n));
        return F.textNode(String.valueOf(v));
    }

    private static ObjectNode meta(StatementMeta m) {
        ObjectNode o = F.objectNode();
        o.set("bank", value(m.bank));
        o.set("accountNumber", value(m.accountNumber));
        o.set("ifsc", value(m.ifsc));
        o.set("holderName", value(m.holderName));
        o.set("periodStart", value(m.periodStart));
        o.set("periodEnd", value(m.periodEnd));
        o.set("openingBalance", value(m.openingBalance));
        o.set("closingBalance", value(m.closingBalance));
        o.set("issuerId", value(m.issuerId));
        o.set("documentKind", value(m.documentKind));
        o.set("isStatement", value(m.isStatement));
        o.set("documentDate", value(m.documentDate));
        o.set("productPhrase", value(m.productPhrase));
        o.set("creditLimit", value(m.creditLimit));
        o.put("closureStated", m.closureStated);
        return o;
    }

    private static ObjectNode parseInfo(ParseInfo p) {
        ObjectNode o = F.objectNode();
        o.set("mode", value(p.mode));
        o.set("verdict", value(p.verdict));
        o.put("chainValidationPct", money(p.chainValidationPct));
        o.put("rowsChainValidated", p.rowsChainValidated);
        o.put("totalCredits", money(p.totalCredits));
        o.put("totalDebits", money(p.totalDebits));
        o.set("checksumOk", value(p.checksumOk));
        o.set("crossDebits", value(p.crossDebits));
        o.set("crossCredits", value(p.crossCredits));
        if (p.cardChecks == null) {
            o.set("cardChecks", F.nullNode());
        } else {
            ObjectNode c = o.putObject("cardChecks");
            List<String> keys = new ArrayList<>(p.cardChecks.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                c.set(k, value(p.cardChecks.get(k)));
            }
        }
        o.put("rowsDropped", p.rowsDropped);
        return o;
    }

    private static ObjectNode row(int index, RowResult r) {
        ObjectNode o = F.objectNode();
        o.put("index", index);
        o.set("date", value(r.date));
        o.set("description", value(r.description));
        o.set("amount", value(r.amount));
        o.set("balance", value(r.balance));
        o.put("chainValid", r.chainValid);
        o.set("signSource", value(r.signSource));
        o.set("cardLast4", value(r.cardLast4));
        return o;
    }
}
