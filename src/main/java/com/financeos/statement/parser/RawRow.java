package com.financeos.statement.parser;

// isNewTxn: true = transaction row, false = continuation line, null = opening/closing marker row
record RawRow(Line line, Boolean isNewTxn, DateAnchor anchor, String cardLast4) {
    RawRow(Line line, Boolean isNewTxn, DateAnchor anchor) {
        this(line, isNewTxn, anchor, null);
    }
}
