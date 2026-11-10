package com.financeos.statement.parser.corpus;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.statement.parser.ParseSnapshot;
import com.financeos.statement.parser.ParsedStatement;


/**
 * Stable, fully ordered JSON snapshot of everything the statement parser returns (except the derived stats and the
 * summary text). Money is written as a plain 2 dp decimal string, dates as ISO strings, nulls as JSON null. Key
 * order is fixed, map keys are sorted, so the same parse always gives byte-identical output.
 */
public final class CorpusSnapshot {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private CorpusSnapshot() {
    }

    public static ObjectNode of(ParsedStatement ps) {
        return ParseSnapshot.of(ps);
    }

    public static ObjectNode ofFailure(Throwable t) {
        ObjectNode root = F.objectNode();
        ObjectNode f = root.putObject("failure");
        f.put("type", t.getClass().getSimpleName());
        f.put("message", t.getMessage());
        return root;
    }

    /** Plain decimal with 2 dp, the same way for every money value. */
    static String money(Number n) {
        return ParseSnapshot.money(n);
    }
}
